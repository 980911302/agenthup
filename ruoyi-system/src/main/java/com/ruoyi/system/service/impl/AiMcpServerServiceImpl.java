package com.ruoyi.system.service.impl;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.EncryptUtils;
import com.ruoyi.common.utils.bizcode.BizCodeGenerator;
import com.ruoyi.system.domain.AiMcpServer;
import com.ruoyi.system.mapper.AiMcpServerMapper;
import com.ruoyi.system.service.IAiMcpServerService;

/**
 * MCP服务器 服务层实现
 *
 * env 字段加密存储(用户编辑时整体解密回显)
 *
 * @author ruoyi
 */
@Service
public class AiMcpServerServiceImpl implements IAiMcpServerService
{
    @Autowired
    private AiMcpServerMapper aiMcpServerMapper;

    @Autowired
    private EncryptUtils encryptUtils;

    @Autowired
    private BizCodeGenerator bizCodeGenerator;

    @Override
    public AiMcpServer selectAiMcpServerById(Long mcpServerId)
    {
        AiMcpServer s = aiMcpServerMapper.selectAiMcpServerById(mcpServerId);
        if (s != null)
        {
            s.setEnv(encryptUtils.decrypt(s.getEnv()));
        }
        return s;
    }

    @Override
    public List<AiMcpServer> selectAiMcpServerList(AiMcpServer aiMcpServer)
    {
        // 列表不返回 env(详情才给,减少不必要的数据传输和敏感信息暴露)
        return aiMcpServerMapper.selectAiMcpServerList(aiMcpServer);
    }

    @Override
    public int insertAiMcpServer(AiMcpServer aiMcpServer)
    {
        if (aiMcpServer.getServerCode() == null || aiMcpServer.getServerCode().isEmpty())
        {
            aiMcpServer.setServerCode(bizCodeGenerator.next("MCP"));
        }
        if (aiMcpServer.getEnv() != null && !aiMcpServer.getEnv().isEmpty())
        {
            aiMcpServer.setEnv(encryptUtils.encrypt(aiMcpServer.getEnv()));
        }
        aiMcpServer.setHealthStatus("0");
        // 软删后同 code 重建:复活旧行,避免撞 uk_mcp_server_code
        AiMcpServer existing = aiMcpServerMapper.selectAiMcpServerByCodeIncludeDeleted(aiMcpServer.getServerCode());
        if (existing != null)
        {
            if (!"2".equals(existing.getDelFlag()))
            {
                throw new ServiceException("MCP 服务编码已存在: " + aiMcpServer.getServerCode());
            }
            aiMcpServer.setMcpServerId(existing.getMcpServerId());
            return aiMcpServerMapper.reactivateAiMcpServer(aiMcpServer);
        }
        return aiMcpServerMapper.insertAiMcpServer(aiMcpServer);
    }

    @Override
    public int updateAiMcpServer(AiMcpServer aiMcpServer)
    {
        if (aiMcpServer.getEnv() != null && !aiMcpServer.getEnv().isEmpty())
        {
            aiMcpServer.setEnv(encryptUtils.encrypt(aiMcpServer.getEnv()));
        }
        return aiMcpServerMapper.updateAiMcpServer(aiMcpServer);
    }

    @Override
    public int deleteAiMcpServerById(Long mcpServerId)
    {
        return aiMcpServerMapper.deleteAiMcpServerById(mcpServerId);
    }

    @Override
    public int deleteAiMcpServerByIds(Long[] mcpServerIds)
    {
        return aiMcpServerMapper.deleteAiMcpServerByIds(mcpServerIds);
    }
}
