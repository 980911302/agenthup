package com.ruoyi.system.service.impl;

import com.ruoyi.system.ai.UpstreamModelClient;
import com.ruoyi.system.domain.*;
import com.ruoyi.system.domain.vo.UpstreamModelVo;
import com.ruoyi.system.mapper.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiModelImportListTest
{
    private AiUpstreamModelMapper upstreamMapper;
    private AiModelMapper modelMapper;
    private AiModelChannelMapper bindingMapper;
    private UpstreamModelClient client;
    private AiModelImportServiceImpl service;

    @BeforeEach void setUp()
    {
        upstreamMapper = mock(AiUpstreamModelMapper.class); modelMapper = mock(AiModelMapper.class);
        bindingMapper = mock(AiModelChannelMapper.class); client = mock(UpstreamModelClient.class);
        AiChannelMapper channelMapper = mock(AiChannelMapper.class); AiChannel channel = new AiChannel();
        channel.setChannelId(1L); when(channelMapper.selectAiChannelById(1L)).thenReturn(channel);
        service = new AiModelImportServiceImpl();
        ReflectionTestUtils.setField(service, "aiChannelMapper", channelMapper);
        ReflectionTestUtils.setField(service, "aiModelMapper", modelMapper);
        ReflectionTestUtils.setField(service, "aiModelChannelMapper", bindingMapper);
        ReflectionTestUtils.setField(service, "aiUpstreamModelMapper", upstreamMapper);
        ReflectionTestUtils.setField(service, "upstreamModelClient", client);
    }

    @Test void listUpstreamModels_readsFromTable_notFromUpstream()
    {
        when(upstreamMapper.selectList(any())).thenReturn(List.of(upstream("gpt-4o"), upstream("gpt-4o-mini")));
        when(modelMapper.selectAiModelList(any())).thenReturn(List.of()); when(bindingMapper.selectAiModelChannelList(any())).thenReturn(List.of());
        List<UpstreamModelVo> result = service.listUpstreamModels(1L);
        assertEquals(2, result.size()); verify(client, never()).fetchModels(any(), anyString());
        assertTrue(result.stream().allMatch(v -> AiModelImportServiceImpl.STATUS_NOT_IMPORTED.equals(v.getImportStatus())));
    }
    @Test void listUpstreamModels_marksChannelBound()
    {
        when(upstreamMapper.selectList(any())).thenReturn(List.of(upstream("gpt-4o")));
        AiModel model = new AiModel(); model.setModelId(7L); model.setModelCode("gpt-4o");
        AiModelChannel binding = new AiModelChannel(); binding.setModelId(7L); binding.setChannelId(1L);
        when(modelMapper.selectAiModelList(any())).thenReturn(List.of(model)); when(bindingMapper.selectAiModelChannelList(any())).thenReturn(List.of(binding));
        assertEquals(AiModelImportServiceImpl.STATUS_CHANNEL_BOUND, service.listUpstreamModels(1L).get(0).getImportStatus());
    }
    @Test void listUpstreamModels_emptyTable_returnsEmpty()
    {
        when(upstreamMapper.selectList(any())).thenReturn(List.of()); when(modelMapper.selectAiModelList(any())).thenReturn(List.of());
        when(bindingMapper.selectAiModelChannelList(any())).thenReturn(List.of()); assertTrue(service.listUpstreamModels(1L).isEmpty());
    }
    private static AiUpstreamModel upstream(String id) { AiUpstreamModel m = new AiUpstreamModel(); m.setChannelId(1L); m.setUpstreamModelId(id); m.setDisplayName(id); return m; }
}
