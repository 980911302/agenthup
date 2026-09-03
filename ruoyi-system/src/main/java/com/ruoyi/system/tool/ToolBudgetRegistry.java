package com.ruoyi.system.tool;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按会话管理工具预算生命周期。
 *
 * <p>run 预算在 open/close 间重置;会话累计({@link ToolBudget.SessionTally})跨 run 保留,
 * 直到 {@link #clearSession(String)}(清记忆时调用)。
 */
@Component
public class ToolBudgetRegistry
{
    private final Map<String, ToolBudget> budgets = new ConcurrentHashMap<>();
    private final Map<String, ToolBudget.SessionTally> sessionTallies = new ConcurrentHashMap<>();

    @Value("${ai.chat.tool.max-rounds:25}")
    private int maxRounds;

    @Value("${ai.chat.tool.hard-max-rounds:35}")
    private int hardMaxRounds;

    @Value("${ai.chat.tool.max-result-chars:30000}")
    private int maxResultChars;

    /** 单个工具返回给模型的行数上限,与字符上限独立。 */
    @Value("${ai.chat.tool.max-result-lines:2000}")
    private int maxResultLines;

    @Value("${ai.chat.tool.max-total-chars:200000}")
    private long maxTotalChars;

    @Value("${ai.chat.tool.soft-token-ratio:0.75}")
    private double softTokenRatio;

    @Value("${ai.chat.tool.hard-token-ratio:0.90}")
    private double hardTokenRatio;

    /** END=软上限返回提示;ERROR=软上限也抛异常。 */
    @Value("${ai.chat.tool.exit-behavior:END}")
    private String exitBehavior;

    /** 会话累计:往返次数上限;0=不限。 */
    @Value("${ai.chat.tool.max-session-rounds:0}")
    private int maxSessionRounds;

    /** 会话累计:工具调用次数上限;0=不限。 */
    @Value("${ai.chat.tool.max-session-tool-calls:0}")
    private int maxSessionToolCalls;

    /** 会话累计:工具返回字符上限;0=不限。 */
    @Value("${ai.chat.tool.max-session-total-chars:0}")
    private long maxSessionTotalChars;

    @Autowired(required = false)
    private ToolPolicyService toolPolicyService;

    public ToolBudget open(String sessionId)
    {
        return open(sessionId, 0);
    }

    public ToolBudget open(String sessionId, int inputBudget)
    {
        ToolBudget.SessionTally tally = sessionTallies.computeIfAbsent(
                sessionId, k -> new ToolBudget.SessionTally());
        Map<String, Integer> perTool = toolPolicyService != null
                ? toolPolicyService.maxCallsPerRun() : Map.of();
        ToolBudget.ExitBehavior behavior = ToolBudget.ExitBehavior.from(exitBehavior);
        ToolBudget budget = new ToolBudget(maxRounds, hardMaxRounds, maxTotalChars, maxResultChars,
                maxResultLines, inputBudget, softTokenRatio, hardTokenRatio, behavior, perTool,
                tally, maxSessionRounds, maxSessionToolCalls, maxSessionTotalChars);
        budgets.put(sessionId, budget);
        return budget;
    }

    public ToolBudget get(String sessionId)
    {
        if (sessionId == null)
        {
            return null;
        }
        return budgets.get(sessionId);
    }

    public void close(String sessionId)
    {
        if (sessionId == null)
        {
            return;
        }
        budgets.remove(sessionId);
        // 会话累计故意不删:防「每轮刚好不超、十轮烧光」
    }

    /**
     * 清空会话累计(用户清空记忆/删会话时调用)。
     */
    public void clearSession(String sessionId)
    {
        if (sessionId == null)
        {
            return;
        }
        budgets.remove(sessionId);
        sessionTallies.remove(sessionId);
    }
}
