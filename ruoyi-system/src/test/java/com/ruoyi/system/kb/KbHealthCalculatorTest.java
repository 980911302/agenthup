package com.ruoyi.system.kb;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import com.ruoyi.system.domain.vo.KbWorkbenchItem;

class KbHealthCalculatorTest
{
    @Test
    void readyWhenDocsOk()
    {
        KbWorkbenchItem i = base();
        i.setDocCount(3);
        i.setReadyCount(3);
        i.setProcessingCount(0);
        i.setFailedCount(0);
        KbHealthCalculator.apply(i);
        assertEquals("READY", i.getHealth());
    }

    @Test
    void issueWhenAllFailed()
    {
        KbWorkbenchItem i = base();
        i.setDocCount(5);
        i.setFailedCount(5);
        i.setReadyCount(0);
        i.setProcessingCount(0);
        KbHealthCalculator.apply(i);
        assertEquals("ISSUE", i.getHealth());
        assertEquals("open_content_failed", KbHealthCalculator.suggestedAction(i.getHealth()));
    }

    @Test
    void partialWhenSomeReadySomeFailed()
    {
        KbWorkbenchItem i = base();
        i.setDocCount(5);
        i.setFailedCount(2);
        i.setReadyCount(3);
        i.setProcessingCount(0);
        KbHealthCalculator.apply(i);
        assertEquals("PARTIAL", i.getHealth());
    }

    @Test
    void processingPreferredOverFailed()
    {
        KbWorkbenchItem i = base();
        i.setDocCount(5);
        i.setFailedCount(1);
        i.setProcessingCount(2);
        KbHealthCalculator.apply(i);
        assertEquals("PROCESSING", i.getHealth());
    }

    @Test
    void emptyAndDisabled()
    {
        KbWorkbenchItem empty = base();
        empty.setDocCount(0);
        KbHealthCalculator.apply(empty);
        assertEquals("EMPTY", empty.getHealth());
        assertEquals("add_content", KbHealthCalculator.suggestedAction(empty.getHealth()));

        KbWorkbenchItem off = base();
        off.setStatus("1");
        off.setDocCount(10);
        KbHealthCalculator.apply(off);
        assertEquals("DISABLED", off.getHealth());
        assertEquals("open_settings", KbHealthCalculator.suggestedAction(off.getHealth()));
    }

    private static KbWorkbenchItem base()
    {
        KbWorkbenchItem i = new KbWorkbenchItem();
        i.setKbId(1L);
        i.setStatus("0");
        i.setDeptId(10L);
        i.setCreateUserId(2L);
        return i;
    }
}
