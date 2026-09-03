package com.ruoyi.system.kb.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class KbGraphPipelineHashFitTest
{
    @Test
    void fitVarcharKeepsShort()
    {
        assertEquals("abc", KbGraphPipeline.fitVarchar("abc", 64));
    }

    @Test
    void fitVarcharHashesLongToAtMostMax()
    {
        String longVal = "x".repeat(200) + "|tu:params|pf:fingerprint";
        String fitted = KbGraphPipeline.fitVarchar(longVal, 64);
        assertNotNull(fitted);
        assertTrue(fitted.length() <= 64, "len=" + fitted.length());
        // 同一输入稳定
        assertEquals(fitted, KbGraphPipeline.fitVarchar(longVal, 64));
    }

    @Test
    void compositeLikeChunkParamsFits64()
    {
        // 模拟修复前会超长的拼接
        String leaf = "ad786efa25ef516b644cc4c8bce04336";
        String unit = "a".repeat(32) + "|pf:" + "b".repeat(32);
        String composite = leaf + "|tu:" + unit;
        assertTrue(composite.length() > 64);
        String stored = KbGraphPipeline.fitVarchar(composite, 64);
        assertTrue(stored.length() <= 64);
    }
}
