package com.ruoyi.system.kb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class KbDocProductStatusTest
{
    @Test
    void mapsPipelineToProduct()
    {
        assertEquals(KbDocProductStatus.READY, KbDocProductStatus.fromParseStatus("COMPLETED"));
        assertEquals(KbDocProductStatus.FAILED, KbDocProductStatus.fromParseStatus("FAILED"));
        assertEquals(KbDocProductStatus.QUEUED, KbDocProductStatus.fromParseStatus("PENDING"));
        assertEquals(KbDocProductStatus.PROCESSING, KbDocProductStatus.fromParseStatus("PARSING"));
        assertEquals(KbDocProductStatus.PROCESSING, KbDocProductStatus.fromParseStatus("EMBEDDING"));
        assertEquals(KbDocProductStatus.QUEUED, KbDocProductStatus.fromParseStatus(null));
    }

    @Test
    void labelsAndBusy()
    {
        assertEquals("可用", KbDocProductStatus.label(KbDocProductStatus.READY));
        assertTrue(KbDocProductStatus.isBusy("CHUNKING"));
        assertTrue(KbDocProductStatus.isBusy("PENDING"));
    }
}
