package com.ruoyi.system.kb.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KbSearchRequestNormalizerTest
{
    @Test
    void nonManageIgnoresAdvancedParams()
    {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", "q");
        body.put("mode", "global");
        body.put("debug", true);
        body.put("topK", 20);
        body.put("minScore", 0.01);

        var p = KbSearchRequestNormalizer.normalize(false, body);
        assertEquals(KbSearchRequestNormalizer.DEFAULT_TOP_K, p.topK());
        assertEquals(KbSearchRequestNormalizer.DEFAULT_MIN_SCORE, p.minScore(), 1e-9);
        assertEquals(KbSearchMode.of("auto"), p.mode());
        assertFalse(p.debug());
    }

    @Test
    void manageKeepsAdvancedParams()
    {
        Map<String, Object> body = Map.of(
            "mode", "hybrid",
            "debug", "true",
            "topK", 10,
            "minScore", 0.5);

        var p = KbSearchRequestNormalizer.normalize(true, body);
        assertEquals(10, p.topK());
        assertEquals(0.5, p.minScore(), 1e-9);
        assertEquals(KbSearchMode.of("hybrid"), p.mode());
        assertTrue(p.debug());
    }

    @Test
    void manageClampsTopKAndMinScore()
    {
        Map<String, Object> body = Map.of("topK", 99, "minScore", -1);
        var p = KbSearchRequestNormalizer.normalize(true, body);
        assertEquals(20, p.topK());
        assertEquals(0.0, p.minScore(), 1e-9);
    }
}
