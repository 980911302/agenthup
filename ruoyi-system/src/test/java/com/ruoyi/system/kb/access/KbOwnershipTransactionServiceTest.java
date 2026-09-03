package com.ruoyi.system.kb.access;

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
import com.ruoyi.system.domain.KbAclMember;
import com.ruoyi.system.domain.KbKnowledge;
import com.ruoyi.system.mapper.KbAclMemberMapper;
import com.ruoyi.system.mapper.KbKnowledgeMapper;

/**
 * 负责人转移事务语义单测（mock mapper，不启 Spring 事务管理器）。
 * <p>验证：目标非成员拒绝；成功路径更新 owner + 旧 owner upsert EDITOR + 删新 owner ACL；
 * 任一步失败时后续步骤不被调用（真实 PG 事务由 @Transactional 保证回滚）。
 */
@ExtendWith(MockitoExtension.class)
class KbOwnershipTransactionServiceTest
{
    @Mock
    private KbKnowledgeMapper kbKnowledgeMapper;

    @Mock
    private KbAclMemberMapper kbAclMemberMapper;

    @InjectMocks
    private KbOwnershipTransactionService service;

    @BeforeEach
    void loginAsOwner()
    {
        LoginUser login = new LoginUser();
        login.setUserId(10L);
        login.setDeptId(100L);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(login, null, List.of()));
    }

    @AfterEach
    void clearSecurity()
    {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsWhenTargetIsNotMember()
    {
        when(kbKnowledgeMapper.selectKbKnowledgeByIdForUpdate(1L)).thenReturn(privateKb(10L));
        when(kbAclMemberMapper.selectByKbAndUser(1L, 10L)).thenReturn(null);
        when(kbAclMemberMapper.selectByKbAndUser(1L, 20L)).thenReturn(null);

        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.transferOwner(1L, 20L, "owner"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getCode());
        assertTrue(ex.getMessage().contains("必须是当前成员"));
        verify(kbKnowledgeMapper, never()).updateKbKnowledge(any());
        verify(kbAclMemberMapper, never()).upsert(any());
        verify(kbAclMemberMapper, never()).deleteByKbAndUser(any(), any());
    }

    @Test
    void rejectsWhenOperatorLacksDelete()
    {
        // 库属于他人，操作者仅 VIEWER
        when(kbKnowledgeMapper.selectKbKnowledgeByIdForUpdate(1L)).thenReturn(privateKb(99L));
        KbAclMember viewer = member(20L, "VIEWER");
        when(kbAclMemberMapper.selectByKbAndUser(1L, 10L)).thenReturn(viewer);

        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.transferOwner(1L, 20L, "viewer"));
        assertEquals(HttpStatus.FORBIDDEN, ex.getCode());
        verify(kbKnowledgeMapper, never()).updateKbKnowledge(any());
    }

    @Test
    void successUpdatesOwnerDemotesOldAndDeletesNewAcl()
    {
        when(kbKnowledgeMapper.selectKbKnowledgeByIdForUpdate(1L)).thenReturn(privateKb(10L));
        when(kbAclMemberMapper.selectByKbAndUser(1L, 10L)).thenReturn(null);
        when(kbAclMemberMapper.selectByKbAndUser(1L, 20L)).thenReturn(member(20L, "EDITOR"));
        when(kbKnowledgeMapper.updateKbKnowledge(any())).thenReturn(1);
        when(kbAclMemberMapper.upsert(any())).thenReturn(1);
        when(kbAclMemberMapper.deleteByKbAndUser(1L, 20L)).thenReturn(1);

        service.transferOwner(1L, 20L, "owner");

        ArgumentCaptor<KbKnowledge> patchCap = ArgumentCaptor.forClass(KbKnowledge.class);
        verify(kbKnowledgeMapper).updateKbKnowledge(patchCap.capture());
        assertEquals(Long.valueOf(20L), patchCap.getValue().getOwnerUserId());
        assertEquals(Long.valueOf(1L), patchCap.getValue().getKbId());

        ArgumentCaptor<KbAclMember> demoteCap = ArgumentCaptor.forClass(KbAclMember.class);
        verify(kbAclMemberMapper).upsert(demoteCap.capture());
        assertEquals(Long.valueOf(10L), demoteCap.getValue().getUserId());
        assertEquals(KbAccessPolicy.ROLE_EDITOR, demoteCap.getValue().getRole());

        verify(kbAclMemberMapper).deleteByKbAndUser(1L, 20L);
    }

    @Test
    void doesNotContinueWhenOwnerUpdateFails()
    {
        when(kbKnowledgeMapper.selectKbKnowledgeByIdForUpdate(1L)).thenReturn(privateKb(10L));
        when(kbAclMemberMapper.selectByKbAndUser(1L, 10L)).thenReturn(null);
        when(kbAclMemberMapper.selectByKbAndUser(1L, 20L)).thenReturn(member(20L, "QUALITY"));
        when(kbKnowledgeMapper.updateKbKnowledge(any())).thenReturn(0);

        assertThrows(ServiceException.class, () -> service.transferOwner(1L, 20L, "owner"));
        verify(kbAclMemberMapper, never()).upsert(any());
        verify(kbAclMemberMapper, never()).deleteByKbAndUser(any(), any());
    }

    @Test
    void doesNotDeleteNewOwnerAclWhenDemoteUpsertFails()
    {
        when(kbKnowledgeMapper.selectKbKnowledgeByIdForUpdate(1L)).thenReturn(privateKb(10L));
        when(kbAclMemberMapper.selectByKbAndUser(1L, 10L)).thenReturn(null);
        when(kbAclMemberMapper.selectByKbAndUser(1L, 20L)).thenReturn(member(20L, "EDITOR"));
        when(kbKnowledgeMapper.updateKbKnowledge(any())).thenReturn(1);
        when(kbAclMemberMapper.upsert(any())).thenThrow(new RuntimeException("unique conflict"));

        assertThrows(RuntimeException.class, () -> service.transferOwner(1L, 20L, "owner"));
        // 真实环境下 @Transactional 会回滚 owner 更新；此处确认失败路径不继续删 ACL
        verify(kbAclMemberMapper, never()).deleteByKbAndUser(eq(1L), eq(20L));
    }

    @Test
    void idempotentWhenAlreadyOwner()
    {
        when(kbKnowledgeMapper.selectKbKnowledgeByIdForUpdate(1L)).thenReturn(privateKb(10L));
        when(kbAclMemberMapper.selectByKbAndUser(1L, 10L)).thenReturn(null);

        service.transferOwner(1L, 10L, "owner");

        verify(kbKnowledgeMapper, never()).updateKbKnowledge(any());
        verify(kbAclMemberMapper, never()).upsert(any());
    }

    private static KbKnowledge privateKb(Long ownerId)
    {
        KbKnowledge kb = new KbKnowledge();
        kb.setKbId(1L);
        kb.setOwnerUserId(ownerId);
        kb.setCreateUserId(ownerId);
        kb.setDeptId(100L);
        kb.setVisibility("PRIVATE");
        return kb;
    }

    private static KbAclMember member(Long userId, String role)
    {
        KbAclMember m = new KbAclMember();
        m.setKbId(1L);
        m.setUserId(userId);
        m.setRole(role);
        return m;
    }
}
