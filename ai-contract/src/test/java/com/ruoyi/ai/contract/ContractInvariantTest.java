package com.ruoyi.ai.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ruoyi.ai.contract.event.RunEventEnvelope;
import com.ruoyi.ai.contract.event.RunEventTypes;
import com.ruoyi.ai.contract.kb.SearchMode;
import com.ruoyi.ai.contract.tool.ToolDescriptor;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContractInvariantTest
{
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void eventEnvelopeKeepsVersionAndSequenceInvariants()
    {
        ObjectNode data = mapper.createObjectNode().put("text", "ok");
        RunEventEnvelope event = new RunEventEnvelope("1.0", RunEventTypes.TEXT_DELTA,
                "evt-1", Instant.parse("2026-08-26T00:00:00Z"), "run/r1", 1, data);
        assertEquals(1, event.sequence());
        assertThrows(IllegalArgumentException.class, () -> new RunEventEnvelope(
                "2.0", RunEventTypes.TEXT_DELTA, "evt-2", Instant.now(), "run/r1", 1, data));
    }

    @Test
    void unknownSearchModeFallsBackToBasic()
    {
        assertEquals(SearchMode.BASIC, SearchMode.from("future-mode"));
        assertEquals(SearchMode.LOCAL, SearchMode.from("graph"));
        assertEquals(SearchMode.HYBRID, SearchMode.from("mix"));
    }

    @Test
    void toolDescriptorRequiresStableIdentity()
    {
        assertThrows(IllegalArgumentException.class,
                () -> new ToolDescriptor("", "1", null, null, null, null, null));
    }
}
