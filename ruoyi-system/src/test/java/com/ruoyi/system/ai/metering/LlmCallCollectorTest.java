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

    @Test
    void probeProvesNonCumulative_stopsDiffingEvenWhenCompletionKeepsGrowing()
    {
        // 线上真实形态(会话 29dc90a4,deepseek-v4-flash-ga):自建工具循环下上游每轮给的是
        // 「本轮自己的」usage,prompt 因为历史变长而单调递增、completion 也因为思考变长和
        // 工具入参变大而单调递增 —— 旧的「prompt 涨而 completion 跌」反证永远不触发,
        // 于是每轮都被当成累计值做差,落库只剩本轮新增(8494/151/595/1735,真实 37354)。
        // 探针给的 hit+miss 就是当次请求的真实 prompt,拿它做硬判定。
        CacheUsageProbe probe = new CacheUsageProbe();
        probe.record("r1", 0, 8494);
        probe.record("r2", 8192, 453);
        probe.record("r3", 8192, 1048);
        probe.record("r4", 8192, 2783);
        LlmCallCollector c = new LlmCallCollector("s1", 10L, "s1:10", 100L, "m", 0,
                mapper, estimator, probe);

        c.onResponse(usageWithId(8494, 144, 8638, "tool_calls", "m", "r1"));
        c.onResponse(usageWithId(8645, 217, 8862, "tool_calls", "m", "r2"));
        c.onResponse(usageWithId(9240, 619, 9859, "tool_calls", "m", "r3"));
        c.onResponse(usageWithId(10975, 2803, 13778, "stop", "m", "r4"));
        LlmCallCollector.TurnUsage turn = c.onComplete("p", "r");

        ArgumentCaptor<AiLlmCall> cap = ArgumentCaptor.forClass(AiLlmCall.class);
        verify(mapper, times(4)).insertLlmCall(cap.capture());
        List<AiLlmCall> rows = cap.getAllValues();
        // 每行都是当次请求的完整 prompt,不是「本轮新增」
        assertEquals(8494, rows.get(0).getPromptTokens());
        assertEquals(8645, rows.get(1).getPromptTokens());
        assertEquals(9240, rows.get(2).getPromptTokens());
        assertEquals(10975, rows.get(3).getPromptTokens());
        assertEquals(2803, rows.get(3).getCompletionTokens());
        assertEquals(37354, turn.promptTokens());
        // 缓存列仍按探针原值落库(块量化绝对值,不做差值)
        assertEquals(8192, rows.get(1).getCacheHitTokens());
        assertEquals(453, rows.get(1).getCacheMissTokens());
        // hit+miss 恒等于该行的真实 prompt —— 命中率的正确分母
        for (AiLlmCall row : rows)
        {
            assertEquals(row.getPromptTokens().intValue(),
                    row.getCacheHitTokens() + row.getCacheMissTokens());
        }
    }

    @Test
    void probeAgreesWithCumulativeUpstream_keepsDiffing()
    {
        // 反向保护:上游确实累计时(raw 300 而当次请求只有 200),探针读数与 raw 不等,
        // 不能误判成非累计 —— 否则累计 raw 会被原样落库,把 32K 上下文算成 203K 幻影。
        CacheUsageProbe probe = new CacheUsageProbe();
        probe.record("c1", 0, 100);
        probe.record("c2", 64, 136);
        probe.record("c3", 128, 172);
        LlmCallCollector c = new LlmCallCollector("s1", 10L, "s1:10", 100L, "m", 0,
                mapper, estimator, probe);

        c.onResponse(usageWithId(100, 20, 120, "tool_calls", "m", "c1"));
        c.onResponse(usageWithId(300, 50, 350, "tool_calls", "m", "c2"));
        c.onResponse(usageWithId(600, 90, 690, "stop", "m", "c3"));
        c.onComplete("p", "r");

        ArgumentCaptor<AiLlmCall> cap = ArgumentCaptor.forClass(AiLlmCall.class);
        verify(mapper, times(3)).insertLlmCall(cap.capture());
        List<AiLlmCall> rows = cap.getAllValues();
        assertEquals(100, rows.get(0).getPromptTokens());
        assertEquals(200, rows.get(1).getPromptTokens());
        assertEquals(300, rows.get(2).getPromptTokens());
    }

    @Test
    void durationMs_measuresOwnRoundNotTheNextOne() throws Exception
    {
        // 旧实现拿 lastBoundaryMs(上一次 flush 的时刻)当起点,而 flush 由**下一轮**的
        // usage 触发 —— 第 N 行记成第 N+1 轮的耗时(含工具批次),末轮只剩 flush 的尾巴。
        // 线上 trace 表实测:一个 7.2s 的轮次记成 40565ms,末轮记成 35ms。
        collector.onLlmCallStarted();
        Thread.sleep(80);                 // 第 1 轮流
        collector.onResponse(responseWithUsage(100, 20, 120, "tool_calls", "m"));
        Thread.sleep(300);                // 工具批次,不该算进第 1 轮
        collector.onLlmCallStarted();
        Thread.sleep(80);                 // 第 2 轮流
        collector.onResponse(responseWithUsage(200, 30, 230, "stop", "m"));
        collector.onComplete("p", "r");

        ArgumentCaptor<AiLlmCall> cap = ArgumentCaptor.forClass(AiLlmCall.class);
        verify(mapper, times(2)).insertLlmCall(cap.capture());
        List<AiLlmCall> rows = cap.getAllValues();
        long d1 = rows.get(0).getDurationMs();
        long d2 = rows.get(1).getDurationMs();
        // 修复前 d1 ≈ 80+300+80 = 460(串到了下一轮),d2 ≈ 0(只剩 flush 尾巴)
        assertTrue(d1 >= 40 && d1 < 250, "第 1 轮耗时应只覆盖自己那段流,实际 " + d1);
        assertTrue(d2 >= 40 && d2 < 250, "末轮耗时不该只剩 flush 尾巴,实际 " + d2);
    }

    private static ChatResponse usageWithId(int prompt, int completion, int total,
                                            String finishReason, String model, String id)
    {
        DefaultUsage usage = new DefaultUsage(prompt, completion, total);
        ChatResponseMetadata meta = ChatResponseMetadata.builder()
                .id(id)
                .model(model)
                .usage(usage)
                .build();
        ChatGenerationMetadata genMeta = finishReason != null
                ? ChatGenerationMetadata.builder().finishReason(finishReason).build()
                : ChatGenerationMetadata.NULL;
        Generation gen = new Generation(new AssistantMessage(""), genMeta);
        return new ChatResponse(List.of(gen), meta);
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
