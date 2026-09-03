package com.ruoyi.system.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.KbDocument;
import com.ruoyi.system.domain.KbKnowledge;
import com.ruoyi.system.kb.ingest.KbIngestExecutor;
import com.ruoyi.system.mapper.KbDocumentMapper;
import com.ruoyi.system.mapper.KbKnowledgeMapper;

/**
 * 上传 onDuplicate 策略：replace 必须在任何删除前以业务 code 400 拒绝。
 */
@ExtendWith(MockitoExtension.class)
class KbDocumentUploadPolicyTest
{
    @Mock
    private KbDocumentMapper kbDocumentMapper;
    @Mock
    private KbKnowledgeMapper kbKnowledgeMapper;
    @Mock
    private KbIngestExecutor kbIngestExecutor;

    @InjectMocks
    private KbDocumentServiceImpl service;

    @Test
    void rejectReplaceBeforeAnyDelete()
    {
        MockMultipartFile file = new MockMultipartFile(
            "file", "a.pdf", "application/pdf", "hello".getBytes());

        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.uploadDocument(1L, file, "u", "replace"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getCode());
        assertTrue(ex.getMessage().contains("replace") || ex.getMessage().contains("替换"));

        verify(kbKnowledgeMapper, never()).selectKbKnowledgeById(anyLong());
        verify(kbDocumentMapper, never()).selectByKbIdAndContentHash(anyLong(), anyString());
        verify(kbDocumentMapper, never()).deleteKbDocumentByIds(any());
        verify(kbIngestExecutor, never()).submit(anyLong());
    }

    @Test
    void skipReturnsExistingDuplicateWithoutDelete()
    {
        MockMultipartFile file = new MockMultipartFile(
            "file", "a.pdf", "application/pdf", "same-content".getBytes());
        KbKnowledge kb = new KbKnowledge();
        kb.setKbId(1L);
        when(kbKnowledgeMapper.selectKbKnowledgeById(1L)).thenReturn(kb);

        KbDocument existing = new KbDocument();
        existing.setDocId(9L);
        existing.setKbId(1L);
        existing.setDocName("a.pdf");
        // contentHash 在 service 内计算；需要 mock 按任意 hash 返回 existing
        when(kbDocumentMapper.selectByKbIdAndContentHash(anyLong(), anyString())).thenReturn(existing);

        KbDocument result = service.uploadDocument(1L, file, "u", "skip");
        assertEquals(Long.valueOf(9L), result.getDocId());
        assertEquals("DUPLICATE", result.getRemark());
        verify(kbDocumentMapper, never()).deleteKbDocumentByIds(any());
        verify(kbDocumentMapper, never()).insertKbDocument(any());
        verify(kbIngestExecutor, never()).submit(anyLong());
    }
}
