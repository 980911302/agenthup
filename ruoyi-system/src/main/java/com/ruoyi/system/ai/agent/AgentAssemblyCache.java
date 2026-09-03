package com.ruoyi.system.ai.agent;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.ruoyi.system.ai.AiConfigChangedEvent;
import com.ruoyi.system.domain.AiAgent;
import com.ruoyi.system.domain.AiModel;
import com.ruoyi.system.domain.AiSkill;
import com.ruoyi.system.domain.AiTool;
import com.ruoyi.system.service.IAiAgentService;
import com.ruoyi.system.service.IAiModelService;
import com.ruoyi.system.service.IAiSkillService;
import com.ruoyi.system.service.IAiToolService;

/**
 * 智能体装配行缓存:agent / tool / skill / model 四类 DB 行的 30s TTL 进程内快照。
 *
 * <p>每轮对话装配原本要 11+2N+2S+k 条点查且成对重复(resolveTools 与
 * buildWorkspaceToolsSection 各查一遍工具行、buildSkillSection 与 collectBoundSkills
 * 各查一遍技能行),子智能体每次调用再全额重放。这些 DB 行只有管理端写会变;
 * 真正需要实时的 {@code ToolCallbackRegistry}(MCP 掉线)是内存注册表,不经过这里。</p>
 *
 * <p>失效语义:经 Service 的增删改由 {@link AiConfigChangedEvent} 即时失效;
 * 直改库不走 Service 时最多等 TTL(30s,与 {@code ToolPolicyService} 同口径,
 * 项目已接受该语义)。查不到不缓存 —— 删行后穿透查询在 TTL 内重查即自愈。</p>
 *
 * <p><b>返回的是共享实例,消费方必须只读</b>:装配链目前对域对象无 set 调用;
 * 将来要在装配中改写时,先做防御性拷贝再动,不要原地改缓存里的对象。</p>
 */
@Component
public class AgentAssemblyCache
{
    /**
     * 与 ToolPolicyService 的 TTL 同口径:后台直改库最多等这么久生效。
     * 包级可写(非 final)仅为测试调短,生产恒为 30s。
     */
    static long TTL_MS = 30_000L;

    private final IAiAgentService aiAgentService;
    private final IAiToolService aiToolService;
    private final IAiSkillService aiSkillService;
    private final IAiModelService aiModelService;

    private final ConcurrentHashMap<Long, Entry<AiAgent>> agents = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Entry<AiTool>> tools = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Entry<AiSkill>> skills = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Entry<AiModel>> modelsByCode = new ConcurrentHashMap<>();

    public AgentAssemblyCache(IAiAgentService aiAgentService, IAiToolService aiToolService,
                              IAiSkillService aiSkillService, IAiModelService aiModelService)
    {
        this.aiAgentService = aiAgentService;
        this.aiToolService = aiToolService;
        this.aiSkillService = aiSkillService;
        this.aiModelService = aiModelService;
    }

    /** 智能体(含 skillIds/toolIds/childAgents 关联,服务层一条调用四条 SQL) */
    public AiAgent agent(Long agentId)
    {
        if (agentId == null)
        {
            return null;
        }
        return lookup(agents, agentId, aiAgentService::selectAiAgentById);
    }

    public AiTool tool(Long toolId)
    {
        if (toolId == null)
        {
            return null;
        }
        return lookup(tools, toolId, aiToolService::selectAiToolById);
    }

    public AiSkill skill(Long skillId)
    {
        if (skillId == null)
        {
            return null;
        }
        return lookup(skills, skillId, aiSkillService::selectAiSkillById);
    }

    public AiModel modelByCode(String modelCode)
    {
        if (modelCode == null || modelCode.isBlank())
        {
            return null;
        }
        return lookup(modelsByCode, modelCode, aiModelService::selectByModelCode);
    }

    @EventListener
    public void onConfigChanged(AiConfigChangedEvent event)
    {
        switch (event.kind())
        {
            case AGENT -> agents.remove(event.id());
            case TOOL -> tools.remove(event.id());
            case SKILL -> skills.remove(event.id());
            // modelCode 本身可能被改,按 id 失效会漏掉旧 code 的条目,整表清
            case MODEL -> modelsByCode.clear();
        }
    }

    private <K, T> T lookup(ConcurrentHashMap<K, Entry<T>> map, K key, Function<K, T> loader)
    {
        long now = System.currentTimeMillis();
        Entry<T> hit = map.get(key);
        if (hit != null && now < hit.expiresAt())
        {
            return hit.value();
        }
        T loaded = loader.apply(key);
        if (loaded != null)
        {
            map.put(key, new Entry<>(loaded, now + TTL_MS));
        }
        else
        {
            map.remove(key);
        }
        return loaded;
    }

    private record Entry<T>(T value, long expiresAt)
    {
    }
}
