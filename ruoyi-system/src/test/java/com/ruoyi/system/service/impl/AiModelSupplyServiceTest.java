package com.ruoyi.system.service.impl;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.AiChannel;
import com.ruoyi.system.domain.AiModel;
import com.ruoyi.system.domain.AiModelChannel;
import com.ruoyi.system.domain.AiUpstreamModel;
import com.ruoyi.system.mapper.AiChannelMapper;
import com.ruoyi.system.mapper.AiModelChannelMapper;
import com.ruoyi.system.mapper.AiModelMapper;
import com.ruoyi.system.mapper.AiUpstreamModelMapper;
import com.ruoyi.system.service.IAiModelChannelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiModelSupplyServiceTest
{
    private AiModelMapper modelMapper;
    private AiChannelMapper channelMapper;
    private AiModelChannelMapper bindingMapper;
    private AiUpstreamModelMapper upstreamMapper;
    private IAiModelChannelService bindingService;
    private AiModelSupplyServiceImpl service;

    @BeforeEach
    void setUp()
    {
        modelMapper = mock(AiModelMapper.class);
        channelMapper = mock(AiChannelMapper.class);
        bindingMapper = mock(AiModelChannelMapper.class);
        upstreamMapper = mock(AiUpstreamModelMapper.class);
        bindingService = mock(IAiModelChannelService.class);
        service = new AiModelSupplyServiceImpl();
        ReflectionTestUtils.setField(service, "aiModelMapper", modelMapper);
        ReflectionTestUtils.setField(service, "aiChannelMapper", channelMapper);
        ReflectionTestUtils.setField(service, "aiModelChannelMapper", bindingMapper);
        ReflectionTestUtils.setField(service, "aiUpstreamModelMapper", upstreamMapper);
        ReflectionTestUtils.setField(service, "aiModelChannelService", bindingService);
    }

    @Test
    void addSupply_acceptsChannelModelDifferentFromPlatformModelCode()
    {
        when(modelMapper.selectAiModelById(7L)).thenReturn(model(7L, "logical-chat"));
        when(channelMapper.selectAiChannelById(1L)).thenReturn(channel(1L));
        when(upstreamMapper.selectByChannelAndModelId(1L, "provider-deployment-v2"))
            .thenReturn(upstream(1L, "provider-deployment-v2"));
        when(bindingService.saveBinding(any(), any())).thenReturn(1);

        AiModelChannel input = new AiModelChannel();
        input.setModelId(7L);
        input.setChannelId(1L);
        input.setModelName("provider-deployment-v2");

        assertEquals(1, service.addSupply(input, "admin"));
        verify(bindingService).saveBinding(org.mockito.ArgumentMatchers.argThat(binding ->
            "provider-deployment-v2".equals(binding.getModelName())), org.mockito.ArgumentMatchers.eq("admin"));
    }

    @Test
    void addSupply_rejectsModelOutsideChannelModelList()
    {
        when(modelMapper.selectAiModelById(7L)).thenReturn(model(7L, "logical-chat"));
        when(channelMapper.selectAiChannelById(1L)).thenReturn(channel(1L));
        AiModelChannel input = new AiModelChannel();
        input.setModelId(7L);
        input.setChannelId(1L);
        input.setModelName("not-in-list");

        ServiceException error = assertThrows(ServiceException.class,
            () -> service.addSupply(input, "admin"));
        assertTrue(error.getMessage().contains("渠道模型"));
        verify(bindingService, never()).saveBinding(any(), any());
    }

    @Test
    void listByModelId_marksMissingWithoutDeletingSupply()
    {
        when(modelMapper.selectAiModelById(7L)).thenReturn(model(7L, "logical-chat"));
        AiModelChannel existing = new AiModelChannel();
        existing.setChannelId(1L);
        existing.setModelName("old-model");
        when(bindingMapper.selectAiModelChannelList(any())).thenReturn(List.of(existing));
        when(upstreamMapper.selectByChannelIds(any())).thenReturn(List.of(upstream(1L, "new-model")));

        List<AiModelChannel> result = service.listByModelId(7L);

        assertTrue(result.get(0).getUpstreamMissing());
        verify(bindingMapper, never()).deleteAiModelChannelByIds(any());
    }

    private static AiModel model(Long id, String code)
    {
        AiModel model = new AiModel();
        model.setModelId(id);
        model.setModelCode(code);
        return model;
    }

    private static AiChannel channel(Long id)
    {
        AiChannel channel = new AiChannel();
        channel.setChannelId(id);
        channel.setChannelName("渠道" + id);
        channel.setStatus("0");
        return channel;
    }

    private static AiUpstreamModel upstream(Long channelId, String modelId)
    {
        AiUpstreamModel model = new AiUpstreamModel();
        model.setChannelId(channelId);
        model.setUpstreamModelId(modelId);
        return model;
    }
}
