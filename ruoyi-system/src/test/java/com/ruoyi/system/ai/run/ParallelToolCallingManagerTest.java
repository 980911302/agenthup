package com.ruoyi.system.ai.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.ai.tool.resolution.StaticToolCallbackResolver;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;

import com.ruoyi.system.tool.PromptMediaBuffer;

/**
 * 并行工具调用管理器测试。
 * <p>覆盖：并行提速、结果按原顺序归位、单工具直接执行、超时兜底、异常转错误文本，
 * 以及子线程产出的 prompt media 回收（见 {@code promptMedia_*} 用例）。
 */
class ParallelToolCallingManagerTest
{
    private ExecutorService executor;

    @BeforeEach
    void setUp()
    {
        executor = Executors.newFixedThreadPool(4);
    }

    @AfterEach
    void tearDown()
    {
        executor.shutdownNow();
    }

    private ParallelToolCallingManager manager(List<ToolCallback> tools, int maxParallel, Duration timeout)
    {
        return new ParallelToolCallingManager(
                new StaticToolCallbackResolver(tools),
                DefaultToolExecutionExceptionProcessor.builder().build(),
                executor, maxParallel, timeout);
    }

    @Test
    void parallelExecution_isFasterThanSequential() throws Exception
    {
        StubTool slowA = new StubTool("slowA", 400, false);
        StubTool slowB = new StubTool("slowB", 400, false);
        ParallelToolCallingManager manager = manager(List.of(slowA, slowB), 2, Duration.ofSeconds(5));

        long start = System.nanoTime();
        ToolExecutionResult result = manager.executeToolCalls(buildPrompt(slowA, slowB), buildResponse("slowA", "slowB"));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        // 并行：两个 400ms 工具应远小于串行 800ms；给 CI 余量按 <650ms 断言
        assertTrue(elapsedMs < 650, "并行执行应快于串行, 实际耗时 " + elapsedMs + "ms");
        assertNotNull(result);
    }

    @Test
    void responses_keptInOriginalToolCallOrder() throws Exception
    {
        StubTool a = new StubTool("alpha", 200, false);
        StubTool b = new StubTool("beta", 50, false);
        StubTool c = new StubTool("gamma", 120, false);
        ParallelToolCallingManager manager = manager(List.of(a, b, c), 3, Duration.ofSeconds(5));

        ToolExecutionResult result = manager.executeToolCalls(
                buildPrompt(a, b, c), buildResponse("alpha", "beta", "gamma"));

        ToolResponseMessage trm = lastToolResponse(result);
        assertEquals(3, trm.getResponses().size());
        // 顺序必须与 toolCalls 一致（模型按 id 匹配）
        assertEquals("call_alpha", trm.getResponses().get(0).id());
        assertEquals("alpha", trm.getResponses().get(0).name());
        assertEquals("result:alpha", trm.getResponses().get(0).responseData());
        assertEquals("call_beta", trm.getResponses().get(1).id());
        assertEquals("result:beta", trm.getResponses().get(1).responseData());
        assertEquals("call_gamma", trm.getResponses().get(2).id());
        assertEquals("result:gamma", trm.getResponses().get(2).responseData());
    }

    @Test
    void singleToolCall_executesDirectly() throws Exception
    {
        StubTool a = new StubTool("solo", 10, false);
        ParallelToolCallingManager manager = manager(List.of(a), 2, Duration.ofSeconds(5));

        ToolExecutionResult result = manager.executeToolCalls(buildPrompt(a), buildResponse("solo"));

        ToolResponseMessage trm = lastToolResponse(result);
        assertEquals(1, trm.getResponses().size());
        assertEquals("result:solo", trm.getResponses().get(0).responseData());
    }

    @Test
    void singleToolCall_timeoutReturnsErrorInsteadOfBlocking()
    {
        StubTool stuck = new StubTool("stuckSolo", 10_000, false);
        ParallelToolCallingManager manager = manager(List.of(stuck), 2, Duration.ofMillis(300));

        long start = System.nanoTime();
        ToolExecutionResult result = manager.executeToolCalls(buildPrompt(stuck), buildResponse("stuckSolo"));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue(elapsedMs < 5_000, "单工具超时应秒级返回, 实际耗时 " + elapsedMs + "ms");
        ToolResponseMessage trm = lastToolResponse(result);
        assertEquals(1, trm.getResponses().size());
        assertTrue(trm.getResponses().get(0).responseData().contains("Error"),
                "超时单工具应返回错误响应: " + trm.getResponses().get(0).responseData());
    }

    @Test
    void timeout_returnsErrorResponseInsteadOfBlocking() throws Exception
    {
        StubTool stuck = new StubTool("stuck", 10_000, false);
        StubTool fast = new StubTool("fast", 10, false);
        ParallelToolCallingManager manager = manager(List.of(stuck, fast), 2, Duration.ofMillis(300));

        long start = System.nanoTime();
        ToolExecutionResult result = manager.executeToolCalls(buildPrompt(stuck, fast), buildResponse("stuck", "fast"));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue(elapsedMs < 5_000, "超时工具不应阻塞整轮, 实际耗时 " + elapsedMs + "ms");
        ToolResponseMessage trm = lastToolResponse(result);
        assertEquals(2, trm.getResponses().size());
        assertTrue(trm.getResponses().get(0).responseData().contains("Error"), "超时工具应返回错误响应");
        assertEquals("result:fast", trm.getResponses().get(1).responseData());
    }

    @Test
    void exception_returnsErrorTextBackToModel() throws Exception
    {
        StubTool boom = new StubTool("boom", 0, true);
        ParallelToolCallingManager manager = manager(List.of(boom), 2, Duration.ofSeconds(5));

        ToolExecutionResult result = manager.executeToolCalls(buildPrompt(boom), buildResponse("boom"));

        ToolResponseMessage trm = lastToolResponse(result);
        assertEquals(1, trm.getResponses().size());
        assertTrue(trm.getResponses().get(0).responseData().contains("boom"),
                "异常应转成错误文本回填给模型: " + trm.getResponses().get(0).responseData());
    }

    @Test
    void unknownTool_returnsErrorAndDoesNotThrow() throws Exception
    {
        StubTool a = new StubTool("known", 10, false);
        ParallelToolCallingManager manager = manager(List.of(a), 2, Duration.ofSeconds(5));

        ToolExecutionResult result = manager.executeToolCalls(buildPrompt(a), buildResponse("known", "ghost"));

        ToolResponseMessage trm = lastToolResponse(result);
        assertEquals(2, trm.getResponses().size());
        assertTrue(trm.getResponses().get(1).responseData().contains("does not exist"));
    }

    // ---------- helpers ----------

    private ToolResponseMessage lastToolResponse(ToolExecutionResult result)
    {
        List<Message> history = result.conversationHistory();
        assertNotNull(history);
        Message last = history.get(history.size() - 1);
        assertTrue(last instanceof ToolResponseMessage, "最后一条消息应为 ToolResponseMessage");
        return (ToolResponseMessage) last;
    }

    private Prompt buildPrompt(ToolCallback... tools)
    {
        return new Prompt(List.of(new UserMessage("hi")),
                ToolCallingChatOptions.builder().toolCallbacks(tools).build());
    }

    private ChatResponse buildResponse(String... toolNames)
    {
        List<AssistantMessage.ToolCall> calls = java.util.stream.IntStream.range(0, toolNames.length)
                .mapToObj(i -> new AssistantMessage.ToolCall(
                        "call_" + toolNames[i], "function", toolNames[i], "{}"))
                .toList();
        AssistantMessage assistant = AssistantMessage.builder()
                .content("need tools")
                .toolCalls(calls)
                .build();
        return ChatResponse.builder()
                .generations(List.of(new Generation(assistant)))
                .build();
    }


    /**
     * 回归：并行批次里子线程产出的图片必须回到调用线程。
     *
     * <p>{@code PromptMediaBuffer} 是 ThreadLocal —— 截图工具写在并行子线程，
     * 而 {@code AgentToolLoop} 在调用线程 drain。不显式回收的话图片被静默丢弃，
     * 模型看不到截图，captureScreenshot 等于没做。
     * 与 ReactiveContext 的方向相反：那个往下复制，这个要往上回收。
     */
    @Test
    void promptMedia_fromParallelChildThreads_isCollectedOnCallingThread()
    {
        PromptMediaBuffer.drain(); // 清掉本测试线程的残留
        Media shot = media();
        ToolCallback shooter = new MediaStubTool("captureScreenshot", shot);
        ToolCallback other = new StubTool("readFile", 10, false);
        ToolCallingManager m = manager(List.of(shooter, other), 4, Duration.ofSeconds(5));

        m.executeToolCalls(buildPrompt(shooter, other), buildResponse("captureScreenshot", "readFile"));

        List<Media> got = PromptMediaBuffer.drain();
        assertEquals(1, got.size(), "并行批次的截图必须能在调用线程 drain 到");
        assertEquals(shot, got.get(0));
    }

    /** 多个工具都产图时，按 toolCall 原顺序合并，与 responses 归位口径一致。 */
    @Test
    void promptMedia_keepsToolCallOrder()
    {
        PromptMediaBuffer.drain();
        Media first = media();
        Media second = media();
        ToolCallback a = new MediaStubTool("shotA", first);
        ToolCallback b = new MediaStubTool("shotB", second);
        ToolCallingManager m = manager(List.of(a, b), 4, Duration.ofSeconds(5));

        m.executeToolCalls(buildPrompt(a, b), buildResponse("shotA", "shotB"));

        List<Media> got = PromptMediaBuffer.drain();
        assertEquals(List.of(first, second), got, "应按 toolCall 原顺序合并");
    }

    /** 工具产图后抛异常，图片仍要收走(收集写在 finally)。 */
    @Test
    void promptMedia_collectedEvenWhenToolThrows()
    {
        PromptMediaBuffer.drain();
        Media shot = media();
        ToolCallback boom = new MediaStubTool("shotThenFail", shot, true);
        ToolCallback other = new StubTool("readFile", 5, false);
        ToolCallingManager m = manager(List.of(boom, other), 4, Duration.ofSeconds(5));

        m.executeToolCalls(buildPrompt(boom, other), buildResponse("shotThenFail", "readFile"));

        assertEquals(1, PromptMediaBuffer.drain().size(), "工具异常不应吞掉已产出的图片");
    }

    /** 子线程 ThreadLocal 必须清空,否则线程池复用时会把图片串给下一个批次。 */
    @Test
    void promptMedia_doesNotLeakIntoNextBatchViaPooledThread()
    {
        PromptMediaBuffer.drain();
        ToolCallback shooter = new MediaStubTool("shot", media());
        ToolCallback other = new StubTool("readFile", 5, false);
        ToolCallingManager m = manager(List.of(shooter, other), 4, Duration.ofSeconds(5));
        m.executeToolCalls(buildPrompt(shooter, other), buildResponse("shot", "readFile"));
        assertEquals(1, PromptMediaBuffer.drain().size());

        // 第二批全是不产图的工具，复用同一线程池
        ToolCallback p = new StubTool("plainA", 5, false);
        ToolCallback q = new StubTool("plainB", 5, false);
        ToolCallingManager m2 = manager(List.of(p, q), 4, Duration.ofSeconds(5));
        m2.executeToolCalls(buildPrompt(p, q), buildResponse("plainA", "plainB"));

        assertTrue(PromptMediaBuffer.drain().isEmpty(), "上一批的图片不得串到下一批");
    }

    private static Media media()
    {
        return new Media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource("png".getBytes()));
    }

    /** 产出 prompt media 的存根工具：在 call() 里写 buffer，模拟 RecordingToolCallback 的收集时机。 */
    static class MediaStubTool implements ToolCallback
    {
        private final String name;
        private final Media media;
        private final boolean throwAfter;

        MediaStubTool(String name, Media media)
        {
            this(name, media, false);
        }

        MediaStubTool(String name, Media media, boolean throwAfter)
        {
            this.name = name;
            this.media = media;
            this.throwAfter = throwAfter;
        }

        @Override
        public ToolDefinition getToolDefinition()
        {
            return ToolDefinition.builder()
                    .name(name).description("media stub " + name).inputSchema("{}").build();
        }

        @Override
        public String call(String toolInput)
        {
            PromptMediaBuffer.addAll(List.of(media));
            if (throwAfter)
            {
                throw new IllegalStateException("boom after media");
            }
            return "ok";
        }

        @Override
        public String call(String toolInput, org.springframework.ai.chat.model.ToolContext ctx)
        {
            return call(toolInput);
        }
    }

    /** 可控耗时/异常的存根工具 */
    static class StubTool implements ToolCallback
    {
        private final String name;
        private final long sleepMs;
        private final boolean throwEx;

        StubTool(String name, long sleepMs, boolean throwEx)
        {
            this.name = name;
            this.sleepMs = sleepMs;
            this.throwEx = throwEx;
        }

        @Override
        public ToolDefinition getToolDefinition()
        {
            return ToolDefinition.builder()
                    .name(name)
                    .description("stub tool " + name)
                    .inputSchema("{}")
                    .build();
        }

        @Override
        public String call(String toolInput)
        {
            if (sleepMs > 0)
            {
                try
                {
                    Thread.sleep(sleepMs);
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted", e);
                }
            }
            if (throwEx)
            {
                throw new IllegalStateException("boom");
            }
            return "result:" + name;
        }
    }
}
