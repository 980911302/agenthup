package com.ruoyi.system.kb.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class KbSearchModeTest
{
    @Test
    void aliasesMapToCanonical()
    {
        assertEquals(KbSearchMode.basic, KbSearchMode.of("vector"));
        assertEquals(KbSearchMode.basic, KbSearchMode.of("basic"));
        assertEquals(KbSearchMode.local, KbSearchMode.of("graph"));
        assertEquals(KbSearchMode.local, KbSearchMode.of("local"));
        assertEquals(KbSearchMode.hybrid, KbSearchMode.of("mix"));
        assertEquals(KbSearchMode.hybrid, KbSearchMode.of("hybrid"));
        assertEquals(KbSearchMode.global, KbSearchMode.of("global"));
        assertEquals(KbSearchMode.global, KbSearchMode.of("community"));
        assertEquals(KbSearchMode.drift, KbSearchMode.of("drift"));
        assertEquals(KbSearchMode.auto, KbSearchMode.of("auto"));
    }

    @Test
    void defaultAndUnknownAreBasic()
    {
        assertEquals(KbSearchMode.basic, KbSearchMode.of(null));
        assertEquals(KbSearchMode.basic, KbSearchMode.of(""));
        assertEquals(KbSearchMode.basic, KbSearchMode.of("nope"));
    }

    @Test
    void normalizeLegacyEnums()
    {
        assertEquals(KbSearchMode.basic, KbSearchMode.normalize(KbSearchMode.vector));
        assertEquals(KbSearchMode.local, KbSearchMode.normalize(KbSearchMode.graph));
        assertEquals(KbSearchMode.hybrid, KbSearchMode.normalize(KbSearchMode.mix));
        assertEquals(KbSearchMode.global, KbSearchMode.normalize(KbSearchMode.global));
        assertEquals(KbSearchMode.drift, KbSearchMode.normalize(KbSearchMode.drift));
        assertEquals(KbSearchMode.auto, KbSearchMode.normalize(KbSearchMode.auto));
        assertTrue(KbSearchMode.vector.isBasic());
        assertTrue(KbSearchMode.graph.isLocal());
        assertTrue(KbSearchMode.mix.isHybrid());
        assertTrue(KbSearchMode.global.isGlobal());
        assertTrue(KbSearchMode.drift.isDrift());
        assertTrue(KbSearchMode.auto.isAuto());
    }
}
