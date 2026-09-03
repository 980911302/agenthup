package com.ruoyi.system.kb.search;

/**
 * DRIFT 硬预算：深度、分支、检索次数、token、超时。
 * <p>任意计数器触顶即终止，保证必然结束。
 */
public final class DriftSearchBudget
{
    private final int maxDepth;
    private final int maxFollowupsPerLevel;
    private final int maxRetrievals;
    private final int maxTokens;
    private final long deadlineMs;

    private int depthUsed;
    private int retrievalsUsed;
    private int tokensUsed;
    private int followupsAtCurrentDepth;
    private String stopReason;

    public DriftSearchBudget(int maxDepth, int maxFollowupsPerLevel, int maxRetrievals,
        int maxTokens, long timeoutMs)
    {
        this.maxDepth = Math.max(1, maxDepth);
        this.maxFollowupsPerLevel = Math.max(1, maxFollowupsPerLevel);
        this.maxRetrievals = Math.max(1, maxRetrievals);
        this.maxTokens = Math.max(1, maxTokens);
        long to = timeoutMs > 0 ? timeoutMs : 30_000L;
        this.deadlineMs = System.currentTimeMillis() + to;
    }

    public static DriftSearchBudget defaults()
    {
        // 计划默认：深度 2、每层追问 3
        return new DriftSearchBudget(2, 3, 12, 8000, 30_000L);
    }

    /**
     * 资源是否仍可用（超时/检索/token/显式 stop）。
     * 深度上限只在 {@link #enterDepth()} 判定，避免层内追问被误杀。
     */
    public boolean canContinue()
    {
        if (stopReason != null)
        {
            return false;
        }
        if (System.currentTimeMillis() >= deadlineMs)
        {
            stopReason = "timeout";
            return false;
        }
        if (retrievalsUsed >= maxRetrievals)
        {
            stopReason = "max_retrievals";
            return false;
        }
        if (tokensUsed >= maxTokens)
        {
            stopReason = "max_tokens";
            return false;
        }
        return true;
    }

    /** 进入下一深度前调用；超过 maxDepth 则终止 */
    public boolean enterDepth()
    {
        if (!canContinue())
        {
            return false;
        }
        if (depthUsed >= maxDepth)
        {
            stopReason = "max_depth";
            return false;
        }
        depthUsed++;
        followupsAtCurrentDepth = 0;
        return true;
    }

    public boolean allowFollowup()
    {
        if (!canContinue())
        {
            return false;
        }
        if (followupsAtCurrentDepth >= maxFollowupsPerLevel)
        {
            return false;
        }
        return true;
    }

    public void consumeFollowup()
    {
        followupsAtCurrentDepth++;
    }

    public boolean consumeRetrieval()
    {
        if (System.currentTimeMillis() >= deadlineMs)
        {
            stopReason = "timeout";
            return false;
        }
        if (retrievalsUsed >= maxRetrievals)
        {
            stopReason = "max_retrievals";
            return false;
        }
        retrievalsUsed++;
        return true;
    }

    public void consumeTokens(int n)
    {
        if (n > 0)
        {
            tokensUsed += n;
            if (tokensUsed >= maxTokens && stopReason == null)
            {
                stopReason = "max_tokens";
            }
        }
    }

    public void stop(String reason)
    {
        if (stopReason == null)
        {
            stopReason = reason != null ? reason : "stopped";
        }
    }

    public int getMaxDepth() { return maxDepth; }
    public int getMaxFollowupsPerLevel() { return maxFollowupsPerLevel; }
    public int getMaxRetrievals() { return maxRetrievals; }
    public int getMaxTokens() { return maxTokens; }
    public int getDepthUsed() { return depthUsed; }
    public int getRetrievalsUsed() { return retrievalsUsed; }
    public int getTokensUsed() { return tokensUsed; }
    public int getFollowupsAtCurrentDepth() { return followupsAtCurrentDepth; }
    public String getStopReason() { return stopReason; }

    public boolean isExhausted()
    {
        return stopReason != null || !canContinue();
    }
}
