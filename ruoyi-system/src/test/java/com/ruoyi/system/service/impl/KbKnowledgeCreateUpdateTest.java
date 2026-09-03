package com.ruoyi.system.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.KbKnowledge;
import com.ruoyi.system.kb.access.KbAccessAction;
import com.ruoyi.system.kb.access.KbAccessPolicy;
import com.ruoyi.system.kb.access.KbAuthorizationService;
import com.ruoyi.system.mapper.KbKnowledgeMapper;
import com.ruoyi.system.service.ISysConfigService;

/**
 * 创建缺省 visibility=PRIVATE、名称校验；更新 status/visibility 需 MANAGE、局部名称校验。
 */
@ExtendWith(MockitoExtension.class)
class KbKnowledgeCreateUpdateTest
{
    @Mock
    private KbKnowledgeMapper kbKnowledgeMapper;
    @Mock
    private ISysConfigService configService;
    @Mock
    private KbAuthorizationService kbAuthorizationService;

    @InjectMocks
    private KbKnowledgeServiceImpl service;

    @BeforeEach
    void loginUserWithDept()
    {
        LoginUser login = new LoginUser();
        login.setUserId(7L);
        login.setDeptId(100L);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(login, null, List.of()));
    }

    @AfterEach
    void clear()
    {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createDefaultsVisibilityPrivateEvenWithDept()
    {
        when(configService.selectConfigByKey("kb.default.embeddingModel")).thenReturn("emb-1");
        when(configService.selectConfigByKey("kb.default.extractModel")).thenReturn(null);
        when(configService.selectConfigByKey("kb.default.chunkStrategy")).thenReturn(null);
        when(configService.selectConfigByKey("kb.default.chunkSize")).thenReturn(null);
        when(configService.selectConfigByKey("kb.default.chunkOverlap")).thenReturn(null);
        when(configService.selectConfigByKey("kb.default.graphEnabled")).thenReturn(null);
        when(kbKnowledgeMapper.insertKbKnowledge(any())).thenReturn(1);

        KbKnowledge req = new KbKnowledge();
        req.setKbName("产品帮助中心");
        // 省略 visibility
        service.insertKbKnowledge(req);

        ArgumentCaptor<KbKnowledge> cap = ArgumentCaptor.forClass(KbKnowledge.class);
        verify(kbKnowledgeMapper).insertKbKnowledge(cap.capture());
        assertEquals(KbAccessPolicy.VIS_PRIVATE, cap.getValue().getVisibility());
        assertEquals(Long.valueOf(7L), cap.getValue().getOwnerUserId());
        assertEquals(Long.valueOf(100L), cap.getValue().getDeptId());
    }

    @Test
    void createExplicitDeptVisibilityRespected()
    {
        when(configService.selectConfigByKey("kb.default.embeddingModel")).thenReturn("emb-1");
        when(configService.selectConfigByKey("kb.default.extractModel")).thenReturn(null);
        when(configService.selectConfigByKey("kb.default.chunkStrategy")).thenReturn(null);
        when(configService.selectConfigByKey("kb.default.chunkSize")).thenReturn(null);
        when(configService.selectConfigByKey("kb.default.chunkOverlap")).thenReturn(null);
        when(configService.selectConfigByKey("kb.default.graphEnabled")).thenReturn(null);
        when(kbKnowledgeMapper.insertKbKnowledge(any())).thenReturn(1);

        KbKnowledge req = new KbKnowledge();
        req.setKbName("部门库");
        req.setVisibility("DEPT");
        service.insertKbKnowledge(req);

        ArgumentCaptor<KbKnowledge> cap = ArgumentCaptor.forClass(KbKnowledge.class);
        verify(kbKnowledgeMapper).insertKbKnowledge(cap.capture());
        assertEquals(KbAccessPolicy.VIS_DEPT, cap.getValue().getVisibility());
    }

    @Test
    void createRejectsBlankName()
    {
        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.insertKbKnowledge(named("   ")));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getCode());
        verify(kbKnowledgeMapper, never()).insertKbKnowledge(any());
    }

    @Test
    void createRejectsNameOver100()
    {
        String longName = "a".repeat(101);
        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.insertKbKnowledge(named(longName)));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getCode());
        assertTrue(ex.getMessage().contains("100"));
    }

    @Test
    void updateNameOnlyRequiresWrite()
    {
        when(kbAuthorizationService.requireKb(1L, KbAccessAction.WRITE))
            .thenReturn(new KbKnowledge());
        when(kbKnowledgeMapper.updateKbKnowledge(any())).thenReturn(1);

        KbKnowledge patch = new KbKnowledge();
        patch.setKbId(1L);
        patch.setKbName("新名称");
        service.updateKbKnowledge(patch);

        verify(kbAuthorizationService).requireKb(1L, KbAccessAction.WRITE);
        verify(kbAuthorizationService, never()).requireKb(eq(1L), eq(KbAccessAction.MANAGE));
    }

    @Test
    void updateStatusRequiresManage()
    {
        when(kbAuthorizationService.requireKb(1L, KbAccessAction.MANAGE))
            .thenReturn(new KbKnowledge());
        when(kbKnowledgeMapper.updateKbKnowledge(any())).thenReturn(1);

        KbKnowledge patch = new KbKnowledge();
        patch.setKbId(1L);
        patch.setStatus("1");
        service.updateKbKnowledge(patch);

        verify(kbAuthorizationService).requireKb(1L, KbAccessAction.MANAGE);
    }

    @Test
    void updateVisibilityRequiresManage()
    {
        when(kbAuthorizationService.requireKb(1L, KbAccessAction.MANAGE))
            .thenReturn(new KbKnowledge());
        when(kbKnowledgeMapper.updateKbKnowledge(any())).thenReturn(1);

        KbKnowledge patch = new KbKnowledge();
        patch.setKbId(1L);
        patch.setVisibility("ORG");
        service.updateKbKnowledge(patch);

        verify(kbAuthorizationService).requireKb(1L, KbAccessAction.MANAGE);
        ArgumentCaptor<KbKnowledge> cap = ArgumentCaptor.forClass(KbKnowledge.class);
        verify(kbKnowledgeMapper).updateKbKnowledge(cap.capture());
        assertEquals(KbAccessPolicy.VIS_ORG, cap.getValue().getVisibility());
    }

    @Test
    void updateOmittingNameDoesNotRequireName()
    {
        when(kbAuthorizationService.requireKb(1L, KbAccessAction.WRITE))
            .thenReturn(new KbKnowledge());
        when(kbKnowledgeMapper.updateKbKnowledge(any())).thenReturn(1);

        KbKnowledge patch = new KbKnowledge();
        patch.setKbId(1L);
        patch.setDescription("只改说明");
        service.updateKbKnowledge(patch);

        ArgumentCaptor<KbKnowledge> cap = ArgumentCaptor.forClass(KbKnowledge.class);
        verify(kbKnowledgeMapper).updateKbKnowledge(cap.capture());
        // 未传名称时 patch 不带 kbName
        assertEquals(null, cap.getValue().getKbName());
        assertEquals("只改说明", cap.getValue().getDescription());
    }

    @Test
    void updateBlankNameRejected()
    {
        when(kbAuthorizationService.requireKb(1L, KbAccessAction.WRITE))
            .thenReturn(new KbKnowledge());

        KbKnowledge patch = new KbKnowledge();
        patch.setKbId(1L);
        patch.setKbName("  ");
        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.updateKbKnowledge(patch));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getCode());
        verify(kbKnowledgeMapper, never()).updateKbKnowledge(any());
    }

    private static KbKnowledge named(String name)
    {
        KbKnowledge k = new KbKnowledge();
        k.setKbName(name);
        return k;
    }
}
