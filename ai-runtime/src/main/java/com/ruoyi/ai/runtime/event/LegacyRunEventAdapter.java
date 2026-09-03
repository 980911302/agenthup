package com.ruoyi.ai.runtime.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ruoyi.ai.contract.event.RunEventEnvelope;
import com.ruoyi.ai.contract.event.RunEventTypes;
import java.time.Instant;

/**
 * 将现有 ChatEventJson 转成标准 v1 信封。
 *
 * <p>迁移期由事件总线同时投递 legacy 与 v1；业务生产者无需一次性改写。</p>
 */
public final class LegacyRunEventAdapter
{
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private LegacyRunEventAdapter() { }

    public static String toStandardJson(String runId, long sequence, long timestamp,
                                        String legacyEventJson)
    {
        try
        {
            JsonNode parsed = MAPPER.readTree(legacyEventJson);
            ObjectNode data = parsed != null && parsed.isObject()
                    ? ((ObjectNode) parsed).deepCopy() : MAPPER.createObjectNode();
            String legacyType = data.path("type").asText("");
            data.remove("type");
            if ("context_overflow_trimmed".equals(legacyType))
            {
                data.put("kind", "overflow_trimmed");
            }
            RunEventEnvelope event = new RunEventEnvelope(
                    RunEventEnvelope.SPEC_VERSION,
                    standardType(legacyType),
                    "evt-" + runId + "-" + sequence,
                    Instant.ofEpochMilli(timestamp),
                    "run/" + runId,
                    sequence,
                    data);
            return MAPPER.writeValueAsString(event);
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    static String standardType(String legacyType)
    {
        return switch (legacyType)
        {
            case "run_status" -> RunEventTypes.STATUS_CHANGED;
            case "text" -> RunEventTypes.TEXT_DELTA;
            case "reasoning" -> RunEventTypes.REASONING_DELTA;
            case "tool_start" -> RunEventTypes.TOOL_STARTED;
            case "tool_confirm_required" -> RunEventTypes.TOOL_CONFIRMATION_REQUIRED;
            case "tool_call_request" -> RunEventTypes.TOOL_CALL_REQUESTED;
            case "tool_end" -> RunEventTypes.TOOL_COMPLETED;
            case "agent_start" -> RunEventTypes.AGENT_STARTED;
            case "agent_end" -> RunEventTypes.AGENT_COMPLETED;
            case "ui" -> RunEventTypes.UI_PUBLISHED;
            case "context_cleaned", "context_overflow_trimmed" -> RunEventTypes.CONTEXT_COMPACTED;
            case "done" -> RunEventTypes.COMPLETED;
            case "error" -> RunEventTypes.FAILED;
            case "cancelled" -> RunEventTypes.CANCELLED;
            case "interrupted" -> RunEventTypes.INTERRUPTED;
            default -> "ai.run.legacy." + (legacyType == null || legacyType.isBlank()
                    ? "unknown" : legacyType.replace('_', '.'));
        };
    }
}
