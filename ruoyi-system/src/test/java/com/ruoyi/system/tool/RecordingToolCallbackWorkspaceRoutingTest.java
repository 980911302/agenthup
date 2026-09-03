package com.ruoyi.system.tool;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.SyncMcpToolCallback;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import com.ruoyi.system.ai.memory.ChatMessageRecorder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MCP 远端执行工具的会话路由:开启 routing 时,RecordingToolCallback 在交给
 * SyncMcpToolCallback 的 args envelope 里注入 {@code _workspaceKey}。
 */
class RecordingToolCallbackWorkspaceRoutingTest
{
    @Test
    void routesWorkspaceKeyIntoMcpDelegateWhenEnabled()
    {
        SyncMcpToolCallback mcp = mcpEcho();
        RecordingToolCallback cb = new RecordingToolCallback(mcp, "sess-1", 1L, null,
                new SilentRecorder(), event -> { }, "owner-a")
                .withWorkspaceKey("sess-1")
                .withMcpWorkspaceRouting(true);

        String sent = cb.call("{\"command\":\"ls\"}");
        assertTrue(sent.contains("\"_workspaceKey\":\"sess-1\""), sent);
    }

    @Test
    void leavesInputUntouchedWhenRoutingOff()
    {
        SyncMcpToolCallback mcp = mcpEcho();
        RecordingToolCallback cb = new RecordingToolCallback(mcp, "sess-1", 1L, null,
                new SilentRecorder(), event -> { }, "owner-a")
                .withMcpWorkspaceRouting(false);

        String sent = cb.call("{\"command\":\"ls\"}");
        assertFalse(sent.contains("_workspaceKey"), sent);
    }

    @Test
    void leavesInputUntouchedForBuiltinDelegate()
    {
        ToolCallback builtin = mock(ToolCallback.class);
        ToolDefinition def = mock(ToolDefinition.class);
        when(def.name()).thenReturn("echo");
        when(builtin.getToolDefinition()).thenReturn(def);
        when(builtin.call(anyString())).thenAnswer(inv -> inv.getArgument(0));

        RecordingToolCallback cb = new RecordingToolCallback(builtin, "sess-1", 1L, null,
                new SilentRecorder(), event -> { }, "owner-a")
                .withMcpWorkspaceRouting(true);

        String sent = cb.call("{\"command\":\"ls\"}");
        assertFalse(sent.contains("_workspaceKey"), sent);
    }

    /** 空 args(模型没给任何参数)也要能注入,不能崩 */
    @Test
    void nullOrBlankInputStillGetsEnvelope()
    {
        SyncMcpToolCallback mcp = mcpEcho();
        RecordingToolCallback cb = new RecordingToolCallback(mcp, "sess-1", 1L, null,
                new SilentRecorder(), event -> { }, "owner-a")
                .withWorkspaceKey("sess-1")
                .withMcpWorkspaceRouting(true);

        String sent = cb.call("{}");
        assertTrue(sent.contains("\"_workspaceKey\":\"sess-1\""), sent);
    }

    /** 真空串走手工拼 envelope 分支,同样要带上 key 且是合法 JSON */
    @Test
    void emptyStringInputGetsEnvelope()
    {
        SyncMcpToolCallback mcp = mcpEcho();
        RecordingToolCallback cb = new RecordingToolCallback(mcp, "sess-1", 1L, null,
                new SilentRecorder(), event -> { }, "owner-a")
                .withWorkspaceKey("sess-1")
                .withMcpWorkspaceRouting(true);

        for (String input : new String[] { "", "   " })
        {
            String sent = cb.call(input);
            assertTrue(sent.contains("\"_workspaceKey\":\"sess-1\""), sent);
        }
    }

    private static SyncMcpToolCallback mcpEcho()
    {
        SyncMcpToolCallback m = mock(SyncMcpToolCallback.class);
        ToolDefinition def = mock(ToolDefinition.class);
        when(def.name()).thenReturn("bash");
        when(m.getToolDefinition()).thenReturn(def);
        when(m.call(anyString())).thenAnswer(inv -> inv.getArgument(0));
        return m;
    }

    /** 与 RecordingToolCallbackUiArtifactTest 同款的静默记账器 */
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