package com.ruoyi.system.service.impl;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.EncryptUtils;
import com.ruoyi.system.ai.UpstreamModelClient;
import com.ruoyi.system.domain.AiChannel;
import com.ruoyi.system.domain.AiUpstreamModel;
import com.ruoyi.system.mapper.AiChannelMapper;
import com.ruoyi.system.mapper.AiUpstreamModelMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiUpstreamModelServiceTest
{
    private AiUpstreamModelMapper mapper;
    private AiChannelMapper channelMapper;
    private UpstreamModelClient client;
    private AiUpstreamModelServiceImpl service;

    @BeforeEach void setUp()
    {
        mapper = mock(AiUpstreamModelMapper.class); channelMapper = mock(AiChannelMapper.class);
        client = mock(UpstreamModelClient.class); EncryptUtils encrypt = mock(EncryptUtils.class);
        when(encrypt.decrypt(any())).thenReturn("plain"); service = new AiUpstreamModelServiceImpl();
        ReflectionTestUtils.setField(service, "mapper", mapper);
        ReflectionTestUtils.setField(service, "aiChannelMapper", channelMapper);
        ReflectionTestUtils.setField(service, "upstreamModelClient", client);
        ReflectionTestUtils.setField(service, "encryptUtils", encrypt);
    }

    @Test void sync_replacesWholeList()
    {
        when(channelMapper.selectAiChannelById(1L)).thenReturn(channel(1L, "0"));
        when(client.fetchModels(any(), anyString())).thenReturn(List.of(up("gpt-4o"), up("gpt-4o-mini")));
        assertEquals(2, service.syncFromUpstream(1L, "admin")); verify(mapper).deleteByChannelId(1L);
    }
    @Test void sync_upstreamFails_deletesNothing()
    {
        when(channelMapper.selectAiChannelById(1L)).thenReturn(channel(1L, "0"));
        when(client.fetchModels(any(), anyString())).thenThrow(new ServiceException("超时"));
        assertThrows(ServiceException.class, () -> service.syncFromUpstream(1L, "admin"));
        verify(mapper, never()).deleteByChannelId(anyLong());
    }
    @Test void sync_emptyResult_refusesToWipe()
    {
        when(channelMapper.selectAiChannelById(1L)).thenReturn(channel(1L, "0"));
        when(client.fetchModels(any(), anyString())).thenReturn(List.of());
        assertThrows(ServiceException.class, () -> service.syncFromUpstream(1L, "admin"));
        verify(mapper, never()).deleteByChannelId(anyLong());
    }
    @Test void sync_deduplicatesUpstreamIds()
    {
        when(channelMapper.selectAiChannelById(1L)).thenReturn(channel(1L, "0"));
        when(client.fetchModels(any(), anyString())).thenReturn(List.of(up("gpt-4o"), up("gpt-4o")));
        assertEquals(1, service.syncFromUpstream(1L, "admin"));
    }
    @Test void sync_onCustomChannel_isRejected()
    {
        when(channelMapper.selectAiChannelById(9L)).thenReturn(channel(9L, "1"));
        assertThrows(ServiceException.class, () -> service.syncFromUpstream(9L, "admin")); verifyNoInteractions(client);
    }
    @Test void addManual_onNonCustomChannel_isRejected()
    {
        when(channelMapper.selectAiChannelById(1L)).thenReturn(channel(1L, "0"));
        assertThrows(ServiceException.class, () -> service.addManual(model(1L, "x"), "admin"));
    }
    @Test void addManual_onCustomChannel_marksManualSource()
    {
        when(channelMapper.selectAiChannelById(9L)).thenReturn(channel(9L, "1"));
        AiUpstreamModel model = model(9L, "  my-model  "); service.addManual(model, "admin");
        assertEquals("my-model", model.getUpstreamModelId()); assertEquals("my-model", model.getDisplayName());
        assertEquals(AiUpstreamModel.SOURCE_MANUAL, model.getSource());
    }
    @Test void addManual_duplicate_givesReadableError()
    {
        when(channelMapper.selectAiChannelById(9L)).thenReturn(channel(9L, "1"));
        when(mapper.selectByChannelAndModelId(9L, "dup")).thenReturn(new AiUpstreamModel());
        assertThrows(ServiceException.class, () -> service.addManual(model(9L, "dup"), "admin"));
    }
    @Test void delete_onNonCustomChannel_isRejected()
    {
        AiUpstreamModel row = model(1L, "gpt-4o"); row.setId(10L);
        when(mapper.selectByIds(any())).thenReturn(List.of(row));
        when(channelMapper.selectAiChannelById(1L)).thenReturn(channel(1L, "0"));
        assertThrows(ServiceException.class, () -> service.deleteByIds(new Long[] {10L}));
        verify(mapper, never()).deleteByIds(any());
    }
    /** 上游给了模态就必须落库,否则导入侧只能回退按名字猜(实测漏报约三分之二)。 */
    @Test void sync_carriesUpstreamInputModalities()
    {
        when(channelMapper.selectAiChannelById(1L)).thenReturn(channel(1L, "0"));
        when(client.fetchModels(any(), anyString())).thenReturn(List.of(up("m1", "image,file")));
        service.syncFromUpstream(1L, "admin");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AiUpstreamModel>> cap = ArgumentCaptor.forClass(List.class);
        verify(mapper).insertBatch(cap.capture());
        assertEquals("image,file", cap.getValue().get(0).getInputModalities());
    }

    /** 上游没给模态时存 null,不能当成"纯文本"写死。 */
    @Test void sync_keepsNullWhenUpstreamSilent()
    {
        when(channelMapper.selectAiChannelById(1L)).thenReturn(channel(1L, "0"));
        when(client.fetchModels(any(), anyString())).thenReturn(List.of(up("m1")));
        service.syncFromUpstream(1L, "admin");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AiUpstreamModel>> cap = ArgumentCaptor.forClass(List.class);
        verify(mapper).insertBatch(cap.capture());
        assertNull(cap.getValue().get(0).getInputModalities());
    }

    @Test void update_onNonCustomChannel_isAllowed()
    {
        AiUpstreamModel update = new AiUpstreamModel(); update.setId(10L);
        service.update(update, "admin");
        verify(mapper).updateOne(update);
    }
    private static UpstreamModelClient.UpstreamModel up(String id) { return up(id, null); }
    private static UpstreamModelClient.UpstreamModel up(String id, String modalities) { return new UpstreamModelClient.UpstreamModel(id, id, "test", modalities); }
    private static AiUpstreamModel model(Long channelId, String id) { AiUpstreamModel m = new AiUpstreamModel(); m.setChannelId(channelId); m.setUpstreamModelId(id); return m; }
    private static AiChannel channel(Long id, String custom) { AiChannel c = new AiChannel(); c.setChannelId(id); c.setChannelName("渠道" + id); c.setApiKey("cipher"); c.setIsCustom(custom); return c; }
}
