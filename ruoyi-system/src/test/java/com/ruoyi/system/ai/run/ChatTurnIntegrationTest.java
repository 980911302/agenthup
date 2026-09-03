package com.ruoyi.system.ai.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.web.reactive.function.client.WebClient;
import com.ruoyi.system.ai.event.ChatEventSink;
import com.ruoyi.system.ai.memory.TokenEstimator;
import com.ruoyi.system.ai.metering.CacheUsageProbe;
import com.ruoyi.system.ai.metering.LlmCallCollector;
import com.ruoyi.system.domain.AiLlmCall;
import com.ruoyi.system.mapper.AiLlmCallMapper;
import reactor.core.publisher.Flux;

/**
 * 主干集成测试：真实 {@link OpenAiChatModel} + MockWebServer 上游 + {@link LlmCallCollector}。
 * <p>覆盖用量解析、差值记账、缓存探针与错误路径；mapper 用内存假对象，不依赖 H2。
 */
class ChatTurnIntegrationTest
{
    private MockWebServer server;
    private CacheUsageProbe probe;
    private RecordingLlmCallMapper mapper;
    private TokenEstimator estimator;

    @BeforeEach
    void setUp() throws IOException
    {
        server = new MockWebServer();
        server.start();
        probe = new CacheUsageProbe();
        mapper = new RecordingLlmCallMapper();
        estimator = new TokenEstimator();
    }

    @AfterEach
    void tearDown() throws IOException
    {
        server.shutdown();
    }

    @Test
    void singleCall_recordsUsageFromSse() throws Exception
    {
        enqueueSse("chat-turn/sse-simple-with-usage.txt");
        LlmCallCollector.TurnUsage turn = runSimple("hi");

        assertEquals(1, turn.callCount());
        assertEquals(100, turn.promptTokens());
        assertEquals(5, turn.completionTokens());
        assertEquals(105, turn.totalTokens());
        assertEquals(LlmCallCollector.SOURCE_REAL, turn.usageSource());
        assertEquals(1, mapper.rows.size());
        assertEquals(100, mapper.rows.get(0).getPromptTokens());
        assertEquals(5, mapper.rows.get(0).getCompletionTokens());
    }

    @Test
    void singleCall_withCachedTokens_viaProbe() throws Exception
    {
        enqueueSse("chat-turn/sse-with-cached-tokens.txt");
        LlmCallCollector.TurnUsage turn = runSimple("cache please");

        assertEquals(1, turn.callCount());
        assertEquals(1, mapper.rows.size());
        AiLlmCall row = mapper.rows.get(0);
        assertEquals(200, row.getPromptTokens());
        // 探针扫到 prompt_tokens_details.cached_tokens=128
        assertEquals(128, row.getCacheHitTokens());
        assertEquals(72, row.getCacheMissTokens());
        assertEquals(row.getPromptTokens().intValue(),
                row.getCacheHitTokens() + row.getCacheMissTokens());
    }

    @Test
    void toolLoop_threeRounds_recordsDeltas() throws Exception
    {
        // ChatClient 工具循环的 MessageAggregator 会把多轮 usage 聚成一条交给外层订阅者,
        // 抓不到中间轮。这里用三次真实 HTTP 解析(OpenAiChatModel) + 按 UsageCalculator
        // 同样的累加规则喂 collector,覆盖「累计 usage → 差值落库」回归。
        List<ParsedUsage> rounds = parseThreeToolRounds();
        assertEquals(3, rounds.size());
        assertEquals(2585, rounds.get(0).prompt());
        assertEquals(4563, rounds.get(1).prompt());
        assertEquals(6541, rounds.get(2).prompt());

        LlmCallCollector collector = newCollector();
        int cumP = 0;
        int cumC = 0;
        int cumT = 0;
        for (ParsedUsage u : rounds)
        {
            cumP += u.prompt();
            cumC += u.completion();
            cumT += u.total();
            // 模拟 Spring AI UsageCalculator 的累计值 + 真实 responseId(供探针 take)
            collector.onResponse(responseWithUsage(cumP, cumC, cumT, u.responseId()));
        }
        LlmCallCollector.TurnUsage turn = collector.onComplete("tool loop", "done");

        assertEquals(3, turn.callCount());
        assertEquals(3, mapper.rows.size());
        assertEquals(2585, mapper.rows.get(0).getPromptTokens());
        assertEquals(4563, mapper.rows.get(1).getPromptTokens());
        assertEquals(6541, mapper.rows.get(2).getPromptTokens());
        assertEquals(2585 + 4563 + 6541, turn.promptTokens());
        assertEquals(100 + 50 + 20, turn.completionTokens());
    }

    @Test
    void toolLoop_eachRoundHasCacheTokens() throws Exception
    {
        List<ParsedUsage> rounds = parseThreeToolRounds();
        LlmCallCollector collector = newCollector();
        int cumP = 0;
        int cumC = 0;
        int cumT = 0;
        for (ParsedUsage u : rounds)
        {
            cumP += u.prompt();
            cumC += u.completion();
            cumT += u.total();
            collector.onResponse(responseWithUsage(cumP, cumC, cumT, u.responseId()));
        }
        collector.onComplete("tool cache", "done");

        assertEquals(3, mapper.rows.size());
        for (AiLlmCall row : mapper.rows)
        {
            int hit = nvl(row.getCacheHitTokens());
            int miss = nvl(row.getCacheMissTokens());
            assertTrue(hit + miss > 0,
                    "每行都应有缓存数据, call_seq=" + row.getCallSeq()
                            + " hit=" + hit + " miss=" + miss);
            assertEquals(nvl(row.getPromptTokens()), hit + miss,
                    "cache_sum 应等于该次调用 prompt(增量口径与探针单次口径对齐)");
        }
        assertEquals(1024, mapper.rows.get(0).getCacheHitTokens());
        assertEquals(2048, mapper.rows.get(1).getCacheHitTokens());
        assertEquals(4096, mapper.rows.get(2).getCacheHitTokens());
    }

    @Test
    void noUsage_fallsBackToEstimate() throws Exception
    {
        enqueueSse("chat-turn/sse-no-usage.txt");
        LlmCallCollector.TurnUsage turn = runSimple("no usage");

        assertEquals(1, turn.callCount());
        assertEquals(LlmCallCollector.SOURCE_ESTIMATED, turn.usageSource());
        assertEquals(1, mapper.rows.size());
        assertEquals("1", mapper.rows.get(0).getUsageSource());
        assertTrue(mapper.rows.get(0).getTotalTokens() > 0);
    }

    @Test
    void midStreamError_emitsFailureAndNoDirtyRows() throws Exception
    {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("upstream boom"));

        List<String> events = new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        OpenAiChatModel model = buildModel();
        LlmCallCollector collector = newCollector();
        ChatEventSink sink = events::add;

        Flux<ChatResponse> flux = ChatClient.create(model).prompt().user("fail").stream().chatResponse();
        flux.subscribe(
                r -> collector.onResponse(r),
                e -> {
                    error.set(e);
                    sink.emit("{\"type\":\"error\",\"message\":\"" + e.getMessage() + "\"}");
                    done.countDown();
                },
                done::countDown);

        assertTrue(done.await(15, TimeUnit.SECONDS), "应在超时前结束");
        assertNotNull(error.get(), "流中途错误应进入 error 回调");
        assertFalse(events.isEmpty());
        assertTrue(events.get(0).contains("error") || events.get(0).contains("500")
                || events.get(0).contains("boom") || events.get(0).contains("Error"));
        // 错误路径不 complete collector → 不应落半截成功行
        assertEquals(0, mapper.rows.size());
    }

    // ---------- helpers ----------

    private LlmCallCollector.TurnUsage runSimple(String user) throws Exception
    {
        OpenAiChatModel model = buildModel();
        LlmCallCollector collector = newCollector();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();

        Flux<ChatResponse> flux = ChatClient.create(model).prompt().user(user).stream().chatResponse();
        flux.subscribe(
                collector::onResponse,
                e -> {
                    error.set(e);
                    done.countDown();
                },
                done::countDown);

        assertTrue(done.await(20, TimeUnit.SECONDS), "流未在超时内结束");
        if (error.get() != null)
        {
            fail("上游流失败: " + error.get().getMessage(), error.get());
        }
        return collector.onComplete(user, "reply");
    }

    /**
     * 三次独立 HTTP 调用，经真实 OpenAiChatModel 解析；探针同时扫到 cached_tokens。
     * 返回每轮 HTTP 自身的 usage（非累计）与 responseId。
     */
    private List<ParsedUsage> parseThreeToolRounds() throws Exception
    {
        enqueueSse("chat-turn/sse-tool-round1.txt");
        enqueueSse("chat-turn/sse-tool-round2.txt");
        enqueueSse("chat-turn/sse-tool-round3.txt");

        OpenAiChatModel model = buildModel();
        List<ParsedUsage> rounds = new ArrayList<>();
        for (int i = 0; i < 3; i++)
        {
            rounds.add(parseOneRound(model, "round-" + i));
        }
        return rounds;
    }

    private ParsedUsage parseOneRound(OpenAiChatModel model, String user) throws Exception
    {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicReference<ChatResponse> last = new AtomicReference<>();

        Flux<ChatResponse> flux = model.stream(new org.springframework.ai.chat.prompt.Prompt(user));
        flux.subscribe(
                r -> {
                    if (r != null && r.getMetadata() != null && r.getMetadata().getUsage() != null
                            && hasPositiveUsage(r.getMetadata().getUsage()))
                    {
                        last.set(r);
                    }
                },
                e -> {
                    error.set(e);
                    done.countDown();
                },
                done::countDown);

        assertTrue(done.await(20, TimeUnit.SECONDS), "单轮流未结束: " + user);
        if (error.get() != null)
        {
            fail("解析 SSE 失败: " + error.get().getMessage(), error.get());
        }
        ChatResponse response = last.get();
        assertNotNull(response, "应解析到带 usage 的末包: " + user);
        var usage = response.getMetadata().getUsage();
        String id = response.getMetadata().getId();
        return new ParsedUsage(
                nvl(usage.getPromptTokens()),
                nvl(usage.getCompletionTokens()),
                nvl(usage.getTotalTokens()) > 0
                        ? nvl(usage.getTotalTokens())
                        : nvl(usage.getPromptTokens()) + nvl(usage.getCompletionTokens()),
                id);
    }

    private static ChatResponse responseWithUsage(int prompt, int completion, int total, String responseId)
    {
        org.springframework.ai.chat.metadata.DefaultUsage usage =
                new org.springframework.ai.chat.metadata.DefaultUsage(prompt, completion, total);
        org.springframework.ai.chat.metadata.ChatResponseMetadata meta =
                org.springframework.ai.chat.metadata.ChatResponseMetadata.builder()
                        .id(responseId)
                        .model("test-model")
                        .usage(usage)
                        .build();
        return new ChatResponse(List.of(), meta);
    }

    private static boolean hasPositiveUsage(org.springframework.ai.chat.metadata.Usage usage)
    {
        return nvl(usage.getPromptTokens()) > 0
                || nvl(usage.getCompletionTokens()) > 0
                || nvl(usage.getTotalTokens()) > 0;
    }

    private OpenAiChatModel buildModel()
    {
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(server.url("/").toString().replaceAll("/$", ""))
                .apiKey("test-key")
                .webClientBuilder(probe.decorate(WebClient.builder()))
                .build();
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model("test-model")
                .streamUsage(true)
                // 测试只关心 SSE/usage 解析,不走工具执行(无 ToolCallback 会炸)
                .internalToolExecutionEnabled(false)
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                .build();
    }

    private record ParsedUsage(int prompt, int completion, int total, String responseId)
    {
    }

    private LlmCallCollector newCollector()
    {
        return new LlmCallCollector("s-it", 1L, "s-it:1", 10L, "test-model", 0,
                mapper, estimator, probe);
    }

    private void enqueueSse(String resource) throws IOException
    {
        String body = loadResource(resource);
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(body));
    }

    private static String loadResource(String path) throws IOException
    {
        try (InputStream in = ChatTurnIntegrationTest.class.getClassLoader().getResourceAsStream(path))
        {
            if (in == null)
            {
                throw new IOException("missing resource: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int nvl(Integer v)
    {
        return v == null ? 0 : v;
    }

    /** 只记录 insert 参数的假 mapper，覆盖核心断言即可。 */
    private static final class RecordingLlmCallMapper implements AiLlmCallMapper
    {
        private final List<AiLlmCall> rows = new ArrayList<>();
        private final AtomicLong seq = new AtomicLong(1);

        @Override
        public int insertLlmCall(AiLlmCall call)
        {
            call.setCallId(seq.getAndIncrement());
            rows.add(call);
            return 1;
        }

        @Override
        public int bindMessageIdByIds(Long messageId, List<Long> callIds)
        {
            return 0;
        }

        @Override
        public int unbindMessageIds(List<Long> messageIds)
        {
            return 0;
        }

        @Override
        public int unbindMessageIdsBySession(String sessionId)
        {
            return 0;
        }

        @Override
        public int deleteBySessionId(String sessionId)
        {
            return 0;
        }

        @Override
        public int countEstimatedBySession(String sessionId)
        {
            return 0;
        }

        @Override
        public long selectLatestPromptTokens(String sessionId, String conversationId)
        {
            return 0L;
        }

        @Override
        public long selectPeakPromptTokens(String sessionId, String conversationId)
        {
            return 0L;
        }

        @Override
        public Map<String, Object> sumCacheTokensBySession(String sessionId)
        {
            return Map.of();
        }

        @Override
        public Map<String, Object> selectOverview(java.util.Date since)
        {
            return Map.of();
        }

        @Override
        public List<Map<String, Object>> selectByModel(java.util.Date since, int limit)
        {
            return List.of();
        }

        @Override
        public List<Map<String, Object>> selectByAgent(java.util.Date since, int limit)
        {
            return List.of();
        }

        @Override
        public List<Map<String, Object>> selectTrend(java.util.Date since)
        {
            return List.of();
        }

        @Override
        public Map<String, Object> selectCacheStats(java.util.Date since)
        {
            return Map.of();
        }

        @Override
        public Map<String, Object> selectUserOverview(java.util.Date since, Long userId)
        {
            return Map.of();
        }

        @Override
        public List<Map<String, Object>> selectUserTrend(java.util.Date since, Long userId)
        {
            return List.of();
        }

        @Override
        public Map<String, Object> selectUserCacheStats(java.util.Date since, Long userId)
        {
            return Map.of();
        }

        @Override
        public int deleteByModelIds(List<Long> modelIds)
        {
            return 0;
        }
    }
}
