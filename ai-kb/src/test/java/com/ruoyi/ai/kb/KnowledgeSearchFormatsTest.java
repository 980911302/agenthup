package com.ruoyi.ai.kb;

import com.ruoyi.ai.contract.kb.SearchHit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeSearchFormatsTest
{
    @Test
    void formatsModelTextAndDeduplicatedReferences()
    {
        SearchHit hit = new SearchHit("1", "2", "3", "正文", 0.9,
                List.of(), Map.of("docName", "手册", "headingPath", "休假", "channel", "basic"));
        assertTrue(KnowledgeSearchFormats.forModel(List.of(hit)).contains("[1] 《手册》 休假 (basic)"));
        Map<String, Object> payload = KnowledgeSearchFormats.referencesPayload("年假", List.of(hit, hit));
        assertEquals(1, payload.get("fileCount"));
        assertEquals(1, payload.get("chunkCount"));
    }
}
