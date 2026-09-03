package com.ruoyi.system.service;

import java.util.List;
import com.ruoyi.system.domain.AiMcpServer;

public interface IAiMcpServerService
{
    public AiMcpServer selectAiMcpServerById(Long mcpServerId);
    public List<AiMcpServer> selectAiMcpServerList(AiMcpServer aiMcpServer);
    public int insertAiMcpServer(AiMcpServer aiMcpServer);
    public int updateAiMcpServer(AiMcpServer aiMcpServer);
    public int deleteAiMcpServerById(Long mcpServerId);
    public int deleteAiMcpServerByIds(Long[] mcpServerIds);
}
