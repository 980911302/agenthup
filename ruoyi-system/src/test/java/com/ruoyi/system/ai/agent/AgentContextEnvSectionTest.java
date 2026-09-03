package com.ruoyi.system.ai.agent;

import com.ruoyi.system.tool.AiToolProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * system prompt 末尾的环境段必须对所有会话逐字相同。
 * messages 排在 tools 前面,末尾一分叉后面整段工具定义都会 miss。
 */
class AgentContextEnvSectionTest
{
    private AgentContextFactory newFactory(Path cwd)
    {
        AiToolProperties props = new AiToolProperties();
        props.setCwd(cwd.toString());
        props.setWorkspaceRoot(cwd.resolve("sandbox").toString());
        props.setWorkspacePerSession(true);
        AgentContextFactory factory = new AgentContextFactory();
        ReflectionTestUtils.setField(factory, "aiToolProperties", props);
        return factory;
    }

    @Test
    void 只含全局固定工作目录不含会话身份(@TempDir Path root)
    {
        String env = newFactory(root).buildEnvSection();

        assertTrue(env.contains("会话工作区"), env);
        assertFalse(env.contains(root.toAbsolutePath().normalize().toString()),
                "环境段不能写绝对路径,否则模型会写到进程目录: " + env);
        assertFalse(env.contains("sessionId") || env.contains("会话 ID"), env);
        assertFalse(env.contains("工作目录:"), env);
    }

    @Test
    void 不含每轮都变的时间(@TempDir Path root)
    {
        String env = newFactory(root).buildEnvSection();
        assertFalse(env.contains("当前时间"), env);
        assertFalse(env.matches("(?s).*\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}.*"),
                "环境段不能含时间戳: " + env);
    }

    @Test
    void 多次装配文案完全相同(@TempDir Path root)
    {
        AgentContextFactory factory = newFactory(root);
        assertEquals(factory.buildEnvSection(), factory.buildEnvSection());
    }
}
