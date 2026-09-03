package com.ruoyi.system.service.impl;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.ruoyi.system.ai.session.AiChatSessionKbCleanup;
import com.ruoyi.system.kb.access.KbAccessAction;
import com.ruoyi.system.kb.access.KbAuthorizationService;
import com.ruoyi.system.kb.graph.KbGraphStore;
import com.ruoyi.system.kb.vector.KbVectorStore;
import com.ruoyi.system.mapper.KbChunkMapper;
import com.ruoyi.system.mapper.KbDocGraphMapper;
import com.ruoyi.system.mapper.KbGraphCommunityMapper;
import com.ruoyi.system.mapper.KbGraphRunMapper;
import com.ruoyi.system.mapper.KbGraphTextUnitMapper;
import com.ruoyi.system.mapper.KbKnowledgeMapper;

/**
 * 删除知识库：可选清理失败不得挡住主表软删。
 */
@ExtendWith(MockitoExtension.class)
class KbKnowledgeDeleteTest
{
    @Mock
    private KbKnowledgeMapper kbKnowledgeMapper;
    @Mock
    private KbAuthorizationService kbAuthorizationService;
    @Mock
    private KbChunkMapper kbChunkMapper;
    @Mock
    private KbVectorStore kbVectorStore;
    @Mock
    private KbGraphStore kbGraphStore;
    @Mock
    private KbDocGraphMapper kbDocGraphMapper;
    @Mock
    private KbGraphRunMapper kbGraphRunMapper;
    @Mock
    private KbGraphTextUnitMapper kbGraphTextUnitMapper;
    @Mock
    private KbGraphCommunityMapper kbGraphCommunityMapper;
    @Mock
    private AiChatSessionKbCleanup aiChatSessionKbCleanup;

    @InjectMocks
    private KbKnowledgeServiceImpl service;

    @Test
    void deleteStillSoftDeletesWhenOptionalCleanupFails()
    {
        Long[] ids = new Long[] { 9L };
        when(kbKnowledgeMapper.deleteKbKnowledgeByIds(ids)).thenReturn(1);
        doThrow(new RuntimeException("graph missing")).when(kbGraphCommunityMapper).deleteReportSourcesByKb(9L);
        doThrow(new RuntimeException("mysql table on pg")).when(aiChatSessionKbCleanup).deleteByKbId(9L);

        service.deleteKbKnowledgeByIds(ids);

        verify(kbAuthorizationService).requireKbIds(ids, KbAccessAction.DELETE);
        verify(kbChunkMapper).deleteByKbId(9L);
        verify(kbVectorStore).deleteByKb(9L);
        verify(aiChatSessionKbCleanup).deleteByKbId(9L);
        verify(kbKnowledgeMapper).deleteKbKnowledgeByIds(ids);
    }

    @Test
    void deleteByIdStillSoftDeletesWhenOptionalCleanupFails()
    {
        when(kbKnowledgeMapper.deleteKbKnowledgeById(9L)).thenReturn(1);
        doThrow(new RuntimeException("graph missing")).when(kbGraphRunMapper).deleteByKbId(9L);

        service.deleteKbKnowledgeById(9L);

        verify(kbAuthorizationService).requireKb(9L, KbAccessAction.DELETE);
        verify(kbKnowledgeMapper).deleteKbKnowledgeById(9L);
    }
}
