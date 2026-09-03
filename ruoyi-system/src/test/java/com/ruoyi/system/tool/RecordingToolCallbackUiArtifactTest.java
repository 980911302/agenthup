package com.ruoyi.system.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.system.ai.memory.ChatMessageRecorder;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecordingToolCallbackUiArtifactTest
{
    @Test
    void knownArtifactEmittedAfterToolEnd()
    {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("query", "请假");
        ArtifactStub tool = new ArtifactStub("searchKnowledge", "[1] 《手册》",
                List.of(UiArtifact.kbReferences(payload)));
        List<String> events = invoke(tool);

        assertEquals("tool_start", JSON.parseObject(events.get(0)).getString("type"));
        assertEquals("tool_end", JSON.parseObject(events.get(1)).getString("type"));
        JSONObject ui = JSON.parseObject(events.get(2));
        assertEquals("ui", ui.getString("type"));
        assertEquals(UiArtifactNames.KB_REFERENCES, ui.getString("name"));
        assertEquals(2, ui.getIntValue("schemaVersion"));
        assertTrue(ui.getString("eventId").endsWith(":" + UiArtifactNames.KB_REFERENCES));
        assertEquals(ui.getString("eventId"), ui.getString("stepId"));
        assertEquals(JSON.parseObject(events.get(1)).getString("stepId"), ui.getString("parentStepId"));
        assertEquals("请假", ui.getJSONObject("payload").getString("query"));
        assertEquals("owner-a", ui.getString("owner"));
    }

    @Test
    void unknownNameIsDropped()
    {
        ArtifactStub tool = new ArtifactStub("echo", "ok",
                List.of(new UiArtifact("not.registered", 1, Map.of("x", 1))));
        List<String> events = invoke(tool);
        assertEquals(2, events.size());
        assertEquals("tool_end", JSON.parseObject(events.get(1)).getString("type"));
    }

    @Test
    void oversizedPayloadIsDroppedAndToolStillSucceeds()
    {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("blob", "y".repeat(UiArtifactNames.spec(UiArtifactNames.KB_REFERENCES).maxPayloadChars() + 8));
        ArtifactStub tool = new ArtifactStub("searchKnowledge", "ok",
                List.of(UiArtifact.kbReferences(payload)));
        List<String> events = invoke(tool);
        assertEquals(2, events.size(), "超限产物不得进总线");
        assertEquals("ok", invokeResult(tool));
    }

    @Test
    void uiEmitFailureDoesNotFailTool()
    {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("query", "请假");
        ArtifactStub tool = new ArtifactStub("searchKnowledge", "[1] 《手册》",
                List.of(UiArtifact.kbReferences(payload)));
        List<String> events = new ArrayList<>();
        RecordingToolCallback cb = new RecordingToolCallback(
                tool, "s1", 1L, null, new SilentRecorder(), event -> {
                    events.add(event);
                    if (event.contains("\"type\":\"ui\""))
                    {
                        throw new IllegalStateException("bus down");
                    }
                }, "owner-a");
        assertEquals("[1] 《手册》", cb.call("{}"));
        assertEquals("tool_end", JSON.parseObject(events.get(1)).getString("type"));
    }

    @Test
    void unawareToolEmitsNoUiEvent()
    {
        ToolCallback tool = new UnawareStub();
        List<String> events = invoke(tool);
        assertEquals(2, events.size());
        assertNull(events.stream().map(JSON::parseObject)
                .filter(o -> "ui".equals(o.getString("type")))
                .findFirst().orElse(null));
    }

    @Test
    void attachmentIsAlsoExposedAsWorkspaceChange()
    {
        List<String> events = invoke(new AttachmentStub());

        JSONObject ui = events.stream().map(JSON::parseObject)
                .filter(o -> UiArtifactNames.WORKSPACE_CHANGES.equals(o.getString("name")))
                .findFirst().orElseThrow();
        JSONObject payload = ui.getJSONObject("payload");
        assertEquals(1, payload.getIntValue("fileCount"));
        assertEquals("CREATE", payload.getJSONArray("files").getJSONObject(0).getString("operation"));
        assertEquals("outputs/chart.png", payload.getJSONArray("files").getJSONObject(0).getString("path"));
    }

    private static List<String> invoke(ToolCallback tool)
    {
        List<String> events = new ArrayList<>();
        RecordingToolCallback cb = new RecordingToolCallback(
                tool, "s1", 1L, null, new SilentRecorder(), events::add, "owner-a");
        cb.call("{}");
        return events;
    }

    private static String invokeResult(ToolCallback tool)
    {
        RecordingToolCallback cb = new RecordingToolCallback(
                tool, "s1", 1L, null, new SilentRecorder(), json -> { }, "owner-a");
        return cb.call("{}");
    }

    static final class ArtifactStub implements ToolCallback, UiArtifactAware
    {
        private final String name;
        private final String result;
        private final List<UiArtifact> artifacts;

        ArtifactStub(String name, String result, List<UiArtifact> artifacts)
        {
            this.name = name;
            this.result = result;
            this.artifacts = artifacts;
        }

        @Override
        public ToolDefinition getToolDefinition()
        {
            return ToolDefinition.builder().name(name).description("stub").inputSchema("{}").build();
        }

        @Override
        public String call(String toolInput)
        {
            return result;
        }

        @Override
        public List<UiArtifact> lastArtifacts()
        {
            return artifacts;
        }
    }

    static final class UnawareStub implements ToolCallback
    {
        @Override
        public ToolDefinition getToolDefinition()
        {
            return ToolDefinition.builder().name("echo").description("stub").inputSchema("{}").build();
        }

        @Override
        public String call(String toolInput)
        {
            return "ok";
        }
    }

    static final class AttachmentStub implements ToolCallback, AttachmentAware
    {
        @Override
        public ToolDefinition getToolDefinition()
        {
            return ToolDefinition.builder().name("generateImage").description("stub").inputSchema("{}").build();
        }

        @Override
        public String call(String toolInput)
        {
            return "generated";
        }

        @Override
        public List<ToolAttachment> lastAttachments()
        {
            return List.of(new ToolAttachment("image", "outputs/chart.png",
                    "chart.png", 128L, "image/png"));
        }
    }

    static final class SilentRecorder extends ChatMessageRecorder
    {
        @Override
        public long recordToolCall(String conversationId, String sessionId, Long agentId, Long subAgentId,
                                   String toolName, String args, String result,
                                   String toolSource, long durationMs, boolean ok,
                                   List<ToolAttachment> attachments, String toolCallId,
                                   String runId, String stepId, String parentStepId,
                                   String contextText)
        {
            return 1L;
        }
    }
}
