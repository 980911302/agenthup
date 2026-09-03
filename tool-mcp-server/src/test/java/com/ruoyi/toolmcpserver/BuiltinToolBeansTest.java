package com.ruoyi.toolmcpserver;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;

import com.ruoyi.system.tool.AiToolProperties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 纯单元测试(不启动 Spring):验证装配出的 8 个工具名与内置完全一致,且行为没退化。
 * 照抄 {@code ShellToolOutcomeTest} 的 {@code new Xxx(props)} 构造模式。
 */
class BuiltinToolBeansTest
{
    @TempDir
    Path workspace;

    @Test
    void exposesExactBuiltinToolNames()
    {
        List<ToolCallback> callbacks = new BuiltinToolBeans().execToolCallbacks(newProps());

        List<String> names = callbacks.stream()
                .map(cb -> cb.getToolDefinition().name())
                .toList();
        assertEquals(List.of(
                "bash", "read", "write", "edit", "grep", "find", "ls", "captureScreenshot"), names);
    }

    @Test
    void bashKeepsOutputContract()
    {
        List<ToolCallback> callbacks = new BuiltinToolBeans().execToolCallbacks(newProps());
        ToolCallback bash = callbacks.get(0);

        String ok = bash.call("{\"command\":\"echo hi\"}");
        assertTrue(ok.contains("Command exited with code 0"), ok);
        assertTrue(ok.contains("hi"), ok);

        // 危险命令必须原样拒绝(中文文案与内置一致)
        String danger = bash.call("{\"command\":\"rm -rf /\"}");
        assertTrue(danger.contains("拒绝执行危险命令"), danger);
    }

    private AiToolProperties newProps()
    {
        AiToolProperties props = new AiToolProperties();
        props.setCwd(workspace.toAbsolutePath().toString());
        props.setWorkspaceRoot(workspace.toAbsolutePath().toString());
        props.setWorkspacePerSession(true);
        props.setShellEnabled(true);
        props.setShellTimeoutMs(10_000L);
        return props;
    }
}