package com.ruoyi.system.ai.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;

import com.ruoyi.system.ai.agent.ModelInputModalities;
import com.ruoyi.system.ai.event.ChatEventSink;
import com.ruoyi.system.ai.memory.ChatMessageMapperTestSupport;
import com.ruoyi.system.ai.metering.LlmCallCollector;
import com.ruoyi.system.tool.PromptMediaBuffer;
import com.ruoyi.system.tool.ToolBudgetExceededException;

import reactor.core.publisher.Flux;
import reactor.util.context.Context;

/**
 * Run 链路超时的两个缺口(心跳机制只覆盖进程死亡,覆盖不了线程活着但阻塞):
 * <ul>
 *   <li>主 LLM 流裸订阅无空闲超时 —— 上游半开连接时 Run 永久 RUNNING</li>
 *   <li>串行工具路径无批次超时 —— 官方 ToolCallingManager 同步执行,单工具挂死拖住整轮</li>
 * </ul>
 * <p>注意超时字段单位是<b>秒</b>,用例里取 1 秒粒度。</p>
 */
class AgentToolLoopTimeoutTest
{
    private ExecutorService executor;

    @BeforeEach
    void setUp()
    {
        executor = Executors.newFixedThreadPool(2);
        PromptMediaBuffer.drain();
    }

    @AfterEach
    void tearDown()
    {
        executor.shutdownNow();
    }

    // ---------- LLM 流空闲超时 ----------

    /** 上游永远不出 chunk:空闲超时必须在秒级切断,并给出用户可读的中文错误。 */
    @Test
    void idleStream_timesOutWithReadableError() throws Exception
    {
        AgentToolLoop loop = new AgentToolLoop();
        ChatMessageMapperTestSupport.setField(loop, "llmIdleTimeoutSeconds", 1L);
        ChatModel model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.never());

        List<Throwable> errors = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        loop.run(spec(model, new StringBuilder(), List.of())).subscribe(r -> { }, e -> {
            errors.add(e);
            latch.countDown();
        });

        assertTrue(latch.await(3, TimeUnit.SECONDS), "空闲超时应在 3s 内触发");
        assertTrue(errors.get(0) instanceof ToolBudgetExceededException,
                "复用闸口异常类型,子 agent 路径才能走既有降级: " + errors.get(0));
        assertTrue(errors.get(0).getMessage().contains("僵死"),
                "错误消息要让用户看懂发生了什么: " + errors.get(0).getMessage());
    }

    /** 对照组:窗口内正常输出/完成的流不受影响。 */
    @Test
    void activeStream_completesUntouched() throws Exception
    {
        AgentToolLoop loop = new AgentToolLoop();
        ChatMessageMapperTestSupport.setField(loop, "llmIdleTimeoutSeconds", 60L);
        ChatModel model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(textResponse("答案")));

        StringBuilder reply = new StringBuilder();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        loop.run(spec(model, reply, List.of())).subscribe(r -> { }, e -> {
            error.set(e);
            latch.countDown();
        }, latch::countDown);

        assertTrue(latch.await(2, TimeUnit.SECONDS), "正常流应立即完成");
        assertNull(error.get());
        assertEquals("答案", reply.toString());
    }

    /**
     * 空闲计时只罩流式段:工具执行期间流段已 complete,不应被误杀。
     * <p>工具合法耗时 3s > 空闲窗口 1s;若把超时错包在整条拼接流(concatWith 之后)上,
     * 工具静默期就会触发误杀,本用例必挂 —— 锁住「分段」这一设计决策。</p>
     */
    @Test
    void toolExecutionGap_notCountedAgainstIdleTimeout() throws Exception
    {
        AgentToolLoop loop = new AgentToolLoop();
        ChatMessageMapperTestSupport.setField(loop, "llmIdleTimeoutSeconds", 1L);
        ChatMessageMapperTestSupport.setField(loop, "parallelTools", false);
        ChatMessageMapperTestSupport.setField(loop, "toolBatchTimeoutSeconds", 30L);
        ChatMessageMapperTestSupport.setField(loop, "parallelToolTaskExecutor", (Executor) executor);
        ChatMessageMapperTestSupport.setField(loop, "messageRecorder",
                mock(com.ruoyi.system.ai.memory.ChatMessageRecorder.class));

        // 第一轮流返回 tool_calls,第二轮返回最终文本
        AtomicInteger round = new AtomicInteger();
        ChatModel model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenAnswer(inv -> round.incrementAndGet() == 1
                ? Flux.just(toolCallResponse("slowTool"))
                : Flux.just(textResponse("完成")));

        StringBuilder reply = new StringBuilder();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        loop.run(spec(model, reply, List.of(new StubTool("slowTool", 3_000))))
                .subscribe(r -> { }, e -> {
                    error.set(e);
                    latch.countDown();
                }, latch::countDown);

        assertTrue(latch.await(6, TimeUnit.SECONDS), "慢工具路径应在工具结束后正常完成");
        assertNull(error.get(), "工具执行静默不应触发流空闲超时: " + error.get());
        // 第一轮伴随 tool_calls 的正文("need tools")合法流出,关键是第二轮文本完整到达
        assertTrue(reply.toString().endsWith("完成"),
                "应走到第二轮最终文本, reply=" + reply);
    }

    // ---------- 串行工具批次超时 ----------

    /** 工具批次挂死:超时后合成错误 ToolResponse 返回,不阻塞调用线程,循环可继续。 */
    @Test
    void serialToolBatch_timeoutReturnsSyntheticErrorFast() throws Exception
    {
        AgentToolLoop loop = boundedLoop(1L);
        ToolCallingManager stuck = stubManager(result -> {
            Thread.sleep(10_000);
            return result;
        });

        long start = System.nanoTime();
        ToolExecutionResult out = loop.executeSerialToolCallsBounded(
                stuck, prompt(), toolCallResponse("slow"), Context.empty()).result();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue(elapsedMs < 3_500, "超时应秒级返回而不是等工具, 实际 " + elapsedMs + "ms");
        ToolResponseMessage trm = lastToolResponse(out);
        assertEquals(1, trm.getResponses().size());
        assertTrue(trm.getResponses().get(0).responseData().contains("timed out"),
                "应回填超时错误给模型: " + trm.getResponses().get(0).responseData());
        assertFalse(out.returnDirect(), "合成结果不能短路循环,要让模型看到失败自行收尾");
    }

    /** 正常批次:透传管理器结果,并把执行线程产出的媒体(截图)收回调用线程。 */
    @Test
    void serialToolBatch_passesThroughResultAndCollectsMedia() throws Exception
    {
        AgentToolLoop loop = boundedLoop(30L);
        Media shot = media();
        ToolExecutionResult normal = ToolExecutionResult.builder()
                .conversationHistory(List.of(new UserMessage("hi")))
                .returnDirect(false)
                .build();
        // 执行发生在 parallelToolTaskExecutor 线程:媒体写在那边,必须显式带回来
        ToolCallingManager manager = stubManager(v -> {
            PromptMediaBuffer.addAll(List.of(shot));
            return normal;
        });

        AgentToolLoop.SerialToolBatchOutcome outcome = loop.executeSerialToolCallsBounded(
                manager, prompt(), toolCallResponse("capture"), Context.empty());

        assertSame(normal, outcome.result(), "正常批次应原样透传管理器结果");
        assertEquals(1, outcome.medias().size(), "执行线程的截图必须回到调用线程");
        assertSame(shot, outcome.medias().get(0));
    }

    // ---------- helpers ----------

    /** parallelTools=false + 批次超时开启 + 测试线程池,与生产串行路径同构。 */
    private AgentToolLoop boundedLoop(long timeoutSeconds) throws Exception
    {
        AgentToolLoop loop = new AgentToolLoop();
        ChatMessageMapperTestSupport.setField(loop, "parallelTools", false);
        ChatMessageMapperTestSupport.setField(loop, "toolBatchTimeoutSeconds", timeoutSeconds);
        ChatMessageMapperTestSupport.setField(loop, "parallelToolTaskExecutor", (Executor) executor);
        return loop;
    }

    /**
     * 工具执行期间 cancel/总时长兜底把 shouldContinue 打成 false 后,
     * 批次返回不得再开下一轮 LLM(否则会跟新 Run 抢会话锁)。
     */
    @Test
    void cancelledDuringToolBatch_skipsNextLlmRound() throws Exception
    {
        AgentToolLoop loop = new AgentToolLoop();
        ChatMessageMapperTestSupport.setField(loop, "llmIdleTimeoutSeconds", 30L);
        ChatMessageMapperTestSupport.setField(loop, "parallelTools", false);
        ChatMessageMapperTestSupport.setField(loop, "toolBatchTimeoutSeconds", 30L);
        ChatMessageMapperTestSupport.setField(loop, "parallelToolTaskExecutor", (Executor) executor);
        ChatMessageMapperTestSupport.setField(loop, "messageRecorder",
                mock(com.ruoyi.system.ai.memory.ChatMessageRecorder.class));

        AtomicInteger rounds = new AtomicInteger();
        ChatModel model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenAnswer(inv -> {
            int n = rounds.incrementAndGet();
            return n == 1 ? Flux.just(toolCallResponse("flag")) : Flux.just(textResponse("不应到达"));
        });

        AtomicBoolean cont = new AtomicBoolean(true);
        ChatTurnCallbacks callbacks = new ChatTurnCallbacks()
        {
            @Override
            public boolean shouldContinue()
            {
                return cont.get();
            }

            @Override
            public void onSucceeded(String reply, LlmCallCollector.TurnUsage usage,
                                    java.util.Map<String, Object> contextUsage,
                                    Long requestMessageId, Long responseMessageId) { }

            @Override
            public void onFailed(Throwable error) { }
        };

        StringBuilder reply = new StringBuilder();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        loop.run(spec(model, reply, List.of(new FlagTool(cont)), callbacks)).subscribe(r -> { }, e -> {
            error.set(e);
            latch.countDown();
        }, latch::countDown);

        assertTrue(latch.await(5, TimeUnit.SECONDS), "取消后循环应立刻结束");
        assertNull(error.get(), "取消是空完成不是异常: " + error.get());
        assertEquals(1, rounds.get(), "不得再开下一轮 LLM, rounds=" + rounds.get());
        assertFalse(reply.toString().contains("不应到达"), "reply=" + reply);
    }

    private AgentToolLoopSpec spec(ChatModel model, StringBuilder reply, List<ToolCallback> tools)
    {
        return spec(model, reply, tools, ChatTurnCallbacks.noop());
    }

    private AgentToolLoopSpec spec(ChatModel model, StringBuilder reply, List<ToolCallback> tools,
                                   ChatTurnCallbacks callbacks)
    {
        return new AgentToolLoopSpec(
                model, ChatOptions.builder().build(),
                List.of(new UserMessage("hi")), tools,
                0, mock(LlmCallCollector.class), ChatEventSink.noop(), callbacks,
                null, "s1", null, 1L, null, null,
                reply, new StringBuilder(), () -> { }, true,
                ModelInputModalities.parse("image"));
    }

    private static ChatResponse textResponse(String text)
    {
        return ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage(text))))
                .build();
    }

    private static ChatResponse toolCallResponse(String toolName)
    {
        AssistantMessage assistant = AssistantMessage.builder()
                .content("need tools")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call_" + toolName, "function", toolName, "{}")))
                .build();
        return ChatResponse.builder()
                .generations(List.of(new Generation(assistant)))
                .build();
    }

    private static Prompt prompt()
    {
        return new Prompt(List.of(new UserMessage("hi")));
    }

    private static ToolResponseMessage lastToolResponse(ToolExecutionResult result)
    {
        List<Message> history = result.conversationHistory();
        assertTrue(history != null && !history.isEmpty());
        Message last = history.get(history.size() - 1);
        assertTrue(last instanceof ToolResponseMessage, "最后一条消息应为 ToolResponseMessage");
        return (ToolResponseMessage) last;
    }

    private interface ManagerBody
    {
        ToolExecutionResult run(ToolExecutionResult noop) throws Exception;
    }

    private static ToolCallingManager stubManager(ManagerBody body)
    {
        ToolCallingManager manager = mock(ToolCallingManager.class);
        when(manager.executeToolCalls(any(Prompt.class), any(ChatResponse.class)))
                .thenAnswer(inv -> body.run(null));
        return manager;
    }

    private static Media media()
    {
        return new Media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(new byte[] { 1, 2, 3 }));
    }

    /** 执行时把 shouldContinue 打成 false,模拟 cancel / 总时长兜底发生在工具批次中。 */
    private static final class FlagTool implements ToolCallback
    {
        private final AtomicBoolean cont;

        private FlagTool(AtomicBoolean cont)
        {
            this.cont = cont;
        }

        @Override
        public ToolDefinition getToolDefinition()
        {
            return ToolDefinition.builder().name("flag").description("flag").inputSchema("{}").build();
        }

        @Override
        public String call(String toolInput)
        {
            cont.set(false);
            return "ok";
        }

        @Override
        public String call(String toolInput, org.springframework.ai.chat.model.ToolContext ctx)
        {
            return call(toolInput);
        }
    }

    /** 可控耗时的存根工具,走完整循环时由官方管理器解析执行。 */
    private static final class StubTool implements ToolCallback
    {
        private final String name;
        private final long sleepMs;

        private StubTool(String name, long sleepMs)
        {
            this.name = name;
            this.sleepMs = sleepMs;
        }

        @Override
        public ToolDefinition getToolDefinition()
        {
            return ToolDefinition.builder()
                    .name(name).description("stub " + name).inputSchema("{}").build();
        }

        @Override
        public String call(String toolInput)
        {
            try
            {
                Thread.sleep(sleepMs);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
            return "ok";
        }

        @Override
        public String call(String toolInput, org.springframework.ai.chat.model.ToolContext ctx)
        {
            return call(toolInput);
        }
    }
}
