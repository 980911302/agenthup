package com.ruoyi.ai.contract.core;

public record Usage(long inputTokens, long outputTokens, long cachedTokens,
                    long durationMillis, long billableUnits)
{
    public static final Usage EMPTY = new Usage(0, 0, 0, 0, 0);
}
