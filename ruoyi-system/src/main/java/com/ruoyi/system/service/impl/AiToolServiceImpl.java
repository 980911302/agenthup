package com.ruoyi.system.service.impl;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import com.ruoyi.system.ai.AiConfigChangedEvent;
import com.ruoyi.system.domain.AiTool;
import com.ruoyi.system.mapper.AiAgentMapper;
import com.ruoyi.system.mapper.AiToolMapper;
import com.ruoyi.system.service.IAiToolService;
import com.ruoyi.system.tool.ToolPolicyService;

@Service
public class AiToolServiceImpl implements IAiToolService
{
    @Autowired
    private AiToolMapper aiToolMapper;

    @Autowired
    private AiAgentMapper aiAgentMapper;

    @Autowired(required = false)
    private ToolPolicyService toolPolicyService;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Override
    public AiTool selectAiToolById(Long toolId) { return aiToolMapper.selectAiToolById(toolId); }

    @Override
    public List<AiTool> selectAiToolList(AiTool aiTool) { return aiToolMapper.selectAiToolList(aiTool); }

    @Override
    public int insertAiTool(AiTool aiTool)
    {
        int n = aiToolMapper.insertAiTool(aiTool);
        invalidatePolicy();
        publishToolChanged(aiTool.getToolId());
        return n;
    }

    @Override
    public int updateAiTool(AiTool aiTool)
    {
        int n = aiToolMapper.updateAiTool(aiTool);
        invalidatePolicy();
        publishToolChanged(aiTool.getToolId());
        return n;
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public int deleteAiToolById(Long toolId)
    {
        // 物理删除前先清理引用该工具的 agent_tool 绑定,避免悬空
        aiAgentMapper.deleteAgentToolByToolIds(new Long[] { toolId });
        int n = aiToolMapper.deleteAiToolById(toolId);
        invalidatePolicy();
        publishToolChanged(toolId);
        return n;
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public int deleteAiToolByIds(Long[] toolIds)
    {
        // 物理删除前先清理引用这批工具的 agent_tool 绑定,避免悬空
        if (toolIds != null && toolIds.length > 0)
        {
            aiAgentMapper.deleteAgentToolByToolIds(toolIds);
        }
        int n = aiToolMapper.deleteAiToolByIds(toolIds);
        invalidatePolicy();
        if (toolIds != null)
        {
            for (Long toolId : toolIds)
            {
                publishToolChanged(toolId);
            }
        }
        return n;
    }

    private void invalidatePolicy()
    {
        if (toolPolicyService != null)
        {
            toolPolicyService.invalidate();
        }
    }

    /** 缓存失效属尽力而为的旁路,广播失败不能让配置修改回滚 */
    private void publishToolChanged(Long toolId)
    {
        if (toolId == null)
        {
            return;
        }
        try
        {
            eventPublisher.publishEvent(new AiConfigChangedEvent(AiConfigChangedEvent.Kind.TOOL, toolId));
        }
        catch (RuntimeException e)
        {
            org.slf4j.LoggerFactory.getLogger(AiToolServiceImpl.class)
                    .warn("广播工具变更事件失败,装配缓存可能仍持旧配置: toolId={}", toolId, e);
        }
    }
}
