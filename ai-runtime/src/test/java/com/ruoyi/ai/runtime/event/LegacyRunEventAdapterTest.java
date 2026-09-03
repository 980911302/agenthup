package com.ruoyi.ai.runtime.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.ai.contract.event.RunEventTypes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class LegacyRunEventAdapterTest
{
    @Test
    void convertsLegacyToolEventToVersionedEnvelope() throws Exception
    {
        String json = LegacyRunEventAdapter.toStandardJson("run-1", 7, 1_700_000_000_000L,
                "{\"type\":\"tool_end\",\"stepId\":\"s1\",\"ok\":true}");
        assertNotNull(json);
        JsonNode event = new ObjectMapper().findAndRegisterModules().readTree(json);
        assertEquals("1.0", event.path("specversion").asText());
        assertEquals(RunEventTypes.TOOL_COMPLETED, event.path("type").asText());
        assertEquals(7, event.path("sequence").asLong());
        assertEquals("s1", event.path("data").path("stepId").asText());
    }

    @Test
    void convertsToolCallRequest() throws Exception
    {
        String json = LegacyRunEventAdapter.toStandardJson("run-1", 3, 1_700_000_000_000L,
                "{\"type\":\"tool_call_request\",\"callId\":\"c1\",\"name\":\"ping\"}");
        assertNotNull(json);
        JsonNode event = new ObjectMapper().findAndRegisterModules().readTree(json);
        assertEquals(RunEventTypes.TOOL_CALL_REQUESTED, event.path("type").asText());
        assertEquals("c1", event.path("data").path("callId").asText());
    }
}
