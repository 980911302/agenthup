package com.ruoyi.system.kb.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class KbSearchModePolicyTest
{
    @Test
    void basicAlwaysEnabledAndAutoNeverDefault()
    {
        KbSearchModePolicy p = new KbSearchModePolicy();
        p.overrideForTest("local,hybrid,auto", "auto", true, true);
        assertTrue(p.isEnabled(KbSearchMode.basic));
        assertEquals(KbSearchMode.basic, p.defaultMode()); // auto 被拒绝
    }

    @Test
    void resolveFallsBackWhenModeDisabled()
    {
        KbSearchModePolicy p = new KbSearchModePolicy();
        p.overrideForTest("basic,local", "basic", true, true);
        assertFalse(p.isEnabled(KbSearchMode.global));
        assertEquals(KbSearchMode.basic, p.resolve(KbSearchMode.global));
        assertEquals(KbSearchMode.local, p.resolve(KbSearchMode.local));
    }

    @Test
    void emptyConfigKeepsBasic()
    {
        KbSearchModePolicy p = new KbSearchModePolicy();
        p.overrideForTest("", "basic", true, true);
        assertTrue(p.enabledSet().contains(KbSearchMode.basic));
    }
}
