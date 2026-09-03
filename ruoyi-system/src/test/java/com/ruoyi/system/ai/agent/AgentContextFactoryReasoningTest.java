package com.ruoyi.system.ai.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatOptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 锁定模型管理 reasoning_enabled 到实际 OpenAI 请求 options 的映射。 */
class AgentContextFactoryReasoningTest
{
    @Test
    void applyReasoningEnabled_setsStandardReasoningEffortWhenEnabled()
    {
        OpenAiChatOptions options = OpenAiChatOptions.builder().build();

        AgentContextFactory.applyReasoningEnabled(options, true);

        assertEquals(AgentContextFactory.DEFAULT_REASONING_EFFORT, options.getReasoningEffort());
    }

    @Test
    void applyReasoningEnabled_clearsCopiedDefaultWhenDisabled()
    {
        OpenAiChatOptions options = OpenAiChatOptions.builder().reasoningEffort("high").build();

        AgentContextFactory.applyReasoningEnabled(options, false);

        assertNull(options.getReasoningEffort());
    }
}
