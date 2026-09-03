package com.ruoyi.system.kb.access;

import com.ruoyi.system.domain.KbKnowledge;
import com.ruoyi.system.domain.vo.KbAccessVO;

/**
 * 纯函数访问策略（可单测）。
 * <p>可见范围 visibility：PRIVATE / MEMBERS / DEPT / ORG（空值按 PRIVATE 收紧）。
 * <p>成员角色 memberRole：VIEWER / EDITOR / QUALITY / OWNER（可空）。
 */
public final class KbAccessPolicy
{
    public static final String VIS_PRIVATE = "PRIVATE";
    public static final String VIS_MEMBERS = "MEMBERS";
    public static final String VIS_DEPT = "DEPT";
    public static final String VIS_ORG = "ORG";

    public static final String ROLE_VIEWER = "VIEWER";
    public static final String ROLE_EDITOR = "EDITOR";
    public static final String ROLE_QUALITY = "QUALITY";
    public static final String ROLE_OWNER = "OWNER";

    public static final String SOURCE_PLATFORM_ADMIN = "PLATFORM_ADMIN";
    public static final String SOURCE_OWNER = "OWNER";
    public static final String SOURCE_ACL = "ACL";
    public static final String SOURCE_DEPT = "DEPT";
    public static final String SOURCE_ORG = "ORG";
    public static final String SOURCE_LEGACY = "LEGACY";

    private KbAccessPolicy() {}

    /**
     * @param memberRole 显式 ACL 成员角色，非成员传 null
     */
    public static boolean isAllowed(KbKnowledge kb, Long userId, Long deptId,
        boolean admin, KbAccessAction action, String memberRole)
    {
        if (action == null)
        {
            return false;
        }
        if (admin)
        {
            return true;
        }
        if (kb == null || userId == null)
        {
            return false;
        }

        Long ownerId = effectiveOwnerId(kb);
        // 历史无归属：非超管不可见
        if (ownerId == null && kb.getDeptId() == null && isBlank(kb.getVisibility())
            && kb.getCreateUserId() == null)
        {
            return false;
        }

        if (ownerId != null && userId.equals(ownerId))
        {
            return true;
        }
        if (ROLE_OWNER.equalsIgnoreCase(memberRole))
        {
            return true;
        }

        String vis = effectiveVisibility(kb);
        int level = roleLevel(memberRole);

        // 范围带来的只读/使用
        boolean scopeReadUse = false;
        if (VIS_ORG.equals(vis))
        {
            scopeReadUse = true;
        }
        else if (VIS_DEPT.equals(vis))
        {
            scopeReadUse = deptId != null && kb.getDeptId() != null && deptId.equals(kb.getDeptId());
        }
        else if (VIS_PRIVATE.equals(vis) || VIS_MEMBERS.equals(vis))
        {
            scopeReadUse = false; // 仅负责人 + 成员
        }
        else
        {
            // 非法范围不能退化成部门共享，按最小权限拒绝。
            scopeReadUse = false;
        }

        return switch (action)
        {
            case READ, USE -> level >= 1 || scopeReadUse;
            case WRITE -> level >= 2;
            case MANAGE -> level >= 3;
            case DELETE -> level >= 4 || (ownerId != null && userId.equals(ownerId));
        };
    }

    /** 兼容旧签名：无成员角色 */
    public static boolean isAllowed(KbKnowledge kb, Long userId, Long deptId,
        boolean admin, KbAccessAction action)
    {
        return isAllowed(kb, userId, deptId, admin, action, null);
    }

    /**
     * 组装当前用户对库的 capability 快照（纯函数，可单测）。
     * <p>source 优先级：PLATFORM_ADMIN &gt; OWNER &gt; ACL &gt; DEPT &gt; ORG &gt; LEGACY。
     * 范围只读访问时 role 可为 null。
     */
    public static KbAccessVO resolveAccess(KbKnowledge kb, Long userId, Long deptId,
        boolean admin, String memberRole)
    {
        KbAccessVO vo = new KbAccessVO();
        vo.setIsPlatformAdmin(admin);
        vo.setCanRead(isAllowed(kb, userId, deptId, admin, KbAccessAction.READ, memberRole));
        vo.setCanUse(isAllowed(kb, userId, deptId, admin, KbAccessAction.USE, memberRole));
        vo.setCanWrite(isAllowed(kb, userId, deptId, admin, KbAccessAction.WRITE, memberRole));
        vo.setCanManage(isAllowed(kb, userId, deptId, admin, KbAccessAction.MANAGE, memberRole));
        vo.setCanDelete(isAllowed(kb, userId, deptId, admin, KbAccessAction.DELETE, memberRole));

        Long ownerId = effectiveOwnerId(kb);
        boolean isOwner = ownerId != null && userId != null && userId.equals(ownerId);
        String normalizedRole = normalizeRole(memberRole);

        if (admin)
        {
            vo.setSource(SOURCE_PLATFORM_ADMIN);
            if (isOwner)
            {
                vo.setRole(ROLE_OWNER);
            }
            else if (normalizedRole != null)
            {
                vo.setRole(normalizedRole);
            }
            else
            {
                vo.setRole(null);
            }
            return vo;
        }

        if (isOwner || ROLE_OWNER.equals(normalizedRole))
        {
            vo.setSource(SOURCE_OWNER);
            vo.setRole(ROLE_OWNER);
            return vo;
        }

        if (normalizedRole != null)
        {
            vo.setSource(SOURCE_ACL);
            vo.setRole(normalizedRole);
            return vo;
        }

        String vis = effectiveVisibility(kb);
        if (VIS_ORG.equals(vis))
        {
            vo.setSource(SOURCE_ORG);
            vo.setRole(null);
        }
        else if (VIS_DEPT.equals(vis))
        {
            vo.setSource(SOURCE_DEPT);
            vo.setRole(null);
        }
        else
        {
            // PRIVATE/MEMBERS 显式成员已在 ACL 分支；此处为兼容空 visibility 等同部门等遗留
            vo.setSource(SOURCE_LEGACY);
            vo.setRole(null);
        }
        return vo;
    }

    private static String normalizeRole(String role)
    {
        if (role == null || role.isBlank())
        {
            return null;
        }
        String r = role.trim().toUpperCase();
        return switch (r)
        {
            case ROLE_VIEWER, ROLE_EDITOR, ROLE_QUALITY, ROLE_OWNER -> r;
            default -> null;
        };
    }

    public static Long effectiveOwnerId(KbKnowledge kb)
    {
        if (kb == null)
        {
            return null;
        }
        if (kb.getOwnerUserId() != null)
        {
            return kb.getOwnerUserId();
        }
        return kb.getCreateUserId();
    }

    public static String effectiveVisibility(KbKnowledge kb)
    {
        if (kb == null)
        {
            return VIS_PRIVATE;
        }
        if (!isBlank(kb.getVisibility()))
        {
            String visibility = kb.getVisibility().trim().toUpperCase();
            return switch (visibility)
            {
                case VIS_PRIVATE, VIS_MEMBERS, VIS_DEPT, VIS_ORG -> visibility;
                default -> VIS_PRIVATE;
            };
        }
        // 旧库尚未写入 visibility 时不能因为带有部门字段就自动对同部门开放。
        // 部门共享必须由负责人显式设置为 DEPT。
        return VIS_PRIVATE;
    }

    /**
     * VIEWER=1, EDITOR=2, QUALITY=3, OWNER=4
     */
    public static int roleLevel(String role)
    {
        if (role == null || role.isBlank())
        {
            return 0;
        }
        return switch (role.trim().toUpperCase())
        {
            case ROLE_VIEWER -> 1;
            case ROLE_EDITOR -> 2;
            case ROLE_QUALITY -> 3;
            case ROLE_OWNER -> 4;
            default -> 0;
        };
    }

    public static boolean roleAllows(String role, KbAccessAction action)
    {
        return isAllowed(dummyOwnerKb(), 2L, 1L, false, action, role);
    }

    private static KbKnowledge dummyOwnerKb()
    {
        KbKnowledge k = new KbKnowledge();
        k.setOwnerUserId(1L);
        k.setVisibility(VIS_PRIVATE);
        return k;
    }

    /** 列表范围：非超管 */
    public static void applyListScope(KbKnowledge query, Long userId, Long deptId, boolean admin)
    {
        if (query == null)
        {
            return;
        }
        if (admin)
        {
            query.setAccessAdmin("1");
            query.setAccessUserId(null);
            query.setAccessDeptId(null);
            return;
        }
        query.setAccessAdmin("0");
        query.setAccessUserId(userId);
        query.setAccessDeptId(deptId);
    }

    private static boolean isBlank(String s)
    {
        return s == null || s.isBlank();
    }
}
