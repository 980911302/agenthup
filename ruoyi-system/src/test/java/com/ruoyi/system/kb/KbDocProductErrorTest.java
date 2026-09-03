package com.ruoyi.system.kb;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import com.ruoyi.system.domain.KbDocument;

class KbDocProductErrorTest
{
    @Test
    void hidesStackTrace()
    {
        KbDocument d = new KbDocument();
        d.setParseStatus("FAILED");
        d.setErrorStage("embed");
        d.setErrorMsg("java.lang.RuntimeException: boom\n\tat com.foo.Bar.baz(Bar.java:1)");
        String msg = KbDocProductError.message(d);
        assertNotNull(msg);
        assertFalse(msg.contains("\tat "));
        assertTrue(msg.contains("向量") || msg.contains("重新处理"));
    }

    @Test
    void ocrHint()
    {
        KbDocument d = new KbDocument();
        d.setParseStatus("FAILED");
        d.setErrorMsg("PDF 文本过少，疑似扫描件");
        String msg = KbDocProductError.message(d);
        assertTrue(msg.contains("扫描") || msg.contains("OCR") || msg.contains("文字"));
    }
}
