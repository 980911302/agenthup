package com.ruoyi.system.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.system.domain.AiAgent;
import com.ruoyi.system.domain.AiAgentChild;

/**
 * 智能体表 数据层
 * 统一管理 ai_agent 主表 + ai_agent_skill / ai_agent_tool / ai_agent_child 关联表
 *
 * @author ruoyi
 */
public interface AiAgentMapper
{
    // ---- ai_agent 主表 ----
    public AiAgent selectAiAgentById(Long agentId);

    public List<AiAgent> selectAiAgentList(AiAgent aiAgent);

    /** 后台管理端列表，不附加「本人或公共」可见性条件。 */
    public List<AiAgent> selectAiAgentListForManagement(AiAgent aiAgent);

    /** 含软删,删除后重加时复活避免撞 uk_agent_code */
    public AiAgent selectAiAgentByCodeIncludeDeleted(String agentCode);

    public int insertAiAgent(AiAgent aiAgent);

    public int reactivateAiAgent(AiAgent aiAgent);

    public int updateAiAgent(AiAgent aiAgent);

    public int deleteAiAgentById(Long agentId);

    public int deleteAiAgentByIds(Long[] agentIds);

    // ---- ai_agent_skill 关联 ----
    public Long[] selectSkillIdsByAgentId(Long agentId);

    public int batchInsertAgentSkill(@Param("agentId") Long agentId, @Param("skillIds") Long[] skillIds);

    public int deleteAgentSkillByAgentId(Long agentId);

    // ---- ai_agent_tool 关联 ----
    public Long[] selectToolIdsByAgentId(Long agentId);

    public int batchInsertAgentTool(@Param("agentId") Long agentId, @Param("toolIds") Long[] toolIds);

    public int deleteAgentToolByAgentId(Long agentId);

    /** 物理删除指向指定工具集合的 agent_tool 绑定(删除 MCP 工具时连带清理,避免悬空绑定). */
    public int deleteAgentToolByToolIds(@Param("toolIds") Long[] toolIds);

    // ---- ai_agent_child 关联 ----
    public List<AiAgentChild> selectChildAgentsByAgentId(Long agentId);

    public int batchInsertAgentChild(@Param("list") List<AiAgentChild> list);

    public int deleteAgentChildByAgentId(Long agentId);
}
