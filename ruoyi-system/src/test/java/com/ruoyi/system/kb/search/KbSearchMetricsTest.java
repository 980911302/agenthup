package com.ruoyi.system.kb.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class KbSearchMetricsTest
{
    @Test
    void recordsDegradeRate()
    {
        KbSearchMetrics m = new KbSearchMetrics();
        m.record("basic", 10, false, false);
        m.record("local", 20, true, false);
        m.record("local", 30, false, false);
        Map<String, Object> snap = m.snapshot();
        assertEquals(3L, snap.get("total"));
        assertEquals(1L, snap.get("degraded"));
        assertTrue(((Number) snap.get("degradeRate")).doubleValue() > 0.3);
        @SuppressWarnings("unchecked")
        Map<String, Object> modes = (Map<String, Object>) snap.get("modes");
        assertTrue(modes.containsKey("basic"));
        assertTrue(modes.containsKey("local"));
    }
}
