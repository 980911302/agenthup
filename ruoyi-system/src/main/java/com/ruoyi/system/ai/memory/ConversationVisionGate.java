package com.ruoyi.system.ai.memory;

import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.ruoyi.system.ai.agent.AgentContext;
import com.ruoyi.system.ai.agent.ModelInputModalities;
import com.ruoyi.system.domain.AiAgent;
import com.ruoyi.system.domain.AiModel;
import com.ruoyi.system.mapper.AiAgentMapper;
import com.ruoyi.system.mapper.AiModelMapper;

/**
 * 按 conversationId 解析当前 agent 模型支持的输入模态。
 *
 * <p>{@link DbChatMemory#get} 拿不到 {@link AgentContext},只能从
 * {@code sessionId:agentId} 回查 agent → model。短 TTL 缓存避免每轮重建都打库。
 * 查不到配置时返回空集,与 {@link AgentContext#inputModalities()} 口径一致。
 *
 * <p>类名保留 Vision 是历史原因;职责已从
 * 「是否支持视觉」扩展为「支持哪些输入模态」,判定统一交给 {@link ModelInputModalities}。
 */
@Component
public class ConversationVisionGate
{
    private static final Logger log = LoggerFactory.getLogger(ConversationVisionGate.class);
    static final long TTL_MS = 30_000L;

    @Autowired
    private AiAgentMapper agentMapper;
    @Autowired
    private AiModelMapper modelMapper;

    private final ConcurrentHashMap<Long, CacheEntry> cache = new ConcurrentHashMap<>();

    /** 该会话当前 agent 模型支持的输入模态;解析不出会话或 agent 时返回空集。 */
    public ModelInputModalities modalities(String conversationId)
    {
        Long agentId = ConversationIds.agentIdOf(conversationId);
        if (agentId == null)
        {
            return ModelInputModalities.of(null);
        }
        return modalitiesForAgent(agentId);
    }

    ModelInputModalities modalitiesForAgent(Long agentId)
    {
        long now = System.currentTimeMillis();
        CacheEntry hit = cache.get(agentId);
        if (hit != null && now - hit.at < TTL_MS)
        {
            return hit.value;
        }
        ModelInputModalities value = load(agentId);
        cache.put(agentId, new CacheEntry(value, now));
        return value;
    }

    /** @deprecated 语义已收窄为「支持图片」,新代码请用 {@link #modalities}。 */
    @Deprecated
    public boolean visionEnabled(String conversationId)
    {
        return modalities(conversationId).imageEnabled();
    }

    private ModelInputModalities load(Long agentId)
    {
        try
        {
            AiAgent agent = agentMapper.selectAiAgentById(agentId);
            if (agent == null || agent.getModelCode() == null || agent.getModelCode().isBlank())
            {
                return ModelInputModalities.of(null);
            }
            AiModel model = modelMapper.selectAiModelByModelCode(agent.getModelCode());
            return AgentContext.inputModalities(model);
        }
        catch (Exception e)
        {
            log.warn("查询模型输入模态失败 agentId={}: {}", agentId, e.getMessage());
            return ModelInputModalities.of(null);
        }
    }

    private record CacheEntry(ModelInputModalities value, long at)
    {
    }
}
