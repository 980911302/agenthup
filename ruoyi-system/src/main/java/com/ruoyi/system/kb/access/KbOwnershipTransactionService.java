package com.ruoyi.system.kb.access;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.annotation.DataSource;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.enums.DataSourceType;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.domain.KbAclMember;
import com.ruoyi.system.domain.KbKnowledge;
import com.ruoyi.system.mapper.KbAclMemberMapper;
import com.ruoyi.system.mapper.KbKnowledgeMapper;

/**
 * 知识库负责人转移：在 PostgreSQL SLAVE 单事务内完成全部 owner/ACL 写入。
 * <p>任一步失败整体回滚；禁止 best-effort try/catch 吞掉 ACL 异常。
 */
@Service
@DataSource(DataSourceType.SLAVE)
public class KbOwnershipTransactionService
{
    @Autowired
    private KbKnowledgeMapper kbKnowledgeMapper;

    @Autowired
    private KbAclMemberMapper kbAclMemberMapper;

    /**
     * @param kbId           知识库 ID
     * @param newOwnerUserId 新负责人（必须已是当前有效 ACL 成员）
     * @param updateBy       操作者用户名（可空）
     */
    @Transactional(rollbackFor = Exception.class)
    public void transferOwner(Long kbId, Long newOwnerUserId, String updateBy)
    {
        if (kbId == null || newOwnerUserId == null)
        {
            throw new ServiceException("请指定知识库与新负责人", HttpStatus.BAD_REQUEST);
        }
        if (kbAclMemberMapper == null)
        {
            throw new ServiceException("ACL 表未就绪，请执行 sql/kb_acl_v2.sql");
        }

        KbKnowledge kb = kbKnowledgeMapper.selectKbKnowledgeByIdForUpdate(kbId);
        if (kb == null)
        {
            throw new ServiceException(KbAuthorizationService.DENY_MSG, HttpStatus.FORBIDDEN);
        }

        Long operatorId = currentUserId();
        Long deptId = currentDeptId();
        boolean admin = currentIsAdmin();
        String memberRole = resolveMemberRole(kbId, operatorId);
        if (!KbAccessPolicy.isAllowed(kb, operatorId, deptId, admin, KbAccessAction.DELETE, memberRole))
        {
            throw new ServiceException(KbAuthorizationService.DENY_MSG, HttpStatus.FORBIDDEN);
        }

        Long oldOwner = KbAccessPolicy.effectiveOwnerId(kb);
        if (oldOwner != null && oldOwner.equals(newOwnerUserId))
        {
            // 已是负责人，幂等成功
            return;
        }

        // 目标必须是当前成员；不能把非成员直接设为 OWNER
        KbAclMember targetMember = kbAclMemberMapper.selectByKbAndUser(kbId, newOwnerUserId);
        if (targetMember == null)
        {
            throw new ServiceException("目标用户必须是当前成员，请先添加为成员", HttpStatus.BAD_REQUEST);
        }

        KbKnowledge patch = new KbKnowledge();
        patch.setKbId(kbId);
        patch.setOwnerUserId(newOwnerUserId);
        if (updateBy != null && !updateBy.isBlank())
        {
            patch.setUpdateBy(updateBy);
        }
        int updated = kbKnowledgeMapper.updateKbKnowledge(patch);
        if (updated <= 0)
        {
            throw new ServiceException("更新负责人失败");
        }

        // 旧负责人降为 EDITOR（幂等 upsert，已有 ACL 则改角色）
        if (oldOwner != null && !oldOwner.equals(newOwnerUserId))
        {
            KbAclMember demote = new KbAclMember();
            demote.setKbId(kbId);
            demote.setUserId(oldOwner);
            demote.setRole(KbAccessPolicy.ROLE_EDITOR);
            if (updateBy != null && !updateBy.isBlank())
            {
                demote.setCreateBy(updateBy);
            }
            kbAclMemberMapper.upsert(demote);
        }

        // 新负责人不再保留 ACL 行（OWNER 由 owner_user_id 表达）
        kbAclMemberMapper.deleteByKbAndUser(kbId, newOwnerUserId);
    }

    private String resolveMemberRole(Long kbId, Long userId)
    {
        if (kbId == null || userId == null)
        {
            return null;
        }
        KbAclMember m = kbAclMemberMapper.selectByKbAndUser(kbId, userId);
        return m == null ? null : m.getRole();
    }

    private static Long currentUserId()
    {
        try
        {
            return SecurityUtils.getUserId();
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static Long currentDeptId()
    {
        try
        {
            return SecurityUtils.getDeptId();
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static boolean currentIsAdmin()
    {
        try
        {
            return SecurityUtils.isAdmin();
        }
        catch (Exception e)
        {
            return false;
        }
    }
}
