package com.ruoyi.system.service.impl;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.bizcode.BizCodeGenerator;
import com.ruoyi.system.ai.AiConfigChangedEvent;
import com.ruoyi.system.ai.agent.AiAgentAccessPolicy;
import com.ruoyi.system.domain.AiAgent;
import com.ruoyi.system.domain.AiAgentChild;
import com.ruoyi.system.mapper.AiAgentMapper;
import com.ruoyi.system.service.IAiAgentService;

/**
 * 智能体 服务层实现
 * 管理 ai_agent 主表 + 技能/工具/子智能体三张关联表
 *
 * @author ruoyi
 */
@Service
public class AiAgentServiceImpl implements IAiAgentService
{
    @Autowired
    private AiAgentMapper aiAgentMapper;

    @Autowired
    private BizCodeGenerator bizCodeGenerator;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    /**
     * 查询智能体详情(含技能/工具/子智能体配置)
     */
    @Override
    public AiAgent selectAiAgentById(Long agentId)
    {
        AiAgent agent = aiAgentMapper.selectAiAgentById(agentId);
        if (agent != null)
        {
            agent.setSkillIds(aiAgentMapper.selectSkillIdsByAgentId(agentId));
            agent.setToolIds(aiAgentMapper.selectToolIdsByAgentId(agentId));
            agent.setChildAgents(aiAgentMapper.selectChildAgentsByAgentId(agentId));
        }
        return agent;
    }

    /**
     * 面向登录用户的详情读取。内部作业、缓存装配仍使用 selectAiAgentById，
     * 不能把请求权限上下文错误带入异步任务。
     */
    @Override
    public AiAgent selectAccessibleAiAgentById(Long agentId)
    {
        AiAgent agent = selectAiAgentById(agentId);
        return AiAgentAccessPolicy.canRead(agent, currentUsername()) ? agent : null;
    }

    /**
     * 查询智能体列表
     */
    @Override
    public List<AiAgent> selectAiAgentList(AiAgent aiAgent)
    {
        if (aiAgent == null)
        {
            aiAgent = new AiAgent();
        }
        AiAgentAccessPolicy.applyListScope(aiAgent, currentUsername());
        return aiAgentMapper.selectAiAgentList(aiAgent);
    }

    /**
     * 后台管理端读取。调用方已通过菜单权限控制，不能复用 desktop 的「本人或公共」范围。
     */
    @Override
    public List<AiAgent> selectAiAgentListForManagement(AiAgent aiAgent)
    {
        return aiAgentMapper.selectAiAgentListForManagement(aiAgent == null ? new AiAgent() : aiAgent);
    }

    /**
     * 新增智能体(含关联配置,agentCode 为空时自动生成)
     */
    @Override
    @Transactional
    public int insertAiAgent(AiAgent aiAgent)
    {
        aiAgent.setIsPublic(normalizeIsPublic(aiAgent.getIsPublic()));
        if (aiAgent.getAgentCode() == null || aiAgent.getAgentCode().isEmpty())
        {
            aiAgent.setAgentCode(bizCodeGenerator.next("AGT"));
        }
        // 软删后同 code 重建:复活旧行,避免撞 uk_agent_code
        AiAgent existing = aiAgentMapper.selectAiAgentByCodeIncludeDeleted(aiAgent.getAgentCode());
        if (existing != null)
        {
            if (!"2".equals(existing.getDelFlag()))
            {
                throw new ServiceException("智能体编码已存在: " + aiAgent.getAgentCode());
            }
            aiAgent.setAgentId(existing.getAgentId());
            int rows = aiAgentMapper.reactivateAiAgent(aiAgent);
            // 关联表先清再建,与 update 路径一致
            aiAgentMapper.deleteAgentSkillByAgentId(existing.getAgentId());
            aiAgentMapper.deleteAgentToolByAgentId(existing.getAgentId());
            aiAgentMapper.deleteAgentChildByAgentId(existing.getAgentId());
            insertRelations(aiAgent);
            publishAgentChanged(aiAgent.getAgentId());
            return rows;
        }
        int rows = aiAgentMapper.insertAiAgent(aiAgent);
        insertRelations(aiAgent);
        publishAgentChanged(aiAgent.getAgentId());
        return rows;
    }

    /**
     * 修改智能体(含关联配置)
     */
    @Override
    @Transactional
    public int updateAiAgent(AiAgent aiAgent)
    {
        if (aiAgent.getIsPublic() != null)
        {
            aiAgent.setIsPublic(normalizeIsPublic(aiAgent.getIsPublic()));
        }
        // 先删旧关联,再插新关联
        Long agentId = aiAgent.getAgentId();
        aiAgentMapper.deleteAgentSkillByAgentId(agentId);
        aiAgentMapper.deleteAgentToolByAgentId(agentId);
        aiAgentMapper.deleteAgentChildByAgentId(agentId);
        insertRelations(aiAgent);
        int rows = aiAgentMapper.updateAiAgent(aiAgent);
        publishAgentChanged(agentId);
        return rows;
    }

    /**
     * 删除智能体(逻辑删除,同时清理关联)
     */
    @Override
    @Transactional
    public int deleteAiAgentById(Long agentId)
    {
        aiAgentMapper.deleteAgentSkillByAgentId(agentId);
        aiAgentMapper.deleteAgentToolByAgentId(agentId);
        aiAgentMapper.deleteAgentChildByAgentId(agentId);
        int rows = aiAgentMapper.deleteAiAgentById(agentId);
        publishAgentChanged(agentId);
        return rows;
    }

    /**
     * 批量删除智能体
     */
    @Override
    @Transactional
    public int deleteAiAgentByIds(Long[] agentIds)
    {
        for (Long agentId : agentIds)
        {
            aiAgentMapper.deleteAgentSkillByAgentId(agentId);
            aiAgentMapper.deleteAgentToolByAgentId(agentId);
            aiAgentMapper.deleteAgentChildByAgentId(agentId);
        }
        int rows = aiAgentMapper.deleteAiAgentByIds(agentIds);
        for (Long agentId : agentIds)
        {
            publishAgentChanged(agentId);
        }
        return rows;
    }

    /** 缓存失效属尽力而为的旁路,广播失败不能让配置修改回滚(同 publishChannelChanged 口径) */
    private void publishAgentChanged(Long agentId)
    {
        if (agentId == null)
        {
            return;
        }
        try
        {
            eventPublisher.publishEvent(new AiConfigChangedEvent(AiConfigChangedEvent.Kind.AGENT, agentId));
        }
        catch (RuntimeException e)
        {
            org.slf4j.LoggerFactory.getLogger(AiAgentServiceImpl.class)
                    .warn("广播智能体变更事件失败,装配缓存可能仍持旧配置: agentId={}", agentId, e);
        }
    }

    /**
     * 插入关联配置(技能/工具/子智能体)
     */
    private void insertRelations(AiAgent aiAgent)
    {
        Long agentId = aiAgent.getAgentId();

        // 技能关联
        Long[] skillIds = aiAgent.getSkillIds();
        if (skillIds != null && skillIds.length > 0)
        {
            aiAgentMapper.batchInsertAgentSkill(agentId, skillIds);
        }

        // 工具关联
        Long[] toolIds = aiAgent.getToolIds();
        if (toolIds != null && toolIds.length > 0)
        {
            aiAgentMapper.batchInsertAgentTool(agentId, toolIds);
        }

        // 子智能体关联
        List<AiAgentChild> childAgents = aiAgent.getChildAgents();
        if (childAgents != null && !childAgents.isEmpty())
        {
            for (AiAgentChild child : childAgents)
            {
                child.setParentAgentId(agentId);
            }
            aiAgentMapper.batchInsertAgentChild(childAgents);
        }
    }

    /** 公共标识仅允许 0/1；新增及复活缺省均按私有处理。 */
    private static String normalizeIsPublic(String isPublic)
    {
        return "1".equals(isPublic) ? "1" : "0";
    }

    private static String currentUsername()
    {
        try
        {
            return SecurityUtils.getUsername();
        }
        catch (Exception e)
        {
            return null;
        }
    }
}
