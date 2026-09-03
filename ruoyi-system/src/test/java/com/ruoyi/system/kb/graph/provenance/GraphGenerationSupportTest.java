package com.ruoyi.system.kb.graph.provenance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GraphGenerationSupportTest
{
    @Test
    void nextGenerationIsMonotonic()
    {
        assertEquals(1L, GraphGenerationSupport.nextGeneration(null));
        assertEquals(1L, GraphGenerationSupport.nextGeneration(0L));
        assertEquals(4L, GraphGenerationSupport.nextGeneration(3L));
    }

    @Test
    void canCommitRequiresMatchingActiveRunAndGeneration()
    {
        assertTrue(GraphGenerationSupport.canCommit(9L, 3L, 9L, 3L));
        assertFalse(GraphGenerationSupport.canCommit(9L, 3L, 8L, 3L));
        assertFalse(GraphGenerationSupport.canCommit(9L, 3L, 9L, 2L));
        assertFalse(GraphGenerationSupport.canCommit(null, 3L, 9L, 3L));
    }

    @Test
    void staleGenerationIsRejected()
    {
        assertTrue(GraphGenerationSupport.isStaleGeneration(3L, 3L));
        assertTrue(GraphGenerationSupport.isStaleGeneration(3L, 2L));
        assertFalse(GraphGenerationSupport.isStaleGeneration(3L, 4L));
        assertFalse(GraphGenerationSupport.isStaleGeneration(null, 1L));
    }

    @Test
    void onlyCacheableOutcomesEnterLongTermCache()
    {
        assertTrue(GraphExtractOutcome.cacheable(GraphExtractOutcome.SUCCESS));
        assertTrue(GraphExtractOutcome.cacheable(GraphExtractOutcome.VALID_EMPTY));
        assertFalse(GraphExtractOutcome.cacheable(GraphExtractOutcome.LLM_FAILED));
        assertFalse(GraphExtractOutcome.cacheable(GraphExtractOutcome.PARSE_FAILED));
        assertFalse(GraphExtractOutcome.cacheable(GraphExtractOutcome.VALIDATION_FAILED));
    }
}
