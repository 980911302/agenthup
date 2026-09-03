package com.ruoyi.system.tool;

import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具调用预算:往返轮次上限、同轮多次 acquire、累计体积、注册表生命周期。
 */
class ToolBudgetTest
{
    private static final int MAX_ROUNDS = 25;
    private static final int HARD_MAX_ROUNDS = 35;
    private static final long MAX_TOTAL_CHARS = 200_000L;
    private static final int MAX_RESULT_CHARS = 30_000;

    private ToolBudget newBudget()
    {
        return new ToolBudget(MAX_ROUNDS, HARD_MAX_ROUNDS, MAX_TOTAL_CHARS, MAX_RESULT_CHARS);
    }

    @Test
    void sameRound_multipleAcquire_roundsOnlyOnce()
    {
        ToolBudget budget = newBudget();
        budget.beginRound();
        for (int i = 0; i < 5; i++)
        {
            assertEquals(ToolBudget.Verdict.ALLOW, budget.acquire(), "parallel tool " + i);
        }
        // 并行 5 次工具只算 1 轮往返
        assertEquals(1, budget.rounds());
    }

    @Test
    void beginRound_withinSoftLimit_allows()
    {
        ToolBudget budget = newBudget();
        for (int i = 1; i <= MAX_ROUNDS; i++)
        {
            budget.beginRound();
            assertEquals(ToolBudget.Verdict.ALLOW, budget.acquire(), "round " + i);
        }
        assertEquals(MAX_ROUNDS, budget.rounds());
    }

    @Test
    void beginRound_pastSoftLimit_returnsSoftExceeded()
    {
        ToolBudget budget = newBudget();
        for (int i = 0; i < MAX_ROUNDS; i++)
        {
            budget.beginRound();
            budget.acquire();
        }
        budget.beginRound();
        assertEquals(ToolBudget.Verdict.SOFT_EXCEEDED, budget.acquire());
        // 软上限后 beginRound 仍自增,否则永远撞不上硬上限
        assertEquals(MAX_ROUNDS + 1, budget.rounds());
    }

    @Test
    void beginRound_continuesPastSoft_untilHardExceeded()
    {
        ToolBudget budget = newBudget();
        // 1..25 ALLOW, 26..35 SOFT, 36 HARD
        for (int i = 1; i <= HARD_MAX_ROUNDS; i++)
        {
            budget.beginRound();
            ToolBudget.Verdict v = budget.acquire();
            if (i <= MAX_ROUNDS)
            {
                assertEquals(ToolBudget.Verdict.ALLOW, v, "round " + i);
            }
            else
            {
                assertEquals(ToolBudget.Verdict.SOFT_EXCEEDED, v, "round " + i);
            }
        }
        budget.beginRound();
        assertEquals(ToolBudget.Verdict.HARD_EXCEEDED, budget.acquire());
        assertEquals(HARD_MAX_ROUNDS + 1, budget.rounds());
    }

    @Test
    void acquire_totalCharsSoftLimit_returnsSoftExceeded()
    {
        ToolBudget budget = newBudget();
        budget.beginRound();
        budget.recordResult((int) MAX_TOTAL_CHARS + 1);
        // 累计已超,即便轮次仍在软限内也判软上限;rounds 不因 acquire 变化
        assertEquals(ToolBudget.Verdict.SOFT_EXCEEDED, budget.acquire());
        assertEquals(1, budget.rounds());
        assertEquals(MAX_TOTAL_CHARS + 1, budget.totalChars());
    }

    @Test
    void recordResult_accumulatesTruncatedLength()
    {
        ToolBudget budget = newBudget();
        budget.recordResult(100);
        budget.recordResult(50);
        assertEquals(150L, budget.totalChars());
    }

    @Test
    void describeExhausted_containsRoundsAndChars()
    {
        ToolBudget budget = newBudget();
        budget.beginRound();
        budget.acquire();
        budget.recordResult(1234);
        String msg = budget.describeExhausted();
        assertTrue(msg.contains("已进行 1 轮模型往返"));
        assertTrue(msg.contains("累计返回 1234 字符"));
        assertTrue(msg.startsWith("[系统提示] 工具调用预算已耗尽"));
        assertTrue(msg.contains("请立即停止调用任何工具"));
    }

    @Test
    void maxResultChars_exposesConfiguredLimit()
    {
        assertEquals(MAX_RESULT_CHARS, newBudget().maxResultChars());
    }

    /**
     * token 判定必须按 agent 隔离。
     *
     * <p>ToolBudget 是 session 级共享的,而子智能体是独立无状态会话、上下文不占父的窗口。
     * 共用一个「最后写入者赢」的单值时,子 agent 把 prompt 顶到 113K 之后,
     * 只用了 5K 的父也会被判成「上下文已占用 95%」而被拦下 —— 线上实测过:
     * 环图显示 4.3%,同一轮却弹出「已占用约 95%,请停止继续读取」。
     */
    @Test
    void promptTokens_isolatedPerAgent_subAgentDoesNotBlockParent()
    {
        // 预算 100_000, soft 0.75 → 75_000, hard 0.90 → 90_000
        ToolBudget budget = new ToolBudget(MAX_ROUNDS, HARD_MAX_ROUNDS, MAX_TOTAL_CHARS,
                MAX_RESULT_CHARS, 100_000, 0.75, 0.90);
        budget.beginRound();

        Long parent = 1L;
        Long child = 6L;

        // 子 agent 的上下文顶到 95%
        budget.recordPromptTokens(child, 95_000);
        // 父只用了 5K
        budget.recordPromptTokens(parent, 5_000);

        assertEquals(ToolBudget.Verdict.ALLOW, budget.acquire("readFile", parent),
                "父自己才用 5K,不该被子 agent 的上下文拖下水");
        assertEquals(ToolBudget.Verdict.HARD_EXCEEDED, budget.acquire("readFile", child),
                "子 agent 自己确实超了,仍要拦");

        // 顺序反过来同样成立:子后写入也不能污染父
        budget.recordPromptTokens(child, 99_000);
        assertEquals(ToolBudget.Verdict.ALLOW, budget.acquire("readFile", parent));
    }

    /** describeExhausted 报的用量要跟着触发判定的那个 agent,不能串到别人头上。 */
    @Test
    void describeExhausted_reportsTriggeringAgentUsage()
    {
        ToolBudget budget = new ToolBudget(MAX_ROUNDS, HARD_MAX_ROUNDS, MAX_TOTAL_CHARS,
                MAX_RESULT_CHARS, 100_000, 0.75, 0.90);
        budget.beginRound();
        budget.recordPromptTokens(1L, 5_000);
        budget.recordPromptTokens(6L, 80_000);

        assertEquals(ToolBudget.Verdict.SOFT_EXCEEDED, budget.acquire("readFile", 6L));
        assertTrue(budget.describeExhausted().contains("80000/100000"),
                budget.describeExhausted());
    }

    /**
     * 子智能体可以用与父完全不同的模型,窗口上限必须按 agent 各自判定:
     * 子 agent 注册自己模型的小窗口后,用量没超 session 级上限但超了自己模型的上限,
     * 仍要被拦;未注册的父 agent 继续用 session 级上限,不受影响。
     */
    @Test
    void registerInputBudget_perAgentWindow_childJudgedByOwnModel()
    {
        // session 级(父模型)预算 100_000,hard 90_000
        ToolBudget budget = new ToolBudget(MAX_ROUNDS, HARD_MAX_ROUNDS, MAX_TOTAL_CHARS,
                MAX_RESULT_CHARS, 100_000, 0.75, 0.90);
        // 子 agent 的模型窗口只有 10_000,hard 9_000
        budget.registerInputBudget(6L, 10_000);
        budget.beginRound();

        budget.recordPromptTokens(6L, 9_500);
        budget.recordPromptTokens(1L, 9_500);

        assertEquals(ToolBudget.Verdict.HARD_EXCEEDED, budget.acquire("runShell", 6L),
                "9500 超了子 agent 自己模型的硬顶 9000,必须拦");
        assertEquals(ToolBudget.Verdict.ALLOW, budget.acquire("readFile", 1L),
                "同样的用量对父(窗口 100K)只是零头,不能误伤");
    }

    /** describeExhausted 的上限/用量都要取触发 agent 自己的槽位。 */
    @Test
    void describeExhausted_usesTriggeringAgentBudget()
    {
        ToolBudget budget = new ToolBudget(MAX_ROUNDS, HARD_MAX_ROUNDS, MAX_TOTAL_CHARS,
                MAX_RESULT_CHARS, 100_000, 0.75, 0.90);
        budget.registerInputBudget(6L, 10_000);
        budget.beginRound();
        budget.recordPromptTokens(6L, 9_500);

        assertEquals(ToolBudget.Verdict.HARD_EXCEEDED, budget.acquire("runShell", 6L));
        assertTrue(budget.describeExhausted().contains("9500/10000"),
                budget.describeExhausted());
    }

    /** 注册 budget<=0 等价于注销,回退 session 级上限。 */
    @Test
    void registerInputBudget_nonPositive_fallsBackToSessionBudget()
    {
        ToolBudget budget = new ToolBudget(MAX_ROUNDS, HARD_MAX_ROUNDS, MAX_TOTAL_CHARS,
                MAX_RESULT_CHARS, 100_000, 0.75, 0.90);
        budget.registerInputBudget(6L, 10_000);
        budget.registerInputBudget(6L, 0);
        budget.beginRound();
        budget.recordPromptTokens(6L, 9_500);

        assertEquals(ToolBudget.Verdict.ALLOW, budget.acquire("runShell", 6L),
                "注销后回退 session 级 100K,9500 不该被拦");
        assertEquals(100_000, budget.inputBudgetFor(6L));
        budget.registerInputBudget(6L, 10_000);
        assertEquals(10_000, budget.inputBudgetFor(6L));
    }

    @Test
    void recordPromptTokens_pastSoftRatio_returnsSoftExceeded()
    {
        // 预算 100_000, soft 0.75 → 75_000
        ToolBudget budget = new ToolBudget(MAX_ROUNDS, HARD_MAX_ROUNDS, MAX_TOTAL_CHARS,
                MAX_RESULT_CHARS, 100_000, 0.75, 0.90);
        budget.beginRound();
        budget.recordPromptTokens(75_001);
        assertEquals(ToolBudget.Verdict.SOFT_EXCEEDED, budget.acquire());
        String msg = budget.describeExhausted();
        assertTrue(msg.contains("当前上下文已占用约"), msg);
        assertTrue(msg.contains("75001/100000"), msg);
        assertTrue(msg.contains("用户可以在新会话中继续"), msg);
    }

    @Test
    void recordPromptTokens_pastHardRatio_returnsHardExceeded()
    {
        // 预算 100_000, hard 0.90 → 90_000
        ToolBudget budget = new ToolBudget(MAX_ROUNDS, HARD_MAX_ROUNDS, MAX_TOTAL_CHARS,
                MAX_RESULT_CHARS, 100_000, 0.75, 0.90);
        budget.beginRound();
        budget.recordPromptTokens(90_001);
        assertEquals(ToolBudget.Verdict.HARD_EXCEEDED, budget.acquire());
    }

    @Test
    void recordPromptTokens_withinSoftRatio_allows()
    {
        ToolBudget budget = new ToolBudget(MAX_ROUNDS, HARD_MAX_ROUNDS, MAX_TOTAL_CHARS,
                MAX_RESULT_CHARS, 100_000, 0.75, 0.90);
        budget.beginRound();
        budget.recordPromptTokens(75_000);
        assertEquals(ToolBudget.Verdict.ALLOW, budget.acquire());
    }

    @Test
    void recordPromptTokens_zeroBudget_skipsTokenCheck()
    {
        // inputBudget=0 时不做 token 比例约束,只走轮次/字符
        ToolBudget budget = new ToolBudget(MAX_ROUNDS, HARD_MAX_ROUNDS, MAX_TOTAL_CHARS,
                MAX_RESULT_CHARS, 0, 0.75, 0.90);
        budget.beginRound();
        budget.recordPromptTokens(999_999);
        assertEquals(ToolBudget.Verdict.ALLOW, budget.acquire());
    }

    @Test
    void promptHard_takesPriorityOverRoundSoft()
    {
        // 轮次仍在软限内,但 prompt 已过硬限 → HARD
        ToolBudget budget = new ToolBudget(MAX_ROUNDS, HARD_MAX_ROUNDS, MAX_TOTAL_CHARS,
                MAX_RESULT_CHARS, 10_000, 0.75, 0.90);
        budget.beginRound();
        budget.recordPromptTokens(9_001);
        assertEquals(ToolBudget.Verdict.HARD_EXCEEDED, budget.acquire());
    }

    @Test
    void perToolLimit_blocksAfterNCalls()
    {
        ToolBudget budget = new ToolBudget(MAX_ROUNDS, HARD_MAX_ROUNDS, MAX_TOTAL_CHARS,
                MAX_RESULT_CHARS, 0, 0.75, 0.90, ToolBudget.ExitBehavior.END,
                java.util.Map.of("writeFile", 2), new ToolBudget.SessionTally(), 0, 0, 0L);
        budget.beginRound();
        assertEquals(ToolBudget.Verdict.ALLOW, budget.acquire("writeFile"));
        assertEquals(ToolBudget.Verdict.ALLOW, budget.acquire("writeFile"));
        assertEquals(ToolBudget.Verdict.SOFT_EXCEEDED, budget.acquire("writeFile"));
        assertTrue(budget.describeExhausted().contains("writeFile"));
        // 其他工具不受影响
        assertEquals(ToolBudget.Verdict.ALLOW, budget.acquire("readFile"));
    }

    @Test
    void exitBehaviorError_turnsSoftIntoHard()
    {
        ToolBudget budget = new ToolBudget(1, 10, MAX_TOTAL_CHARS, MAX_RESULT_CHARS,
                0, 0.75, 0.90, ToolBudget.ExitBehavior.ERROR,
                java.util.Map.of(), new ToolBudget.SessionTally(), 0, 0, 0L);
        budget.beginRound(); // rounds=1, soft maxRounds=1 → next acquire soft
        budget.acquire("x"); // allow at rounds=1
        budget.beginRound(); // rounds=2 > maxRounds=1
        assertEquals(ToolBudget.Verdict.HARD_EXCEEDED, budget.acquire("x"));
    }

    @Test
    void sessionToolCallLimit_blocksAcrossSharedTally()
    {
        ToolBudget.SessionTally tally = new ToolBudget.SessionTally();
        ToolBudget first = new ToolBudget(MAX_ROUNDS, HARD_MAX_ROUNDS, MAX_TOTAL_CHARS,
                MAX_RESULT_CHARS, 0, 0.75, 0.90, ToolBudget.ExitBehavior.END,
                java.util.Map.of(), tally, 0, 2, 0L);
        first.beginRound();
        assertEquals(ToolBudget.Verdict.ALLOW, first.acquire("a"));
        assertEquals(ToolBudget.Verdict.ALLOW, first.acquire("b"));
        // 模拟下一轮 run 共享同一 tally
        ToolBudget second = new ToolBudget(MAX_ROUNDS, HARD_MAX_ROUNDS, MAX_TOTAL_CHARS,
                MAX_RESULT_CHARS, 0, 0.75, 0.90, ToolBudget.ExitBehavior.END,
                java.util.Map.of(), tally, 0, 2, 0L);
        second.beginRound();
        assertEquals(ToolBudget.Verdict.HARD_EXCEEDED, second.acquire("c"));
        assertTrue(second.describeExhausted().contains("本会话"));
    }

    @Test
    void registry_get_nullWhenNotOpen()
    {
        ToolBudgetRegistry registry = new ToolBudgetRegistry();
        assertNull(registry.get("session-never-opened"));
        assertNull(registry.get(null));
    }

    @Test
    void registry_openGetClose_lifecycle()
    {
        ToolBudgetRegistry registry = new ToolBudgetRegistry();
        ToolBudget opened = registry.open("s1");
        assertNotNull(opened);
        assertNotNull(registry.get("s1"));
        assertEquals(opened, registry.get("s1"));

        registry.close("s1");
        assertNull(registry.get("s1"));
        registry.close("s1");
        assertNull(registry.get("s1"));
    }

    @Test
    void registry_open_replacesSameSessionId()
    {
        ToolBudgetRegistry registry = new ToolBudgetRegistry();
        ToolBudget first = registry.open("s1");
        first.beginRound();
        first.acquire();
        ToolBudget second = registry.open("s1");
        assertNotNull(second);
        assertEquals(0, second.rounds());
        assertEquals(second, registry.get("s1"));
    }

    @Test
    void beginRound_perAgent_childRoundsDoNotExhaustParent()
    {
        // 修复⑤:rounds 改 per-agent。原来父子共用一个 rounds 计数器,
        // 子 30 轮 + 父 20 轮 = 50 就顶到 max-rounds,父被子的往返拖垮。
        ToolBudget budget = newBudget();
        Long parent = 1L, child = 6L;
        // 子 agent 跑满自己的软上限(25 轮),再 beginRound+acquire 应 SOFT_EXCEEDED
        for (int i = 0; i < MAX_ROUNDS; i++)
        {
            budget.beginRound(child);
        }
        assertEquals(MAX_ROUNDS, budget.rounds(child));
        budget.beginRound(child);
        assertEquals(ToolBudget.Verdict.SOFT_EXCEEDED, budget.acquire("anyTool", child));

        // 父 agent 一次没跑:子的往返不占父的配额,父仍 ALLOW
        assertEquals(0, budget.rounds(parent));
        assertEquals(ToolBudget.Verdict.ALLOW, budget.acquire("anyTool", parent));
        // 父也能跑满自己的 25 轮(若仍共用计数器,这里会立即 SOFT_EXCEEDED)
        for (int i = 0; i < MAX_ROUNDS; i++)
        {
            budget.beginRound(parent);
            assertEquals(ToolBudget.Verdict.ALLOW, budget.acquire("anyTool", parent),
                    "parent round " + i);
        }
        // 会话级累计仍是父子之和(总闸语义不变)
        assertEquals(MAX_ROUNDS * 2 + 1, budget.session().rounds());
    }

    @Test
    void hardRoundsExceeded_perAgent_childGoingHardDoesNotBlockParent()
    {
        // 流内闸口(checkStreamHealth)也按 agent 判:子跑过硬上限不会误杀父。
        ToolBudget budget = newBudget();
        Long parent = 1L, child = 6L;
        for (int i = 0; i <= HARD_MAX_ROUNDS; i++)
        {
            budget.beginRound(child);
        }
        assertTrue(budget.hardRoundsExceeded(child));
        assertFalse(budget.hardRoundsExceeded(parent));
        assertEquals(ToolBudget.Verdict.ALLOW, budget.acquire("anyTool", parent));
    }

    @Test
    void maxResultLines_defaultsTo2000()
    {
        ToolBudget budget = new ToolBudget(MAX_ROUNDS, HARD_MAX_ROUNDS, MAX_TOTAL_CHARS,
                MAX_RESULT_CHARS);
        assertEquals(2000, budget.maxResultLines());
    }
}
