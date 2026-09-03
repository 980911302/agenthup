package com.ruoyi.system.kb.graph;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.ruoyi.system.kb.graph.merge.DescriptionSummarizer;

class DescriptionSummarizerTest
{
    @Test
    void belowThreshold_noLlm()
    {
        List<String> descs = List.of("描述一", "描述二", "描述三");
        assertFalse(DescriptionSummarizer.needsLlm(descs));
        assertEquals("描述一；描述二；描述三", DescriptionSummarizer.joinDirect(descs));
    }

    @Test
    void manyItems_forceLlm()
    {
        List<String> descs = new ArrayList<>();
        for (int i = 0; i < KbGraphConstants.FORCE_LLM_SUMMARY_ON_MERGE; i++)
        {
            descs.add("第" + i + "条描述内容");
        }
        assertTrue(DescriptionSummarizer.needsLlm(descs));
    }

    @Test
    void longTokens_forceLlmEvenFewItems()
    {
        // 少量条但总 token 超 SUMMARY_MAX_TOKENS
        StringBuilder longDesc = new StringBuilder();
        for (int i = 0; i < 400; i++)
        {
            longDesc.append("中文");
        }
        List<String> descs = List.of(longDesc.toString(), "另一条也很长" + longDesc);
        assertTrue(DescriptionSummarizer.needsLlm(descs));
    }

    @Test
    void empty_noLlm()
    {
        assertFalse(DescriptionSummarizer.needsLlm(List.of()));
        assertFalse(DescriptionSummarizer.needsLlm(null));
        assertEquals("", DescriptionSummarizer.joinDirect(null));
    }
}
