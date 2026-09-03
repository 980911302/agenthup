package com.ruoyi.system.kb.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DriftSearchBudgetTest
{
    @Test
    void terminatesOnMaxRetrievals()
    {
        DriftSearchBudget b = new DriftSearchBudget(5, 5, 3, 100_000, 60_000);
        assertTrue(b.consumeRetrieval());
        assertTrue(b.consumeRetrieval());
        assertTrue(b.consumeRetrieval());
        assertFalse(b.consumeRetrieval());
        assertEquals("max_retrievals", b.getStopReason());
        assertFalse(b.canContinue());
    }

    @Test
    void terminatesOnMaxDepth()
    {
        DriftSearchBudget b = new DriftSearchBudget(2, 3, 100, 100_000, 60_000);
        assertTrue(b.enterDepth()); // 1
        assertTrue(b.canContinue());
        assertTrue(b.enterDepth()); // 2
        assertTrue(b.canContinue()); // 层内仍可追问
        assertFalse(b.enterDepth()); // 3 拒绝
        assertEquals("max_depth", b.getStopReason());
    }

    @Test
    void followupsPerLevelCapped()
    {
        DriftSearchBudget b = new DriftSearchBudget(2, 2, 100, 100_000, 60_000);
        assertTrue(b.enterDepth());
        assertTrue(b.allowFollowup());
        b.consumeFollowup();
        assertTrue(b.allowFollowup());
        b.consumeFollowup();
        assertFalse(b.allowFollowup());
    }

    @Test
    void tokenBudgetStops()
    {
        DriftSearchBudget b = new DriftSearchBudget(2, 3, 100, 50, 60_000);
        b.consumeTokens(60);
        assertEquals("max_tokens", b.getStopReason());
        assertFalse(b.canContinue());
    }

    @Test
    void explicitStop()
    {
        DriftSearchBudget b = DriftSearchBudget.defaults();
        b.stop("no_new_evidence");
        assertFalse(b.canContinue());
        assertEquals("no_new_evidence", b.getStopReason());
    }
}
