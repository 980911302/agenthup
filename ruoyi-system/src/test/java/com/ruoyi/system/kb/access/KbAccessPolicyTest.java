package com.ruoyi.system.kb.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import com.ruoyi.system.domain.KbKnowledge;
import com.ruoyi.system.domain.vo.KbAccessVO;

class KbAccessPolicyTest
{
    @Test
    void adminCanDoEverything()
    {
        KbKnowledge kb = kb(10L, 100L, "DEPT");
        assertTrue(KbAccessPolicy.isAllowed(kb, 2L, 200L, true, KbAccessAction.DELETE, null));
        assertTrue(KbAccessPolicy.isAllowed(kb, 2L, 200L, true, KbAccessAction.MANAGE, null));
    }

    @Test
    void ownerFullAccessViaOwnerUserId()
    {
        KbKnowledge kb = kb(10L, 100L, "PRIVATE");
        kb.setOwnerUserId(10L);
        kb.setCreateUserId(99L);
        assertTrue(KbAccessPolicy.isAllowed(kb, 10L, 999L, false, KbAccessAction.DELETE, null));
        assertFalse(KbAccessPolicy.isAllowed(kb, 99L, 999L, false, KbAccessAction.WRITE, null));
    }

    @Test
    void sameDeptReadUseOnly()
    {
        KbKnowledge kb = kb(10L, 100L, "DEPT");
        assertTrue(KbAccessPolicy.isAllowed(kb, 20L, 100L, false, KbAccessAction.READ, null));
        assertTrue(KbAccessPolicy.isAllowed(kb, 20L, 100L, false, KbAccessAction.USE, null));
        assertFalse(KbAccessPolicy.isAllowed(kb, 20L, 100L, false, KbAccessAction.WRITE, null));
        assertFalse(KbAccessPolicy.isAllowed(kb, 20L, 100L, false, KbAccessAction.MANAGE, null));
        assertFalse(KbAccessPolicy.isAllowed(kb, 20L, 100L, false, KbAccessAction.DELETE, null));
    }

    @Test
    void privateHidesDeptPeers()
    {
        KbKnowledge kb = kb(10L, 100L, "PRIVATE");
        assertFalse(KbAccessPolicy.isAllowed(kb, 20L, 100L, false, KbAccessAction.READ, null));
        assertTrue(KbAccessPolicy.isAllowed(kb, 20L, 100L, false, KbAccessAction.READ, "VIEWER"));
    }

    @Test
    void legacyBlankVisibilityDoesNotGrantDepartmentAccess()
    {
        KbKnowledge kb = kb(10L, 100L, null);
        assertEquals(KbAccessPolicy.VIS_PRIVATE, KbAccessPolicy.effectiveVisibility(kb));
        assertFalse(KbAccessPolicy.isAllowed(kb, 20L, 100L, false, KbAccessAction.READ, null));
        assertTrue(KbAccessPolicy.isAllowed(kb, 10L, 100L, false, KbAccessAction.READ, null));
    }

    @Test
    void invalidVisibilityDoesNotGrantDepartmentAccess()
    {
        KbKnowledge kb = kb(10L, 100L, "EVERYONE");
        assertEquals(KbAccessPolicy.VIS_PRIVATE, KbAccessPolicy.effectiveVisibility(kb));
        assertFalse(KbAccessPolicy.isAllowed(kb, 20L, 100L, false, KbAccessAction.READ, null));
    }

    @Test
    void invalidAclRoleDoesNotMakeKnowledgeBaseVisible()
    {
        KbKnowledge kb = kb(10L, 100L, "PRIVATE");
        assertFalse(KbAccessPolicy.isAllowed(kb, 20L, 100L, false, KbAccessAction.READ, "GUEST"));
    }

    @Test
    void orgReadableByAnyone()
    {
        KbKnowledge kb = kb(10L, 100L, "ORG");
        assertTrue(KbAccessPolicy.isAllowed(kb, 20L, 200L, false, KbAccessAction.READ, null));
        assertTrue(KbAccessPolicy.isAllowed(kb, 20L, 200L, false, KbAccessAction.USE, null));
        assertFalse(KbAccessPolicy.isAllowed(kb, 20L, 200L, false, KbAccessAction.WRITE, null));
    }

    @Test
    void editorCanWriteNotDelete()
    {
        KbKnowledge kb = kb(10L, 100L, "PRIVATE");
        assertTrue(KbAccessPolicy.isAllowed(kb, 20L, 200L, false, KbAccessAction.WRITE, "EDITOR"));
        assertFalse(KbAccessPolicy.isAllowed(kb, 20L, 200L, false, KbAccessAction.DELETE, "EDITOR"));
        assertTrue(KbAccessPolicy.isAllowed(kb, 20L, 200L, false, KbAccessAction.MANAGE, "QUALITY"));
    }

    @Test
    void orphanOnlyAdmin()
    {
        KbKnowledge orphan = new KbKnowledge();
        orphan.setKbId(1L);
        assertFalse(KbAccessPolicy.isAllowed(orphan, 10L, 100L, false, KbAccessAction.READ, null));
        assertTrue(KbAccessPolicy.isAllowed(orphan, 1L, 1L, true, KbAccessAction.READ, null));
    }

    @Test
    void listScopeMarksAdmin()
    {
        KbKnowledge q = new KbKnowledge();
        KbAccessPolicy.applyListScope(q, 9L, 8L, true);
        assertTrue("1".equals(q.getAccessAdmin()));
    }

    @Test
    void listScopeMarksUser()
    {
        KbKnowledge q = new KbKnowledge();
        KbAccessPolicy.applyListScope(q, 9L, 8L, false);
        assertTrue("0".equals(q.getAccessAdmin()));
        assertTrue(Long.valueOf(9L).equals(q.getAccessUserId()));
        assertTrue(Long.valueOf(8L).equals(q.getAccessDeptId()));
    }

    @Test
    void resolveAccessPlatformAdmin()
    {
        KbKnowledge kb = kb(10L, 100L, "PRIVATE");
        KbAccessVO vo = KbAccessPolicy.resolveAccess(kb, 2L, 200L, true, null);
        assertEquals(KbAccessPolicy.SOURCE_PLATFORM_ADMIN, vo.getSource());
        assertNull(vo.getRole());
        assertTrue(vo.getIsPlatformAdmin());
        assertTrue(vo.getCanRead());
        assertTrue(vo.getCanUse());
        assertTrue(vo.getCanWrite());
        assertTrue(vo.getCanManage());
        assertTrue(vo.getCanDelete());
    }

    @Test
    void resolveAccessOwner()
    {
        KbKnowledge kb = kb(10L, 100L, "PRIVATE");
        kb.setOwnerUserId(10L);
        kb.setCreateUserId(99L);
        KbAccessVO vo = KbAccessPolicy.resolveAccess(kb, 10L, 999L, false, null);
        assertEquals(KbAccessPolicy.SOURCE_OWNER, vo.getSource());
        assertEquals(KbAccessPolicy.ROLE_OWNER, vo.getRole());
        assertFalse(vo.getIsPlatformAdmin());
        assertTrue(vo.getCanRead());
        assertTrue(vo.getCanWrite());
        assertTrue(vo.getCanManage());
        assertTrue(vo.getCanDelete());
    }

    @Test
    void resolveAccessTransferredOwnerNotCreator()
    {
        KbKnowledge kb = kb(99L, 100L, "PRIVATE");
        kb.setOwnerUserId(10L);
        kb.setCreateUserId(99L);
        // 旧创建者无 ACL 时不应有任何 capability
        KbAccessVO denied = KbAccessPolicy.resolveAccess(kb, 99L, 100L, false, null);
        assertFalse(denied.getCanRead());
        assertFalse(denied.getCanWrite());

        KbAccessVO owner = KbAccessPolicy.resolveAccess(kb, 10L, 200L, false, null);
        assertEquals(KbAccessPolicy.SOURCE_OWNER, owner.getSource());
        assertTrue(owner.getCanDelete());
    }

    @Test
    void resolveAccessAclRoles()
    {
        KbKnowledge kb = kb(10L, 100L, "PRIVATE");

        KbAccessVO viewer = KbAccessPolicy.resolveAccess(kb, 20L, 200L, false, "VIEWER");
        assertEquals(KbAccessPolicy.SOURCE_ACL, viewer.getSource());
        assertEquals(KbAccessPolicy.ROLE_VIEWER, viewer.getRole());
        assertTrue(viewer.getCanRead());
        assertTrue(viewer.getCanUse());
        assertFalse(viewer.getCanWrite());
        assertFalse(viewer.getCanManage());
        assertFalse(viewer.getCanDelete());

        KbAccessVO editor = KbAccessPolicy.resolveAccess(kb, 21L, 200L, false, "EDITOR");
        assertEquals(KbAccessPolicy.SOURCE_ACL, editor.getSource());
        assertEquals(KbAccessPolicy.ROLE_EDITOR, editor.getRole());
        assertTrue(editor.getCanWrite());
        assertFalse(editor.getCanManage());
        assertFalse(editor.getCanDelete());

        KbAccessVO quality = KbAccessPolicy.resolveAccess(kb, 22L, 200L, false, "QUALITY");
        assertEquals(KbAccessPolicy.SOURCE_ACL, quality.getSource());
        assertEquals(KbAccessPolicy.ROLE_QUALITY, quality.getRole());
        assertTrue(quality.getCanManage());
        assertFalse(quality.getCanDelete());
    }

    @Test
    void resolveAccessDeptAndOrgScopeReadOnly()
    {
        KbKnowledge deptKb = kb(10L, 100L, "DEPT");
        KbAccessVO dept = KbAccessPolicy.resolveAccess(deptKb, 20L, 100L, false, null);
        assertEquals(KbAccessPolicy.SOURCE_DEPT, dept.getSource());
        assertNull(dept.getRole());
        assertTrue(dept.getCanRead());
        assertTrue(dept.getCanUse());
        assertFalse(dept.getCanWrite());
        assertFalse(dept.getCanManage());
        assertFalse(dept.getCanDelete());

        KbKnowledge orgKb = kb(10L, 100L, "ORG");
        KbAccessVO org = KbAccessPolicy.resolveAccess(orgKb, 20L, 200L, false, null);
        assertEquals(KbAccessPolicy.SOURCE_ORG, org.getSource());
        assertNull(org.getRole());
        assertTrue(org.getCanRead());
        assertTrue(org.getCanUse());
        assertFalse(org.getCanWrite());
    }

    @Test
    void resolveAccessAclOverridesDeptScope()
    {
        KbKnowledge kb = kb(10L, 100L, "DEPT");
        KbAccessVO vo = KbAccessPolicy.resolveAccess(kb, 20L, 100L, false, "EDITOR");
        assertEquals(KbAccessPolicy.SOURCE_ACL, vo.getSource());
        assertEquals(KbAccessPolicy.ROLE_EDITOR, vo.getRole());
        assertTrue(vo.getCanWrite());
        assertFalse(vo.getCanManage());
    }

    @Test
    void resolveAccessLegacyMembersVisibility()
    {
        KbKnowledge kb = kb(10L, 100L, "MEMBERS");
        KbAccessVO member = KbAccessPolicy.resolveAccess(kb, 20L, 100L, false, "VIEWER");
        assertEquals(KbAccessPolicy.SOURCE_ACL, member.getSource());
        assertTrue(member.getCanRead());

        KbAccessVO peer = KbAccessPolicy.resolveAccess(kb, 20L, 100L, false, null);
        assertFalse(peer.getCanRead());
        assertEquals(KbAccessPolicy.SOURCE_LEGACY, peer.getSource());
    }

    @Test
    void resolveAccessNoPermissionFlagsOff()
    {
        KbKnowledge kb = kb(10L, 100L, "PRIVATE");
        KbAccessVO vo = KbAccessPolicy.resolveAccess(kb, 20L, 200L, false, null);
        assertFalse(vo.getCanRead());
        assertFalse(vo.getCanUse());
        assertFalse(vo.getCanWrite());
        assertFalse(vo.getCanManage());
        assertFalse(vo.getCanDelete());
        assertFalse(vo.getIsPlatformAdmin());
    }

    /**
     * 负责人转移后：列表作用域与详情 access 一致——仅 effectiveOwner 是负责人，
     * 旧 create_user_id 不再因创建者身份获得权限（mapper 已去掉 create_user_id 额外放行）。
     */
    @Test
    void effectiveOwnerIgnoresStaleCreatorAfterTransfer()
    {
        KbKnowledge kb = kb(99L, 100L, "PRIVATE");
        kb.setOwnerUserId(10L);
        kb.setCreateUserId(99L);
        assertEquals(Long.valueOf(10L), KbAccessPolicy.effectiveOwnerId(kb));
        assertFalse(KbAccessPolicy.isAllowed(kb, 99L, 100L, false, KbAccessAction.READ, null));
        assertTrue(KbAccessPolicy.isAllowed(kb, 10L, 200L, false, KbAccessAction.DELETE, null));
        // 旧创建者若被降为 EDITOR 后仍可读可写，但不能 DELETE
        assertTrue(KbAccessPolicy.isAllowed(kb, 99L, 100L, false, KbAccessAction.WRITE, "EDITOR"));
        assertFalse(KbAccessPolicy.isAllowed(kb, 99L, 100L, false, KbAccessAction.DELETE, "EDITOR"));
    }

    private static KbKnowledge kb(Long creator, Long dept, String visibility)
    {
        KbKnowledge k = new KbKnowledge();
        k.setKbId(1L);
        k.setCreateUserId(creator);
        k.setOwnerUserId(creator);
        k.setDeptId(dept);
        k.setVisibility(visibility);
        return k;
    }
}
