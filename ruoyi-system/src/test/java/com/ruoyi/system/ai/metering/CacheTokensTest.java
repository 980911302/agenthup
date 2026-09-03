package com.ruoyi.system.ai.metering;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CacheTokensTest
{
    @Test
    void quantizedCacheAbovePromptDelta_isClampedForDisplay()
    {
        assertEquals(6451, CacheTokens.effectiveHit(6528, 6451));
        assertEquals(0, CacheTokens.effectiveHit(128, 0));
        assertEquals(100, CacheTokens.effectiveHit(100, 200));
        assertEquals(0, CacheTokens.effectiveHit(null, 10));
    }

    @Test
    void homepageTotals_areClampedTheSameWay()
    {
        assertEquals(6451L, CacheTokens.effectiveHit(6528L, 6451L));
        assertEquals(0L, CacheTokens.effectiveHit(128L, 0L));
        assertEquals(100L, CacheTokens.effectiveHit(100L, 200L));
    }
}
