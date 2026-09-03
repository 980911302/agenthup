package com.ruoyi.system.tool;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.system.ai.memory.ChatMessageRecorder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * bash 非零退出/危险命令必须记失败,且原文仍交给模型。
 */
class ShellToolOutcomeTest
{
    @TempDir
    Path workspace;

    @Test
    void bash_exit3_recordsFailureAndKeepsOutput()
    {
        Recorded rec = invokeBash("{\"command\":\"exit 3\"}");

        assertTrue(rec.modelResult.contains("exited with code 3"), rec.modelResult);
        assertFalse(rec.recorder.ok, "非零退出必须记失败");
        assertEquals("1", rec.recorder.toolSuccess);
        assertTrue(rec.recorder.result.contains("exited with code 3"), rec.recorder.result);
        assertFalse(rec.toolEndOk());
        assertFalse(rec.modelResult.startsWith("ERROR:"), "不得改抛异常把正文换成 ERROR:");
    }

    @Test
    void bash_exit0_recordsSuccess()
    {
        Recorded rec = invokeBash("{\"command\":\"exit 0\"}");

        assertTrue(rec.modelResult.contains("exited with code 0"), rec.modelResult);
        assertTrue(rec.recorder.ok);
        assertEquals("0", rec.recorder.toolSuccess);
        assertTrue(rec.toolEndOk());
    }

    @Test
    void bashPublishesFilesCreatedByCommand()
    {
        ToolCallback callback = newShell().bashTool();
        String result = callback.call("{\"command\":\"printf hello > generated.txt\"}");

        assertTrue(result.contains("exited with code 0"), result);
        List<UiArtifact> artifacts = ((UiArtifactAware) callback).lastArtifacts();
        assertEquals(1, artifacts.size());
        JSONObject payload = JSON.parseObject(JSON.toJSONString(artifacts.get(0).payload()));
        assertEquals("generated.txt", payload.getJSONArray("files").getJSONObject(0).getString("path"));
        assertEquals("CREATE", payload.getJSONArray("files").getJSONObject(0).getString("operation"));
    }

    @Test
    void bash_dangerousCommand_recordsFailure()
    {
        Recorded rec = invokeBash("{\"command\":\"rm -rf /\"}");

        assertTrue(rec.modelResult.contains("拒绝执行危险命令"), rec.modelResult);
        assertFalse(rec.recorder.ok);
        assertEquals("1", rec.recorder.toolSuccess);
        assertFalse(rec.toolEndOk());
    }

    @Test
    void bash_missingWorkdir_recordsFailure()
    {
        Recorded rec = invokeBash("{\"command\":\"echo hi\",\"workdir\":\"/no/such/pi-workdir\"}");

        assertTrue(rec.modelResult.contains("does not exist") || rec.modelResult.contains("执行失败"), rec.modelResult);
        assertFalse(rec.recorder.ok);
        assertEquals("1", rec.recorder.toolSuccess);
        assertFalse(rec.toolEndOk());
    }

    @Test
    void unawareTool_noException_stillSuccess()
    {
        Recorded rec = invoke(new UnawareStub("echo", "hello"), "{}");

        assertEquals("hello", rec.modelResult);
        assertTrue(rec.recorder.ok);
        assertEquals("0", rec.recorder.toolSuccess);
        assertTrue(rec.toolEndOk());
    }

    @Test
    void lastCallOkNull_meansNoOpinion_stillSuccess()
    {
        Recorded rec = invoke(new StubTool("echo", "hello", null), "{}");

        assertTrue(rec.recorder.ok);
        assertEquals("0", rec.recorder.toolSuccess);
    }

    @Test
    void lastCallOkFalse_recordsFailureAndKeepsOriginalText()
    {
        Recorded rec = invoke(new StubTool("echo", "exitCode=3\nboom", Boolean.FALSE), "{}");

        assertEquals("exitCode=3\nboom", rec.modelResult);
        assertFalse(rec.recorder.ok);
        assertEquals("1", rec.recorder.toolSuccess);
        assertEquals("exitCode=3\nboom", rec.recorder.result);
        assertFalse(rec.toolEndOk());
    }

    private Recorded invokeBash(String args)
    {
        return invoke(newShell().bashTool(), args);
    }

    private Recorded invoke(ToolCallback tool, String args)
    {
        CapturingRecorder recorder = new CapturingRecorder();
        List<String> events = new ArrayList<>();
        RecordingToolCallback cb = new RecordingToolCallback(
                tool, "s1", 1L, null, recorder, events::add, "agent");
        String modelResult = cb.call(args);
        return new Recorded(modelResult, recorder, events);
    }

    private ShellTool newShell()
    {
        AiToolProperties props = new AiToolProperties();
        props.setCwd(workspace.toAbsolutePath().toString());
        props.setShellEnabled(true);
        props.setShellTimeoutMs(10_000L);
        return new ShellTool(props);
    }

    private record Recorded(String modelResult, CapturingRecorder recorder, List<String> events)
    {
        boolean toolEndOk()
        {
            for (String e : events)
            {
                JSONObject o = JSON.parseObject(e);
                if ("tool_end".equals(o.getString("type")))
                {
                    return o.getBooleanValue("ok");
                }
            }
            throw new AssertionError("没有 tool_end 事件: " + events);
        }
    }

    /** 记录 ok / toolSuccess 映射,不碰真实库。 */
    static final class CapturingRecorder extends ChatMessageRecorder
    {
        boolean ok;

        String toolSuccess;

        String result;

        @Override
        public long recordToolCall(String conversationId, String sessionId, Long agentId, Long subAgentId,
                                   String toolName, String args, String result,
                                   String toolSource, long durationMs, boolean ok,
                                   List<ToolAttachment> attachments, String toolCallId,
                                   String runId, String stepId, String parentStepId,
                                   String contextText)
        {
            this.ok = ok;
            this.result = result;
            this.toolSuccess = ok ? "0" : "1";
            return 1L;
        }
    }

    static final class StubTool implements ToolCallback, ToolOutcomeAware
    {
        private final String name;

        private final String result;

        private final Boolean reportedOk;

        StubTool(String name, String result, Boolean reportedOk)
        {
            this.name = name;
            this.result = result;
            this.reportedOk = reportedOk;
        }

        @Override
        public ToolDefinition getToolDefinition()
        {
            return ToolDefinition.builder()
                    .name(name)
                    .description("stub")
                    .inputSchema("{}")
                    .build();
        }

        @Override
        public String call(String toolInput)
        {
            return result;
        }

        @Override
        public Boolean lastCallOk()
        {
            return reportedOk;
        }
    }

    /** 不实现 ToolOutcomeAware,行为应与改造前一致。 */
    static final class UnawareStub implements ToolCallback
    {
        private final String name;

        private final String result;

        UnawareStub(String name, String result)
        {
            this.name = name;
            this.result = result;
        }

        @Override
        public ToolDefinition getToolDefinition()
        {
            return ToolDefinition.builder()
                    .name(name)
                    .description("stub")
                    .inputSchema("{}")
                    .build();
        }

        @Override
        public String call(String toolInput)
        {
            return result;
        }
    }
}
