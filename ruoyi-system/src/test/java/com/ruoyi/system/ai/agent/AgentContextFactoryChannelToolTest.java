package com.ruoyi.system.ai.agent;

import com.ruoyi.system.ai.event.ChatEventSink;
import com.ruoyi.system.ai.memory.ChatMessageMapperTestSupport;
import com.ruoyi.system.tool.channel.ChannelToolBroker;
import com.ruoyi.system.tool.channel.ChannelToolDef;
import com.ruoyi.system.tool.channel.ChannelToolProperties;
import com.ruoyi.system.tool.channel.ChannelToolSchemas;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentContextFactoryChannelToolTest
{
    private AgentContextFactory factory;
    private ChannelToolBroker broker;
    private ChannelToolProperties props;

    @BeforeEach
    void setUp() throws Exception
    {
        factory = new AgentContextFactory();
        broker = mock(ChannelToolBroker.class);
        props = new ChannelToolProperties();
        props.overrideForTest(true, List.of(), 120, 16, 32);
        ChatMessageMapperTestSupport.setField(factory, "channelToolBroker", broker);
        ChatMessageMapperTestSupport.setField(factory, "channelToolProperties", props);
        ChatMessageMapperTestSupport.setField(factory, "recorder",
                mock(com.ruoyi.system.ai.memory.ChatMessageRecorder.class));
    }

    @Test
    void duplicateName_skipped()
    {
        ChannelToolDef ping = new ChannelToolDef("ping", "p", ChannelToolSchemas.DEFAULT_SCHEMA);
        AgentRunOverrides overrides = new AgentRunOverrides(null, null, List.of(ping));
        ToolCallback existing = mock(ToolCallback.class);
        when(existing.getToolDefinition()).thenReturn(
                DefaultToolDefinition.builder().name("ping").description("x").inputSchema("{}").build());
        List<ToolCallback> already = new ArrayList<>();
        already.add(existing);
        AgentContextFactory.OperatorHolder op = new AgentContextFactory.OperatorHolder("u", 1L, false);
        List<ToolCallback> added = factory.resolveChannelTools(
                overrides, "s1", "r1", 1L, null, ChatEventSink.noop(), op, already);
        assertTrue(added.isEmpty());
    }

    @Test
    void appendedAfterExisting()
    {
        ChannelToolDef ping = new ChannelToolDef("ping", "p", ChannelToolSchemas.DEFAULT_SCHEMA);
        AgentRunOverrides overrides = new AgentRunOverrides(null, null, List.of(ping));
        ToolCallback existing = mock(ToolCallback.class);
        when(existing.getToolDefinition()).thenReturn(
                DefaultToolDefinition.builder().name("bash").description("x").inputSchema("{}").build());
        List<ToolCallback> already = new ArrayList<>();
        already.add(existing);
        AgentContextFactory.OperatorHolder op = new AgentContextFactory.OperatorHolder("u", 1L, false);
        List<ToolCallback> added = factory.resolveChannelTools(
                overrides, "s1", "r1", 1L, null, ChatEventSink.noop(), op, already);
        assertEquals(1, added.size());
        already.addAll(added);
        assertEquals("bash", already.get(0).getToolDefinition().name());
        assertEquals("ping", already.get(already.size() - 1).getToolDefinition().name());
    }

    @Test
    void disabled_returnsEmpty()
    {
        props.overrideForTest(false, List.of(), 120, 16, 32);
        ChannelToolDef ping = new ChannelToolDef("ping", "p", ChannelToolSchemas.DEFAULT_SCHEMA);
        AgentRunOverrides overrides = new AgentRunOverrides(null, null, List.of(ping));
        AgentContextFactory.OperatorHolder op = new AgentContextFactory.OperatorHolder("u", 1L, false);
        assertTrue(factory.resolveChannelTools(
                overrides, "s1", "r1", 1L, null, ChatEventSink.noop(), op, List.of()).isEmpty());
    }
}
