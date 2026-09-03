package com.ruoyi.system.service;

import java.util.List;
import com.ruoyi.system.domain.AiAgent;

/**
 * 智能体 服务层
 *
 * @author ruoyi
 */
public interface IAiAgentService
{
    /**
     * 查询智能体详情(含技能/工具/子智能体配置)
     */
    public AiAgent selectAiAgentById(Long agentId);

    /** 查询当前登录用户可读取的智能体详情（本人创建或公共）。 */
    public AiAgent selectAccessibleAiAgentById(Long agentId);

    /**
     * 查询智能体列表
     */
    public List<AiAgent> selectAiAgentList(AiAgent aiAgent);

    /** 后台管理范围的智能体列表，不应用 desktop 使用端的可见性过滤。 */
    public List<AiAgent> selectAiAgentListForManagement(AiAgent aiAgent);

    /**
     * 新增智能体(含关联配置)
     */
    public int insertAiAgent(AiAgent aiAgent);

    /**
     * 修改智能体(含关联配置)
     */
    public int updateAiAgent(AiAgent aiAgent);

    /**
     * 删除智能体(逻辑删除,同时清理关联)
     */
    public int deleteAiAgentById(Long agentId);

    /**
     * 批量删除智能体
     */
    public int deleteAiAgentByIds(Long[] agentIds);
}
