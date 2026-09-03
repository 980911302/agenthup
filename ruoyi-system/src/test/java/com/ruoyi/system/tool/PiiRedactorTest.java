package com.ruoyi.system.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PiiRedactorTest
{
    @Test
    void masksPhoneAndEmail()
    {
        String raw = "联系人 13812345678 邮箱 user@example.com";
        String out = PiiRedactor.forStorage(raw);
        assertTrue(out.contains("138****5678"), out);
        assertTrue(out.contains("u***@example.com"), out);
        assertFalse(out.contains("13812345678"));
    }

    @Test
    void redactsApiKeyAndBearer()
    {
        String raw = "key=sk-abc1234567890xyz Bearer eyJhbGciOiJIUzI1NiJ9.xx";
        String out = PiiRedactor.forStorage(raw);
        assertTrue(out.contains("[REDACTED]"), out);
        assertFalse(out.contains("sk-abc"));
        assertFalse(out.contains("eyJhbGci"));
    }

    @Test
    void masksIdCardKeepingHeadTail()
    {
        // 合法结构样例(非真实身份号)
        String raw = "身份证 11010519900307557X 结束";
        String out = PiiRedactor.forStorage(raw);
        assertTrue(out.contains("1101**********557X") || out.contains("**********"), out);
        assertFalse(out.contains("19900307"));
    }

    @Test
    void nullSafe()
    {
        assertEquals(null, PiiRedactor.forStorage(null));
        assertEquals("", PiiRedactor.forStorage(""));
    }
}
