package com.ruoyi.system.mapper;

import java.util.List;
import java.util.Map;
import com.ruoyi.system.domain.AiMcpServer;

public interface AiMcpServerMapper
{
    public AiMcpServer selectAiMcpServerById(Long mcpServerId);
    public List<AiMcpServer> selectAiMcpServerList(AiMcpServer aiMcpServer);
    /** 含软删,删除后重加时复活避免撞 uk_mcp_server_code */
    public AiMcpServer selectAiMcpServerByCodeIncludeDeleted(String serverCode);
    public int insertAiMcpServer(AiMcpServer aiMcpServer);
    public int reactivateAiMcpServer(AiMcpServer aiMcpServer);
    public int updateAiMcpServer(AiMcpServer aiMcpServer);
    /**
     * 保活探测后回写健康状态(与渠道 checkHealth 同模式)。
     * 只更 health 字段,不触碰配置列。
     */
    public int updateHealthByServerCode(@org.apache.ibatis.annotations.Param("serverCode") String serverCode,
                                        @org.apache.ibatis.annotations.Param("healthStatus") String healthStatus,
                                        @org.apache.ibatis.annotations.Param("healthCheckTime") java.util.Date healthCheckTime);
    public int deleteAiMcpServerById(Long mcpServerId);
    public int deleteAiMcpServerByIds(Long[] mcpServerIds);

    /**
     * MCP 健康聚合(首页)。
     * @return map keys: total / enabled / healthy / unhealthy / unknown
     */
    Map<String, Object> selectMcpHealth();
}
