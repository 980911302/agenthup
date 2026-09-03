package com.ruoyi.system.ai.memory;

import com.ruoyi.system.domain.AiAgent;
import com.ruoyi.system.domain.AiModel;
import com.ruoyi.system.mapper.AiAgentMapper;
import com.ruoyi.system.mapper.AiModelMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationVisionGateTest
{
    @Test
    void missingConversationOrAgentIsBlind()
    {
        ConversationVisionGate gate = new ConversationVisionGate();
        assertFalse(gate.visionEnabled(null));
        assertFalse(gate.visionEnabled("only-session"));
        assertFalse(gate.visionEnabled("sess:x"));
    }

    @Test
    void loadsModelFlagAndCaches()
    {
        AiAgentMapper agents = mock(AiAgentMapper.class);
        AiModelMapper models = mock(AiModelMapper.class);
        AiAgent agent = new AiAgent();
        agent.setModelCode("m1");
        AiModel model = new AiModel();
        model.setVisionEnabled("1");
        when(agents.selectAiAgentById(6L)).thenReturn(agent);
        when(models.selectAiModelByModelCode("m1")).thenReturn(model);

        ConversationVisionGate gate = new ConversationVisionGate();
        set(gate, "agentMapper", agents);
        set(gate, "modelMapper", models);

        assertTrue(gate.visionEnabled("sess-1:6"));
        assertTrue(gate.visionEnabled("sess-1:6"));
        verify(agents, times(1)).selectAiAgentById(6L);
    }

    @Test
    void nullModelIsBlind()
    {
        AiAgentMapper agents = mock(AiAgentMapper.class);
        AiModelMapper models = mock(AiModelMapper.class);
        when(agents.selectAiAgentById(1L)).thenReturn(null);
        ConversationVisionGate gate = new ConversationVisionGate();
        set(gate, "agentMapper", agents);
        set(gate, "modelMapper", models);
        assertFalse(gate.visionEnabled("s:1"));
    }

    private static void set(Object target, String name, Object value)
    {
        try
        {
            ChatMessageMapperTestSupport.setField(target, name, value);
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }
}
