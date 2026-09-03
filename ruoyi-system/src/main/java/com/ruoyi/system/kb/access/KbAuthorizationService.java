package com.ruoyi.system.kb.access;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.annotation.DataSource;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.enums.DataSourceType;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.domain.KbAclMember;
import com.ruoyi.system.domain.KbDocument;
import com.ruoyi.system.domain.KbKnowledge;
import com.ruoyi.system.domain.vo.KbAccessVO;
import com.ruoyi.system.mapper.KbAclMemberMapper;
import com.ruoyi.system.mapper.KbDocumentMapper;
import com.ruoyi.system.mapper.KbKnowledgeMapper;

/**
 * 知识库服务端访问校验（KB-PV2-00 / PV2-08）。
 * <p>拒绝时统一文案，避免泄露目标是否存在。
 * <p>知识库/文档/ACL 表均在 PostgreSQL slave，本类必须强制 SLAVE，
 * 否则会落到 MySQL master 的旧 kb_* 表（缺 graph_enabled 等列）。
 * <p><b>事务</b>：使用 {@code NOT_SUPPORTED} 挂起调用方（如智能体保存）在 MASTER 上
 * 已开启的事务。否则 AbstractRoutingDataSource 仍复用外层已绑定的 MySQL 连接，
 * 出现 {@code Table 'agent-java.kb_knowledge' doesn't exist}。
 */
@Service
@DataSource(DataSourceType.SLAVE)
@Transactional(propagation = Propagation.NOT_SUPPORTED, readOnly = true)
public class KbAuthorizationService
{
    public static final String DENY_MSG = "知识库不存在或无权访问";
    public static final String DENY_DOC_MSG = "文档不存在或无权访问";

    @Autowired
    private KbKnowledgeMapper kbKnowledgeMapper;
    @Autowired
    private KbDocumentMapper kbDocumentMapper;
    @Autowired(required = false)
    private KbAclMemberMapper kbAclMemberMapper;

    public KbKnowledge requireKb(Long kbId, KbAccessAction action)
    {
        if (kbId == null)
        {
            throw denyKb();
        }
        KbKnowledge kb = kbKnowledgeMapper.selectKbKnowledgeById(kbId);
        if (kb == null)
        {
            throw denyKb();
        }
        String memberRole = resolveMemberRole(kbId, currentUserId());
        if (!KbAccessPolicy.isAllowed(kb, currentUserId(), currentDeptId(), isAdmin(), action, memberRole))
        {
            throw denyKb();
        }
        return kb;
    }

    /**
     * 只读：返回当前登录用户对该库的 capability，不夹带成员/智能体数据。
     * <p>先要求 READ；无权限时 body code 403。
     */
    public KbAccessVO resolveAccess(Long kbId)
    {
        KbKnowledge kb = requireKb(kbId, KbAccessAction.READ);
        String memberRole = resolveMemberRole(kbId, currentUserId());
        return KbAccessPolicy.resolveAccess(kb, currentUserId(), currentDeptId(), isAdmin(), memberRole);
    }

    public void requireKbIds(Long[] kbIds, KbAccessAction action)
    {
        if (kbIds == null)
        {
            return;
        }
        for (Long id : kbIds)
        {
            requireKb(id, action);
        }
    }

    /**
     * 列表二次校验：必须与详情/文档接口使用同一条访问策略，
     * 防止 SQL 预过滤和 Java 策略演进后出现“列表可见、打开被拒绝”。
     */
    public boolean canAccess(KbKnowledge kb, KbAccessAction action)
    {
        if (kb == null || kb.getKbId() == null)
        {
            return false;
        }
        Long userId = currentUserId();
        return KbAccessPolicy.isAllowed(kb, userId, currentDeptId(), isAdmin(), action,
            resolveMemberRole(kb.getKbId(), userId));
    }

    /**
     * 校验文档属于 path kbId，且对库有指定动作权限。
     */
    public KbDocument requireDocInKb(Long kbId, Long docId, KbAccessAction action)
    {
        requireKb(kbId, action);
        if (docId == null)
        {
            throw denyDoc();
        }
        KbDocument doc = kbDocumentMapper.selectKbDocumentById(docId);
        if (doc == null || doc.getKbId() == null || !kbId.equals(doc.getKbId()))
        {
            throw denyDoc();
        }
        return doc;
    }

    /**
     * 批量删除：每个 doc 必须属于 pathKbId。
     */
    public void requireDocsInKb(Long pathKbId, Long[] docIds, KbAccessAction action)
    {
        requireKb(pathKbId, action);
        if (docIds == null)
        {
            return;
        }
        for (Long docId : docIds)
        {
            if (docId == null)
            {
                throw denyDoc();
            }
            KbDocument doc = kbDocumentMapper.selectKbDocumentById(docId);
            if (doc == null || doc.getKbId() == null || !pathKbId.equals(doc.getKbId()))
            {
                throw denyDoc();
            }
        }
    }

    /** 智能体绑定：每个 kb 需要 USE */
    public void applyListScope(KbKnowledge query)
    {
        if (query == null)
        {
            query = new KbKnowledge();
        }
        KbAccessPolicy.applyListScope(query, currentUserId(), currentDeptId(), isAdmin());
    }

    public String resolveMemberRole(Long kbId, Long userId)
    {
        if (kbId == null || userId == null || kbAclMemberMapper == null)
        {
            return null;
        }
        try
        {
            KbAclMember m = kbAclMemberMapper.selectByKbAndUser(kbId, userId);
            return m == null ? null : m.getRole();
        }
        catch (Exception e)
        {
            // 表未迁移时降级为无成员
            return null;
        }
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

    private static boolean isAdmin()
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

    private static ServiceException denyKb()
    {
        return new ServiceException(DENY_MSG, HttpStatus.FORBIDDEN);
    }

    private static ServiceException denyDoc()
    {
        return new ServiceException(DENY_DOC_MSG, HttpStatus.FORBIDDEN);
    }
}
