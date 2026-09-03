package com.ruoyi.system.kb.search;

import java.util.List;
import java.util.Map;
import com.ruoyi.ai.contract.core.InvocationContext;
import com.ruoyi.ai.contract.kb.KnowledgeSearchPort;
import com.ruoyi.ai.contract.kb.SearchHit;
import com.ruoyi.ai.contract.kb.SearchMode;
import com.ruoyi.ai.contract.kb.SearchResponse;
import com.ruoyi.system.tool.UiArtifact;
import com.ruoyi.system.tool.UiArtifactNames;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeSearchToolCallbackTest
{
    @Test
    void hitsDeclareKbReferencesArtifactAndKeepModelText()
    {
        KnowledgeSearchPort search = mock(KnowledgeSearchPort.class);
        SearchHit hit = new SearchHit("2", "3", "4", "年假 5 天", 0.9,
                List.of(), Map.of("docName", "手册"));
        when(search.search(any(), any())).thenReturn(
                new SearchResponse(List.of(hit), SearchMode.BASIC, List.of(), Map.of()));

        KnowledgeSearchToolCallback cb = new KnowledgeSearchToolCallback(
                List.of("2"), search, InvocationContext.system("test"));
        String result = cb.call("{\"query\":\"请假\"}");

        assertTrue(result.startsWith("[1] 《手册》"));
        assertTrue(result.contains("年假 5 天"));
        List<UiArtifact> arts = cb.lastArtifacts();
        assertEquals(1, arts.size());
        assertEquals(UiArtifactNames.KB_REFERENCES, arts.get(0).name());
        assertEquals(2, arts.get(0).schemaVersion());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) arts.get(0).payload();
        assertEquals(List.of("请假"), payload.get("queries"));
        assertEquals(1, payload.get("fileCount"));
        assertNull(cb.lastArtifacts(), "并行线程池复用前必须 take-and-clear,避免串到下次调用");
    }

    @Test
    void emptyHitsDoNotDeclareArtifact()
    {
        KnowledgeSearchPort search = mock(KnowledgeSearchPort.class);
        when(search.search(any(), any())).thenReturn(
                new SearchResponse(List.of(), SearchMode.BASIC, List.of(), Map.of()));

        KnowledgeSearchToolCallback cb = new KnowledgeSearchToolCallback(
                List.of("2"), search, InvocationContext.system("test"));
        cb.call("{\"query\":\"无\"}");

        List<UiArtifact> arts = cb.lastArtifacts();
        assertTrue(arts == null || arts.isEmpty());
    }

    @Test
    void noKnowledgeBaseDoesNotDeclareArtifact()
    {
        KnowledgeSearchToolCallback cb = new KnowledgeSearchToolCallback(
                List.of(), mock(KnowledgeSearchPort.class), InvocationContext.system("test"));
        cb.call("{\"query\":\"请假\"}");
        assertNull(cb.lastArtifacts());
    }
}
