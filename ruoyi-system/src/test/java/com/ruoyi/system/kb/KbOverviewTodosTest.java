package com.ruoyi.system.kb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.ruoyi.system.domain.vo.KbWorkbenchItem;

class KbOverviewTodosTest
{
    @Test
    void failedDocsHighPriority()
    {
        KbWorkbenchItem i = base();
        i.setDocCount(3);
        i.setFailedCount(1);
        i.setReadyCount(2);
        i.setStatus("0");
        KbHealthCalculator.apply(i);
        List<Map<String, Object>> todos = KbOverviewTodos.build(i, Map.of(
            "available", true, "graphEnabled", "1", "entityCount", 5));
        assertTrue(todos.stream().anyMatch(t -> "FAILED_DOCS".equals(t.get("code"))));
        assertEquals("open_content_failed", todos.stream()
            .filter(t -> "FAILED_DOCS".equals(t.get("code")))
            .findFirst().orElseThrow().get("action"));
    }

    @Test
    void emptySuggestsAddContent()
    {
        KbWorkbenchItem i = base();
        i.setDocCount(0);
        KbHealthCalculator.apply(i);
        List<Map<String, Object>> todos = KbOverviewTodos.build(i, null);
        assertTrue(todos.stream().anyMatch(t -> "EMPTY".equals(t.get("code"))
            && "add_content".equals(t.get("action"))));
    }

    private static KbWorkbenchItem base()
    {
        KbWorkbenchItem i = new KbWorkbenchItem();
        i.setKbId(1L);
        i.setStatus("0");
        i.setCreateUserId(1L);
        i.setProcessingCount(0);
        i.setFailedCount(0);
        i.setReadyCount(0);
        return i;
    }
}
