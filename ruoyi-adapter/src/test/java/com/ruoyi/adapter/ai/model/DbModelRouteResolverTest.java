package com.ruoyi.adapter.ai.model;

import com.ruoyi.ai.contract.core.InvocationContext;
import com.ruoyi.ai.contract.model.ModelCapability;
import com.ruoyi.ai.contract.model.ModelRequest;
import com.ruoyi.system.domain.AiChannel;
import com.ruoyi.system.domain.AiModel;
import com.ruoyi.system.domain.AiModelChannel;
import com.ruoyi.system.service.IAiChannelService;
import com.ruoyi.system.service.IAiModelService;
import com.ruoyi.system.service.IAiModelSupplyService;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DbModelRouteResolverTest
{
    @Test
    void resolvesLogicalModelToActiveWeightedSupply()
    {
        IAiModelService models = mock(IAiModelService.class);
        IAiModelSupplyService supplies = mock(IAiModelSupplyService.class);
        IAiChannelService channels = mock(IAiChannelService.class);
        AiModel model = new AiModel();
        model.setModelId(9L);
        model.setModelCode("chat-main");
        model.setModelType("CHAT");
        model.setStatus("0");
        model.setContextWindow(32000);
        AiModelChannel supply = new AiModelChannel();
        supply.setChannelId(2L);
        supply.setModelName("upstream-chat");
        supply.setStatus("0");
        supply.setWeight(100);
        AiChannel channel = new AiChannel();
        channel.setChannelId(2L);
        channel.setStatus("0");
        when(models.selectByModelCode("chat-main")).thenReturn(model);
        when(supplies.listByModelId(9L)).thenReturn(List.of(supply));
        when(channels.selectAiChannelById(2L)).thenReturn(channel);

        var resolved = new DbModelRouteResolver(models, supplies, channels).resolve(
                new ModelRequest("chat-main", ModelCapability.CHAT, null),
                InvocationContext.system("trace"));

        assertEquals("channel:2", resolved.providerId());
        assertEquals("upstream-chat", resolved.model());
        assertEquals(32000, resolved.capabilities().contextWindow());
    }
}
