package com.ruoyi.system.ai.metering;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import com.ruoyi.system.ai.memory.TokenEstimator;
import com.ruoyi.system.domain.AiLlmCall;
import com.ruoyi.system.mapper.AiLlmCallMapper;

/**
 * LlmCallCollector：标准多轮 usage 分行、累积式上游合并、无 usage 估算兜底、
 * 工具循环累计 usage 差值记账。
 */
class LlmCallCollectorTest
{
    private AiLlmCallMapper mapper;
    private TokenEstimator estimator;
    private LlmCallCollector collector;

    @BeforeEach
    void setUp()
    {
        mapper = mock(AiLlmCallMapper.class);
        estimator = mock(TokenEstimator.class);
        when(estimator.estimate(any())).thenAnswer(inv -> {
            String s = inv.getArgument(0);
            return s == null ? 0 : s.length() / 2;
        });
        collector = new LlmCallCollector("s1", 10L, "s1:10", 100L, "qwen-max", 0,
                mapper, estimator);
    }

    @Test
    void standardMultiCall_recordsEachUsage()
    {
        // 模拟 Spring AI 工具循环累计 usage:第二次 prompt/completion 是累计值
        collector.onResponse(responseWithUsage(100, 20, 120, "tool_calls", "qwen-max"));
        collector.onResponse(responseWithUsage(200, 30, 230, "stop", "qwen-max"));
        LlmCallCollector.TurnUsage turn = collector.onComplete("hi", "hello");

        assertEquals(2, turn.callCount());
        // 差值:100+100 / 20+10 / 120+110,汇总等于末次累计
        assertEquals(200, turn.promptTokens());
        assertEquals(30, turn.completionTokens());
        assertEquals(230, turn.totalTokens());
        assertEquals(LlmCallCollector.SOURCE_REAL, turn.usageSource());

        ArgumentCaptor<AiLlmCall> cap = ArgumentCaptor.forClass(AiLlmCall.class);
        verify(mapper, times(2)).insertLlmCall(cap.capture());
        List<AiLlmCall> rows = cap.getAllValues();
        assertEquals(1, rows.get(0).getCallSeq());
        assertEquals(2, rows.get(1).getCallSeq());
        assertEquals(100, rows.get(0).getPromptTokens());
        assertEquals(100, rows.get(1).getPromptTokens());
        assertEquals(20, rows.get(0).getCompletionTokens());
        assertEquals(10, rows.get(1).getCompletionTokens());
        assertEquals("tool_calls", rows.get(0).getFinishReason());
        assertEquals("stop", rows.get(1).getFinishReason());
    }

    @Test
    void cumulativeUpstream_recordsOnceAtEnd()
    {
        // 同 prompt、completion 单调递增 → 只落最后一行
        collector.onResponse(responseWithUsage(100, 5, 105, null, "m"));
        collector.onResponse(responseWithUsage(100, 10, 110, null, "m"));
        collector.onResponse(responseWithUsage(100, 20, 120, "stop", "m"));
        LlmCallCollector.TurnUsage turn = collector.onComplete("p", "r");

        assertEquals(1, turn.callCount());
        assertEquals(100, turn.promptTokens());
        assertEquals(20, turn.completionTokens());
        assertEquals(120, turn.totalTokens());
        verify(mapper, times(1)).insertLlmCall(any());
    }

    @Test
    void noUsage_fallsBackToEstimate()
    {
        collector.onResponse(responseTextOnly("chunk"));
        LlmCallCollector.TurnUsage turn = collector.onComplete("prompt-text", "reply-text");

        assertEquals(1, turn.callCount());
        assertTrue(turn.totalTokens() > 0);
        assertEquals(LlmCallCollector.SOURCE_ESTIMATED, turn.usageSource());
        ArgumentCaptor<AiLlmCall> cap = ArgumentCaptor.forClass(AiLlmCall.class);
        verify(mapper, times(1)).insertLlmCall(cap.capture());
        assertEquals("1", cap.getValue().getUsageSource());
    }

    @Test
    void insertedCallIds_returnsGeneratedKeysInOrder()
    {
        // 模拟 useGeneratedKeys：insert 时给传入对象回填 callId
        java.util.concurrent.atomic.AtomicLong seq = new java.util.concurrent.atomic.AtomicLong(1000);
        when(mapper.insertLlmCall(any())).thenAnswer(inv -> {
            AiLlmCall c = inv.getArgument(0);
            c.setCallId(seq.getAndIncrement());
            return 1;
        });

        collector.onResponse(responseWithUsage(100, 20, 120, "tool_calls", "qwen-max"));
        collector.onResponse(responseWithUsage(200, 30, 230, "stop", "qwen-max"));
        collector.onComplete("hi", "hello");

        assertEquals(List.of(1000L, 1001L), collector.insertedCallIds());
    }

    @Test
    void toolLoopCumulative_recordsDeltas()
    {
        // 真实工具续轮数据形态:上游给累计值,落库记增量
        collector.onResponse(responseWithUsage(2585, 2216, 4801, "tool_calls", "m"));
        collector.onResponse(responseWithUsage(7148, 4177, 11325, "tool_calls", "m"));
        collector.onResponse(responseWithUsage(13689, 4313, 18002, "stop", "m"));
        LlmCallCollector.TurnUsage turn = collector.onComplete("p", "r");

        ArgumentCaptor<AiLlmCall> cap = ArgumentCaptor.forClass(AiLlmCall.class);
        verify(mapper, times(3)).insertLlmCall(cap.capture());
        List<AiLlmCall> rows = cap.getAllValues();
        assertEquals(2585, rows.get(0).getPromptTokens());
        assertEquals(4563, rows.get(1).getPromptTokens());
        assertEquals(6541, rows.get(2).getPromptTokens());
        assertEquals(2216, rows.get(0).getCompletionTokens());
        assertEquals(1961, rows.get(1).getCompletionTokens());
        assertEquals(136, rows.get(2).getCompletionTokens());
        // 行求和 == 末次累计
        assertEquals(13689, turn.promptTokens());
        assertEquals(4313, turn.completionTokens());
        assertEquals(18002, turn.totalTokens());
        assertEquals(3, turn.callCount());
    }

    @Test
    void nonCumulativeUpstream_keepsAbsoluteValues()
    {
        // 第二次更小 → 触发兜底,不做差值,不出现负数
        collector.onResponse(responseWithUsage(1000, 100, 1100, "tool_calls", "m"));
        collector.onResponse(responseWithUsage(900, 80, 980, "stop", "m"));
        LlmCallCollector.TurnUsage turn = collector.onComplete("p", "r");

        ArgumentCaptor<AiLlmCall> cap = ArgumentCaptor.forClass(AiLlmCall.class);
        verify(mapper, times(2)).insertLlmCall(cap.capture());
        List<AiLlmCall> rows = cap.getAllValues();
        assertEquals(1000, rows.get(0).getPromptTokens());
        assertEquals(900, rows.get(1).getPromptTokens());
        assertEquals(100, rows.get(0).getCompletionTokens());
        assertEquals(80, rows.get(1).getCompletionTokens());
        assertTrue(rows.get(0).getPromptTokens() >= 0);
        assertTrue(rows.get(1).getPromptTokens() >= 0);
        assertEquals(1900, turn.promptTokens());
        assertEquals(180, turn.completionTokens());
    }

    @Test
    void toolLoopCumulative_budgetReceivesPerCallPrompt()
    {
        // 回归:Spring AI 工具续轮给的是跨轮累计 usage(100→200→300),
        // 喂给 ToolBudget 的必须是差值还原后的当次上下文(100/100/100)。
        // 修复前预算收到累计值 300,越过硬顶 297(330*0.9)被误判「上下文接近上限」。
        com.ruoyi.system.tool.ToolBudget budget = new com.ruoyi.system.tool.ToolBudget(
                25, 35, 100000L, 30000, 330, 0.75, 0.90,
                com.ruoyi.system.tool.ToolBudget.ExitBehavior.END,
                java.util.Collections.emptyMap(), null, 0, 0, 0L);
        com.ruoyi.system.tool.ToolBudgetRegistry registry =
                mock(com.ruoyi.system.tool.ToolBudgetRegistry.class);
        when(registry.get("s1")).thenReturn(budget);
        LlmCallCollector c = new LlmCallCollector("s1", 10L, "s1:10", 100L, "m", 0,
                mapper, estimator, null, registry);

        c.onResponse(responseWithUsage(100, 20, 120, "tool_calls", "m"));
        c.onResponse(responseWithUsage(200, 30, 230, "tool_calls", "m"));
        c.onResponse(responseWithUsage(300, 40, 340, "stop", "m"));
        c.onComplete("p", "r");

        // 差值口径下该 agent 的窗口只占了 100/330,工具调用必须放行;
        // 修复前这里收到的是累计值 300 → HARD_EXCEEDED
        assertEquals(com.ruoyi.system.tool.ToolBudget.Verdict.ALLOW,
                budget.acquire("runShell", 10L));
    }

    @Test
    void cumulativeChainReset_rebaselinesAndKeepsDiffing()
    {
        // 线上真实形态:Spring AI 流式工具循环中途重开累计链,raw 从 250 掉回 130 后继续累计。
        // 重置不是"非累计上游":本轮记原值、重置基准,后续轮次必须继续差值。
        collector.onResponse(responseWithUsage(100, 10, 110, "tool_calls", "m"));
        collector.onResponse(responseWithUsage(250, 25, 275, "tool_calls", "m"));
        // 链重置:raw 跌到 130(=新链第1轮的完整上下文)
        collector.onResponse(responseWithUsage(130, 8, 138, "tool_calls", "m"));
        collector.onResponse(responseWithUsage(280, 20, 300, "stop", "m"));
        LlmCallCollector.TurnUsage turn = collector.onComplete("p", "r");

        ArgumentCaptor<AiLlmCall> cap = ArgumentCaptor.forClass(AiLlmCall.class);
        verify(mapper, times(4)).insertLlmCall(cap.capture());
        List<AiLlmCall> rows = cap.getAllValues();
        assertEquals(100, rows.get(0).getPromptTokens());
        assertEquals(150, rows.get(1).getPromptTokens());
        // 重置轮:记原值(130 就是当次完整上下文)
        assertEquals(130, rows.get(2).getPromptTokens());
        // 关键:重置后仍是累计式,第4轮继续差值(280-130),而非原样落 280
        assertEquals(150, rows.get(3).getPromptTokens());
        assertEquals(530, turn.promptTokens());
    }

    @Test
    void nonCumulativeUpstream_promptGrowsCompletionDrops_stillFlipsOff()
    {
        // 父级主路径形态:每轮 prompt 独立增长、completion 独立(跌)→ 真·非累计上游,
        // prompt 涨 completion 跌时关闭差值,后续轮次原样落库(与旧行为一致)
        collector.onResponse(responseWithUsage(100, 50, 150, "tool_calls", "m"));
        collector.onResponse(responseWithUsage(180, 30, 210, "tool_calls", "m"));
        collector.onResponse(responseWithUsage(260, 25, 285, "stop", "m"));
        collector.onComplete("p", "r");

        ArgumentCaptor<AiLlmCall> cap = ArgumentCaptor.forClass(AiLlmCall.class);
        verify(mapper, times(3)).insertLlmCall(cap.capture());
        List<AiLlmCall> rows = cap.getAllValues();
        assertEquals(100, rows.get(0).getPromptTokens());
        assertEquals(180, rows.get(1).getPromptTokens());
        assertEquals(260, rows.get(2).getPromptTokens());
        assertEquals(50, rows.get(0).getCompletionTokens());
        assertEquals(30, rows.get(1).getCompletionTokens());
    }

    @Test
    void corruptUsageStream_abortsAtFirstPhantomRound()
    {
        // 幻影轮形态:流损坏后"当次上下文"读数冲到窗口两倍以上(真实请求到不了,上游会先 400)。
        // 流内闸口必须在第一个幻影轮就中止,而不是等它空转 30 轮把 run 拖成活锁。
        com.ruoyi.system.tool.ToolBudget budget = new com.ruoyi.system.tool.ToolBudget(
                25, 35, 100000L, 30000, 330, 0.75, 0.90,
                com.ruoyi.system.tool.ToolBudget.ExitBehavior.END,
                java.util.Collections.emptyMap(), null, 0, 0, 0L);
        com.ruoyi.system.tool.ToolBudgetRegistry registry =
                mock(com.ruoyi.system.tool.ToolBudgetRegistry.class);
        when(registry.get("s1")).thenReturn(budget);
        LlmCallCollector c = new LlmCallCollector("s1", 10L, "s1:10", 100L, "m", 0,
                mapper, estimator, null, registry);

        c.onResponse(responseWithUsage(100, 10, 110, "tool_calls", "m"));
        c.onResponse(responseWithUsage(200, 20, 220, "tool_calls", "m"));
        // 当次读数 900-200=700 > 2*330 → 脏数据,立即中止
        org.junit.jupiter.api.Assertions.assertThrows(
                com.ruoyi.system.tool.ToolBudgetExceededException.class,
                () -> c.onResponse(responseWithUsage(900, 30, 930, "tool_calls", "m")));
    }

    @Test
    void pureGenerationRounds_abortsPastHardCapWithoutToolCalls()
    {
        // 纯生成轮不调工具、永远走不到 acquire():轮次硬顶必须在流内拦
        com.ruoyi.system.tool.ToolBudget budget = new com.ruoyi.system.tool.ToolBudget(
                2, 3, 100000L, 30000, 0, 0.75, 0.90,
                com.ruoyi.system.tool.ToolBudget.ExitBehavior.END,
                java.util.Collections.emptyMap(), null, 0, 0, 0L);
        com.ruoyi.system.tool.ToolBudgetRegistry registry =
                mock(com.ruoyi.system.tool.ToolBudgetRegistry.class);
        when(registry.get("s1")).thenReturn(budget);
        LlmCallCollector c = new LlmCallCollector("s1", 10L, "s1:10", 100L, "m", 0,
                mapper, estimator, null, registry);

        c.onResponse(responseWithUsage(100, 10, 110, "tool_calls", "m")); // round 1
        c.onResponse(responseWithUsage(200, 20, 220, "tool_calls", "m")); // round 2
        c.onResponse(responseWithUsage(300, 30, 330, "tool_calls", "m")); // round 3
        org.junit.jupiter.api.Assertions.assertThrows(
                com.ruoyi.system.tool.ToolBudgetExceededException.class,
                () -> c.onResponse(responseWithUsage(400, 40, 440, "tool_calls", "m"))); // round 4 > 硬顶 3
    }

    private static ChatResponse responseWithUsage(int prompt, int completion, int total,
                                                  String finishReason, String model)
    {
        DefaultUsage usage = new DefaultUsage(prompt, completion, total);
        ChatResponseMetadata meta = ChatResponseMetadata.builder()
                .model(model)
                .usage(usage)
                .build();
        ChatGenerationMetadata genMeta = finishReason != null
                ? ChatGenerationMetadata.builder().finishReason(finishReason).build()
                : ChatGenerationMetadata.NULL;
        Generation gen = new Generation(new AssistantMessage(""), genMeta);
        return new ChatResponse(List.of(gen), meta);
    }

    private static ChatResponse responseTextOnly(String text)
    {
        Generation gen = new Generation(new AssistantMessage(text));
        return new ChatResponse(List.of(gen));
    }
}
