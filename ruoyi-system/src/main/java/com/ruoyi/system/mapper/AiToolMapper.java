package com.ruoyi.system.mapper;

import java.util.List;
import com.ruoyi.system.domain.AiTool;

public interface AiToolMapper
{
    public AiTool selectAiToolById(Long toolId);
    public List<AiTool> selectAiToolList(AiTool aiTool);

    /**
     * 按 tool_code 精确查询(未删除的单条)。
     * <p>注意:不能复用 {@link #selectAiToolList},后者用 {@code tool_code like '%xx%'} 模糊匹配,
     * 会导致短工具名(如 search)误命中含该词的工具(batch_search / simulate_research_query),
     * upsert 走错 update 分支、新工具不入库 -- 曾导致 anysearch 的 search 工具丢失。
     */
    public AiTool selectAiToolByCode(String toolCode);

    /**
     * 按 tool_code 查询(含软删行)。
     * <p>用于"删除后重新同步/添加"时发现并复活,避免撞 uk_tool_code。
     * 与 {@link AiModelChannelMapper#selectAiModelChannelByPair} 同一模式。
     */
    public AiTool selectAiToolByCodeIncludeDeleted(String toolCode);

    public int insertAiTool(AiTool aiTool);
    public int updateAiTool(AiTool aiTool);

    /** 复活软删工具行:取消 del_flag 并覆写同步字段 */
    public int reactivateAiTool(AiTool aiTool);
    public int deleteAiToolById(Long toolId);
    public int deleteAiToolByIds(Long[] toolIds);

    /**
     * 按 MCP server 软删除其所有工具(删 server 时调用)。
     * @param mcpServerId MCP 服务 ID
     * @return 受影响行数
     */
    public int deleteAiToolByMcpServerId(Long mcpServerId);

    /** 查询某 MCP server 名下全部工具 ID(删 server 时先清理 ai_agent_tool 绑定用). */
    public java.util.List<Long> selectToolIdsByMcpServerId(Long mcpServerId);

    /**
     * 查询 MCP server 列表(供清理孤儿 MCP 工具时判断哪些 server 还存在)。
     * 返回的 AiMcpServer 仅含 mcpServerId 字段,用于构建存量 ID 集合。
     */
    public java.util.List<Long> selectMcpServerIds();
}
