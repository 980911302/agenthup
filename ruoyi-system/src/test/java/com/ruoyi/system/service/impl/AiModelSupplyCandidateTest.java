package com.ruoyi.system.service.impl;

import com.ruoyi.system.domain.AiChannel;
import com.ruoyi.system.domain.AiModel;
import com.ruoyi.system.domain.AiModelChannel;
import com.ruoyi.system.domain.vo.SupplyCandidateResult;
import com.ruoyi.system.mapper.AiChannelMapper;
import com.ruoyi.system.mapper.AiModelChannelMapper;
import com.ruoyi.system.mapper.AiModelMapper;
import com.ruoyi.system.mapper.AiUpstreamModelMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiModelSupplyCandidateTest
{
    private AiUpstreamModelMapper upstreamMapper;
    private AiChannelMapper channelMapper;
    private AiModelChannelMapper bindingMapper;
    private AiModelSupplyServiceImpl service;

    @BeforeEach
    void setUp()
    {
        upstreamMapper = mock(AiUpstreamModelMapper.class);
        channelMapper = mock(AiChannelMapper.class);
        bindingMapper = mock(AiModelChannelMapper.class);
        AiModelMapper modelMapper = mock(AiModelMapper.class);
        AiModel model = new AiModel();
        model.setModelId(7L);
        model.setModelCode("gpt-4o");
        when(modelMapper.selectAiModelById(7L)).thenReturn(model);
        service = new AiModelSupplyServiceImpl();
        ReflectionTestUtils.setField(service, "aiModelMapper", modelMapper);
        ReflectionTestUtils.setField(service, "aiChannelMapper", channelMapper);
        ReflectionTestUtils.setField(service, "aiModelChannelMapper", bindingMapper);
        ReflectionTestUtils.setField(service, "aiUpstreamModelMapper", upstreamMapper);
    }

    @Test
    void listCandidateChannels_onlyKeepsUnboundChannelsThatOfferThisModel()
    {
        arrangeUnbound(channel(1L), channel(2L));
        when(upstreamMapper.selectChannelIdsByUpstreamModelId("gpt-4o")).thenReturn(List.of(1L));
        when(upstreamMapper.countByChannelIds(any())).thenReturn(countRows(Map.of(1L, 5L, 2L, 3L)));

        SupplyCandidateResult result = service.listCandidateChannels(7L);

        assertEquals(1, result.getCandidates().size());
        assertEquals(1L, result.getCandidates().get(0).getChannelId());
        assertEquals(1, result.getExcluded().size());
        assertEquals("missing_model", result.getExcluded().get(0).getReasonType());
    }

    @Test
    void listCandidateChannels_skipsAlreadyBoundChannelEvenIfCatalogHasModel()
    {
        AiModelChannel bound = new AiModelChannel();
        bound.setChannelId(1L);
        when(bindingMapper.selectAiModelChannelList(any())).thenReturn(List.of(bound));
        when(channelMapper.selectAiChannelList(any())).thenReturn(List.of(channel(1L), channel(2L)));
        when(upstreamMapper.selectChannelIdsByUpstreamModelId("gpt-4o")).thenReturn(List.of(1L, 2L));
        when(upstreamMapper.countByChannelIds(any())).thenReturn(countRows(Map.of(2L, 2L)));

        SupplyCandidateResult result = service.listCandidateChannels(7L);

        assertEquals(1, result.getCandidates().size());
        assertEquals(2L, result.getCandidates().get(0).getChannelId());
    }

    @Test
    void listCandidateChannels_neverSynced()
    {
        arrangeUnbound(channel(3L));
        when(upstreamMapper.selectChannelIdsByUpstreamModelId("gpt-4o")).thenReturn(List.of());
        when(upstreamMapper.countByChannelIds(any())).thenReturn(List.of());

        assertEquals("not_synced", service.listCandidateChannels(7L).getExcluded().get(0).getReasonType());
        assertTrue(service.listCandidateChannels(7L).getCandidates().isEmpty());
    }

    private void arrangeUnbound(AiChannel... channels)
    {
        when(bindingMapper.selectAiModelChannelList(any())).thenReturn(List.of());
        when(channelMapper.selectAiChannelList(any())).thenReturn(List.of(channels));
    }

    private static AiChannel channel(Long id)
    {
        AiChannel c = new AiChannel();
        c.setChannelId(id);
        c.setChannelName("渠道" + id);
        c.setChannelCode("CH" + id);
        return c;
    }

    private static List<Map<String, Object>> countRows(Map<Long, Long> counts)
    {
        List<Map<String, Object>> rows = new ArrayList<>();
        counts.forEach((id, count) -> {
            Map<String, Object> row = new HashMap<>();
            row.put("channelId", id);
            row.put("cnt", count);
            rows.add(row);
        });
        return rows;
    }
}
