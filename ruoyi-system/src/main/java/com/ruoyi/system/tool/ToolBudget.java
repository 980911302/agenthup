package com.ruoyi.system.tool;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单次运行的工具调用预算 + 可选会话累计 + 按工具限额 + prompt token。
 *
 * <p>rounds 按「模型往返」计(见 {@link #beginRound()}),不按单次工具调用。
 * 按工具限额与会话累计是「调用次数」口径,与 rounds 语义不同,不要混用。
 *
 * @author ruoyi
 */
public final class ToolBudget
{
    /** 行数上限默认值,与 OpenCode tool/truncate.ts 的 MAX_LINES 对齐。 */
    private static final int DEFAULT_MAX_RESULT_LINES = 2000;

    public enum Verdict
    {
        ALLOW, SOFT_EXCEEDED, HARD_EXCEEDED
    }

    public enum ExhaustReason
    {
        ROUNDS_OR_CHARS,
        PROMPT_TOKENS,
        PER_TOOL,
        SESSION
    }

    /**
     * 软上限时的行为:END=把说明当 tool result 回给模型让其收尾;ERROR=直接抛异常中断。
     * <p>硬上限始终 ERROR。
     */
    public enum ExitBehavior
    {
        END, ERROR;

        public static ExitBehavior from(String raw)
        {
            if (raw == null || raw.isBlank())
            {
                return END;
            }
            try
            {
                return ExitBehavior.valueOf(raw.trim().toUpperCase());
            }
            catch (IllegalArgumentException e)
            {
                return END;
            }
        }
    }

    /** 跨 run 共享的会话累计(同一 sessionId 多轮对话累加)。 */
    public static final class SessionTally
    {
        private final AtomicInteger rounds = new AtomicInteger();
        private final AtomicInteger toolCalls = new AtomicInteger();
        private final AtomicLong totalChars = new AtomicLong();

        public int rounds() { return rounds.get(); }
        public int toolCalls() { return toolCalls.get(); }
        public long totalChars() { return totalChars.get(); }
    }

    private final int maxRounds;
    private final int hardMaxRounds;
    private final long maxTotalChars;
    private final int maxResultChars;
    /** 单个工具返回给模型的行数上限,与字符上限是两个独立维度。 */
    private final int maxResultLines;
    private final int inputBudget;
    private final double softTokenRatio;
    private final double hardTokenRatio;
    private final ExitBehavior exitBehavior;
    /** toolName → 单次运行最多调用次数;空 map 表示不按工具限。 */
    private final Map<String, Integer> perToolLimits;
    private final SessionTally session;
    private final int maxSessionRounds;
    private final int maxSessionToolCalls;
    private final long maxSessionTotalChars;

    /**
     * agentId -> 该 agent 本 run 的往返轮次。
     *
     * <p><b>必须按 agent 分</b>:ToolBudget 是 session 级共享的,而子智能体是独立无状态会话,
     * 它的往返不占父的轮次配额。原来共用一个 rounds 计数器,父 20 轮 + 子 30 轮 = 50
     * 就顶到 max-rounds,父被子的往返拖垮。与 {@link #promptByAgent} 同构:轮次配额天生
     * per-agent。会话级累计({@link #session}.rounds)仍共享,作跨 run 总闸(maxSessionRounds)。
     * key 用 -1 兜底未知 agent(老调用方不传 agentId 时)。
     */
    private final ConcurrentHashMap<Long, AtomicInteger> roundsByAgent = new ConcurrentHashMap<>();
    private final AtomicLong totalChars = new AtomicLong();
    /**
     * agentId → 该 agent 最近一次调用的 prompt_tokens。
     *
     * <p><b>必须按 agent 分开</b>:ToolBudget 是 session 级共享的,而子智能体是独立无状态会话,
     * 它的上下文不占父的窗口。原来共用一个「最后写入者赢」的单值,子 agent 把 prompt 顶到 113K
     * 之后,只用了 5K 的父也会被判定为「上下文已占用 95%」而被拦下,且这个值一直留着不会退。
     *
     * <p>轮次/字符那几项按会话共享是对的(防的是整体烧钱);唯独 token 这项衡量的是
     * 「某个 agent 的窗口还剩多少」,天生是 per-agent 的。
     *
     * <p>key 用 -1 兜底未知 agent(老调用方不传 agentId 时)。
     */
    private final ConcurrentHashMap<Long, AtomicInteger> promptByAgent = new ConcurrentHashMap<>();
    /**
     * agentId → 该 agent 自己模型的输入预算(token)。
     *
     * <p><b>为什么按 agent 分</b>:inputBudget 来自建模时父 agent 模型的
     * {@code contextWindow - maxOutputTokens},而子智能体可以用完全不同的模型 ——
     * 窗口比父小,共用父的预算会保护不到(真满了都没触发);窗口比父大,又会误伤。
     * 与 {@link #promptByAgent} 同构:用量和上限都是 per-agent 的,配对才有意义。
     * 未注册的 agent 回退到 session 级 {@link #inputBudget}(建模时父 agent 的值)。
     */
    private final ConcurrentHashMap<Long, Integer> inputBudgetByAgent = new ConcurrentHashMap<>();
    /** 最近一次触发 token 判定的 agent,供 describeExhausted 取用量 */
    private final AtomicReference<Long> lastPromptAgent = new AtomicReference<>();
    private final ConcurrentHashMap<String, AtomicInteger> perToolCalls = new ConcurrentHashMap<>();
    private final AtomicReference<ExhaustReason> lastReason =
            new AtomicReference<>(ExhaustReason.ROUNDS_OR_CHARS);
    private final AtomicReference<String> lastToolName = new AtomicReference<>();

    public ToolBudget(int maxRounds, int hardMaxRounds, long maxTotalChars, int maxResultChars,
                      int inputBudget, double softTokenRatio, double hardTokenRatio,
                      ExitBehavior exitBehavior, Map<String, Integer> perToolLimits,
                      SessionTally session, int maxSessionRounds, int maxSessionToolCalls,
                      long maxSessionTotalChars)
    {
        this(maxRounds, hardMaxRounds, maxTotalChars, maxResultChars, DEFAULT_MAX_RESULT_LINES,
                inputBudget, softTokenRatio, hardTokenRatio, exitBehavior, perToolLimits,
                session, maxSessionRounds, maxSessionToolCalls, maxSessionTotalChars);
    }

    public ToolBudget(int maxRounds, int hardMaxRounds, long maxTotalChars, int maxResultChars,
                      int maxResultLines, int inputBudget, double softTokenRatio,
                      double hardTokenRatio, ExitBehavior exitBehavior,
                      Map<String, Integer> perToolLimits, SessionTally session,
                      int maxSessionRounds, int maxSessionToolCalls, long maxSessionTotalChars)
    {
        this.maxRounds = maxRounds;
        this.hardMaxRounds = hardMaxRounds;
        this.maxTotalChars = maxTotalChars;
        this.maxResultChars = maxResultChars;
        this.maxResultLines = maxResultLines > 0 ? maxResultLines : DEFAULT_MAX_RESULT_LINES;
        this.inputBudget = Math.max(inputBudget, 0);
        this.softTokenRatio = clampRatio(softTokenRatio, 0.75);
        this.hardTokenRatio = clampRatio(hardTokenRatio, 0.90);
        this.exitBehavior = exitBehavior != null ? exitBehavior : ExitBehavior.END;
        this.perToolLimits = perToolLimits != null ? perToolLimits : Collections.emptyMap();
        this.session = session != null ? session : new SessionTally();
        this.maxSessionRounds = Math.max(maxSessionRounds, 0);
        this.maxSessionToolCalls = Math.max(maxSessionToolCalls, 0);
        this.maxSessionTotalChars = Math.max(maxSessionTotalChars, 0L);
    }

    /** 兼容旧构造:不做 token/会话/按工具约束,ExitBehavior=END。 */
    public ToolBudget(int maxRounds, int hardMaxRounds, long maxTotalChars, int maxResultChars)
    {
        this(maxRounds, hardMaxRounds, maxTotalChars, maxResultChars, 0, 0.75, 0.90,
                ExitBehavior.END, Collections.emptyMap(), new SessionTally(), 0, 0, 0L);
    }

    public ToolBudget(int maxRounds, int hardMaxRounds, long maxTotalChars, int maxResultChars,
                      int inputBudget, double softTokenRatio, double hardTokenRatio)
    {
        this(maxRounds, hardMaxRounds, maxTotalChars, maxResultChars, inputBudget,
                softTokenRatio, hardTokenRatio, ExitBehavior.END, Collections.emptyMap(),
                new SessionTally(), 0, 0, 0L);
    }

    public void beginRound()
    {
        beginRound(null);
    }

    /**
     * 推进某 agent 的往返轮次(per-agent)。会话级累计({@code session.rounds})仍共享,
     * 作跨 run 总闸({@link #maxSessionRounds});本 run 的 {@code maxRounds}/{@code hardMaxRounds}
     * 则按 agent 各自判,子的往返不再占父的配额。
     */
    public void beginRound(Long agentId)
    {
        roundsOf(agentId).incrementAndGet();
        session.rounds.incrementAndGet();
    }

    /** 兼容旧调用(不区分 agent),记到兜底槽位。 */
    public void recordPromptTokens(int promptTokens)
    {
        recordPromptTokens(null, promptTokens);
    }

    /**
     * 记录某个 agent 最近一次调用的 prompt_tokens。
     *
     * @param agentId 产生这次调用的智能体;null 归入兜底槽位
     */
    public void recordPromptTokens(Long agentId, int promptTokens)
    {
        if (promptTokens > 0)
        {
            promptOf(agentId).set(promptTokens);
        }
    }

    private AtomicInteger promptOf(Long agentId)
    {
        return promptByAgent.computeIfAbsent(agentId != null ? agentId : -1L,
                k -> new AtomicInteger());
    }

    /** 按 agent 取本 run 往返轮次计数器;null 归入 -1 兜底槽位(与 {@link #promptOf} 同口径)。 */
    private AtomicInteger roundsOf(Long agentId)
    {
        return roundsByAgent.computeIfAbsent(agentId != null ? agentId : -1L,
                k -> new AtomicInteger());
    }

    /**
     * 注册某个 agent 自己模型的输入预算。子智能体装配出上下文后、首次调用前注册。
     * 重复注册覆盖旧值(模型热更新后下一轮生效);budget<=0 时移除注册,回退 session 级。
     */
    public void registerInputBudget(Long agentId, int budget)
    {
        if (agentId == null)
        {
            return;
        }
        if (budget > 0)
        {
            inputBudgetByAgent.put(agentId, budget);
        }
        else
        {
            inputBudgetByAgent.remove(agentId);
        }
    }

    /**
     * 取某个 agent 的输入预算:优先它自己模型注册的窗口,未注册回退 session 级
     * (建模时父 agent 的窗口)。<=0 表示不做 token 判定。
     */
    public int inputBudgetFor(Long agentId)
    {
        Integer budget = agentId != null ? inputBudgetByAgent.get(agentId) : null;
        return budget != null ? budget : inputBudget;
    }

    /**
     * 无工具名时的判定(仅轮次/字符/token/会话),兼容旧调用。
     */
    public Verdict acquire()
    {
        return acquire(null, null);
    }

    /** 兼容旧调用(不区分 agent):token 判定取兜底槽位。 */
    public Verdict acquire(String toolName)
    {
        return acquire(toolName, null);
    }

    /**
     * 单次工具调用前判定。
     * <p>ALLOW 时递增该工具的本 run 计数与会话 toolCalls。
     * 并行工具共享同一轮 rounds 配额,但各自占用自己的 per-tool 次数。
     *
     * @param agentId 发起这次工具调用的智能体;token 判定只看它自己的上下文,
     *                不受同会话其他智能体(尤其是上下文很大的子智能体)影响
     */
    public Verdict acquire(String toolName, Long agentId)
    {
        lastToolName.set(toolName);
        lastPromptAgent.set(agentId);

        // 1) 硬:轮次(per-agent:子的往返不占父的配额)
        int n = roundsOf(agentId).get();
        if (n > hardMaxRounds)
        {
            return hard(ExhaustReason.ROUNDS_OR_CHARS);
        }

        // 2) 硬:prompt token(上限取该 agent 自己模型的窗口,未注册回退 session 级)
        int prompt = promptOf(agentId).get();
        int agentInputBudget = inputBudgetFor(agentId);
        if (agentInputBudget > 0 && prompt > 0)
        {
            int hardCeiling = (int) Math.floor(agentInputBudget * hardTokenRatio);
            if (prompt > hardCeiling)
            {
                return hard(ExhaustReason.PROMPT_TOKENS);
            }
        }

        // 3) 会话硬上限(配置了才生效;超了按硬处理,避免跨轮无限烧)
        if (maxSessionRounds > 0 && session.rounds.get() > maxSessionRounds)
        {
            return hard(ExhaustReason.SESSION);
        }
        if (maxSessionToolCalls > 0 && session.toolCalls.get() >= maxSessionToolCalls)
        {
            return hard(ExhaustReason.SESSION);
        }
        if (maxSessionTotalChars > 0 && session.totalChars.get() > maxSessionTotalChars)
        {
            return hard(ExhaustReason.SESSION);
        }

        // 4) 按工具限额(本 run)
        if (toolName != null && !toolName.isEmpty())
        {
            Integer limit = perToolLimits.get(toolName);
            if (limit != null && limit > 0)
            {
                int used = perToolCalls.computeIfAbsent(toolName, k -> new AtomicInteger()).get();
                if (used >= limit)
                {
                    return softOrHard(ExhaustReason.PER_TOOL);
                }
            }
        }

        // 5) 软:prompt
        if (agentInputBudget > 0 && prompt > 0)
        {
            int softCeiling = (int) Math.floor(agentInputBudget * softTokenRatio);
            if (prompt > softCeiling)
            {
                return softOrHard(ExhaustReason.PROMPT_TOKENS);
            }
        }

        // 6) 软:本 run 轮次/字符
        if (n > maxRounds || totalChars.get() > maxTotalChars)
        {
            return softOrHard(ExhaustReason.ROUNDS_OR_CHARS);
        }

        // 通过:记账
        if (toolName != null && !toolName.isEmpty())
        {
            perToolCalls.computeIfAbsent(toolName, k -> new AtomicInteger()).incrementAndGet();
            session.toolCalls.incrementAndGet();
        }
        return Verdict.ALLOW;
    }

    public void recordResult(int chars)
    {
        totalChars.addAndGet(chars);
        session.totalChars.addAndGet(chars);
    }

    public int maxResultChars()
    {
        return maxResultChars;
    }

    public int maxResultLines()
    {
        return maxResultLines;
    }

    public int inputBudget()
    {
        return inputBudget;
    }

    public int lastPromptTokens()
    {
        return promptOf(lastPromptAgent.get()).get();
    }

    public ExitBehavior exitBehavior()
    {
        return exitBehavior;
    }

    /** 兜底槽位(-1)的本 run 往返轮次;兼容旧调用方。按 agent 取用 {@link #rounds(Long)}。 */
    public int rounds()
    {
        return roundsOf(null).get();
    }

    /** 按 agent 取其本 run 往返轮次。 */
    public int rounds(Long agentId)
    {
        return roundsOf(agentId).get();
    }

    public boolean hardRoundsExceeded()
    {
        return hardRoundsExceeded(null);
    }

    /** 轮次是否已过硬顶(per-agent);供流内闸口用:纯生成轮(无工具调用)不会触发 acquire。 */
    public boolean hardRoundsExceeded(Long agentId)
    {
        return roundsOf(agentId).get() > hardMaxRounds;
    }

    /**
     * 流内脏数据闸口:单轮真实 prompt 超过输入预算两倍(必然超过模型原始窗口,
     * 上游真收到这种请求会先 400),判定为 usage 流损坏(重放/累计回吐),不是真实占用。
     */
    public boolean isCorruptPrompt(Long agentId, int realPrompt)
    {
        int cap = inputBudgetFor(agentId);
        return cap > 0 && realPrompt > 2L * cap;
    }

    public long totalChars()
    {
        return totalChars.get();
    }

    public SessionTally session()
    {
        return session;
    }

    public int perToolCount(String toolName)
    {
        AtomicInteger c = perToolCalls.get(toolName);
        return c == null ? 0 : c.get();
    }

    public String describeExhausted()
    {
        ExhaustReason reason = lastReason.get();
        int triggerBudget = inputBudgetFor(lastPromptAgent.get());
        if (reason == ExhaustReason.PROMPT_TOKENS && triggerBudget > 0)
        {
            int used = promptOf(lastPromptAgent.get()).get();
            int pct = (int) Math.round(used * 100.0 / triggerBudget);
            return "[系统提示] 当前上下文已占用约 " + pct + "%（" + used + "/" + triggerBudget
                    + " token），接近上限。\n"
                    + "请停止继续读取新内容，基于已获得的信息完成任务。\n"
                    + "如果还有必须处理的部分，请先输出当前进展与结论，并说明剩余工作，"
                    + "用户可以在新会话中继续。";
        }
        if (reason == ExhaustReason.PER_TOOL)
        {
            String tool = lastToolName.get();
            Integer limit = tool != null ? perToolLimits.get(tool) : null;
            return "[系统提示] 工具「" + (tool != null ? tool : "?")
                    + "」在本轮运行中的调用次数已达上限"
                    + (limit != null ? "（" + limit + " 次）" : "")
                    + "。\n请改用其他方式完成任务，或基于已有结果直接给出答复。";
        }
        if (reason == ExhaustReason.SESSION)
        {
            return "[系统提示] 本会话的工具预算已耗尽"
                    + "（往返 " + session.rounds.get()
                    + " / 调用 " + session.toolCalls.get()
                    + " / 累计返回 " + session.totalChars.get() + " 字符）。\n"
                    + "请停止继续调用工具，基于已有信息给出最终答复。"
                    + "如需继续大量操作，请开启新会话。";
        }
        return "[系统提示] 工具调用预算已耗尽(已进行 " + roundsOf(lastPromptAgent.get()).get()
                + " 轮模型往返 / 累计返回 " + totalChars.get() + " 字符)。\n"
                + "请立即停止调用任何工具,基于已经获得的信息直接给出最终答复。\n"
                + "如果信息不足以完成任务,请说明已完成的部分和仍然缺少什么。";
    }

    private Verdict hard(ExhaustReason reason)
    {
        lastReason.set(reason);
        return Verdict.HARD_EXCEEDED;
    }

    private Verdict softOrHard(ExhaustReason reason)
    {
        lastReason.set(reason);
        if (exitBehavior == ExitBehavior.ERROR)
        {
            return Verdict.HARD_EXCEEDED;
        }
        return Verdict.SOFT_EXCEEDED;
    }

    private static double clampRatio(double ratio, double fallback)
    {
        if (ratio <= 0 || ratio >= 1)
        {
            return fallback;
        }
        return ratio;
    }
}
