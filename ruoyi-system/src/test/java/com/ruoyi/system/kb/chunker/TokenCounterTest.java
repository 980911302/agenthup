package com.ruoyi.system.kb.chunker;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TokenCounterTest
{
    @Test
    void usesRealTokenizerForEnglishAndChinese()
    {
        assertEquals(2, TokenCounter.estimate("hello world"));
        assertTrue(TokenCounter.estimate("知识库结构化切片") >= 5);
        assertEquals(0, TokenCounter.estimate(""));
    }
}
