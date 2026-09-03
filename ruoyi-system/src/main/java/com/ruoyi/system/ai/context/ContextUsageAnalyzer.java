package com.ruoyi.system.ai.context;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import com.ruoyi.system.ai.agent.AgentContextFactory;
import com.ruoyi.system.ai.agent.SubAgentToolCallback;
import com.ruoyi.system.ai.memory.ChatMessageRecorder;
import com.ruoyi.system.ai.memory.TokenEstimator;
import com.ruoyi.system.domain.AiAgent;
import com.ruoyi.system.domain.AiAgentChild;
import com.ruoyi.system.domain.AiTool;
import com.ruoyi.system.service.IAiAgentService;
import com.ruoyi.system.service.IAiToolService;
import com.ruoyi.system.tool.ToolCallbackRegistry;
import com.ruoyi.system.tool.ToolRegistryRefreshedEvent;

/**
 * 上下文占用分类计量。产出有序 {@link ContextSegment} 列表,供环图接口使用。
 *
 * <p>计量与装配共用同一套文本/定义产出(system 分段、工具往返、ToolDefinition),
 * 避免两套拼接逻辑随演进悄悄漂移。
 *
 * @author ruoyi
 */
@Component
public class ContextUsageAnalyzer
{
    private static final Logger log = LoggerFactory.getLogger(ContextUsageAnalyzer.class);

    /**
     * 工具 JSON 协议包裹(花括号/字段名)的经验常量。
     * 环图看相对占比,不必精确到计费级别。
     */
    private static final int TOOL_ENVELOPE_TOKENS = 8;

    @Autowired private ChatMessageRecorder chatMessageRecorder;
    @Autowired private TokenEstimator tokenEstimator;
    @Autowired private AgentContextFactory agentContextFactory;
    @Autowired private IAiAgentService aiAgentService;
    @Autowired private com.ruoyi.system.ai.agent.AgentAssemblyCache assemblyCache;
    @Autowired private IAiToolService aiToolService;
    @Autowired private com.ruoyi.system.service.IAiSkillService aiSkillService;
    @Autowired private ToolCallbackRegistry toolCallbackRegistry;

    /** key = toolCode, value = 该工具定义的 token 数。Registry 刷新时清空。 */
    private final ConcurrentMap<String, Integer> toolTokenCache = new ConcurrentHashMap<>();

    /**
     * 分析某会话/智能体当前上下文占用,按 tokens 降序返回分片。
     * <p>tokens&lt;=0 的分类跳过;内部异常吞掉后退化为仅 messages。
     */
    public List<ContextSegment> analyze(Long agentId, String sessionId,
                                        String conversationId, long lastSummaryId)
    {
        List<ContextSegment> list = new ArrayList<>();
        try
        {
            long msgSum = 0L;
            long toolRowTokens = 0L;
            try
            {
                if (conversationId != null)
                {
                    msgSum = chatMessageRecorder.sumTokensSinceSummary(conversationId, lastSummaryId);
                    toolRowTokens = chatMessageRecorder.sumToolTokensSinceSummary(
                            conversationId, lastSummaryId);
                }
            }
            catch (Exception e)
            {
                log.debug("消息 token 汇总失败: {}", e.getMessage());
            }
            // 工具往返(assistant 的 tool_calls + tool 结果)单独一段,从 messages 里扣除避免重复计。
            // 与 messages 一样是可压缩的 —— ContextCleaner 清的就是它。
            long messages = Math.max(0L, msgSum - toolRowTokens);
            if (messages > 0)
            {
                list.add(new ContextSegment("messages", "消息", messages, true));
            }
            if (toolRowTokens > 0)
            {
                list.add(new ContextSegment("toolCalls", "工具往返", toolRowTokens, true));
            }

            if (agentId == null)
            {
                return sortDesc(list);
            }
            AiAgent agent = assemblyCache.agent(agentId);
            if (agent == null)
            {
                return sortDesc(list);
            }

            addIfPositive(list, "systemPrompt", "系统提示词",
                    tokenEstimator.estimate(agentContextFactory.buildRoleSection(agent))
                            + tokenEstimator.estimate(agentContextFactory.buildCollabSection(agent)),
                    false);
            addIfPositive(list, "skills", "技能",
                    tokenEstimator.estimate(agentContextFactory.buildSkillSection(agent)),
                    false);

            long builtin = 0L;
            long mcp = 0L;
            if (agent.getToolIds() != null)
            {
                for (Long toolId : agent.getToolIds())
                {
                    AiTool tool = assemblyCache.tool(toolId);
                    if (tool == null || !"0".equals(tool.getStatus())
                            || !StringUtils.hasText(tool.getToolCode()))
                    {
                        continue;
                    }
                    // 从 Registry 直接取 definition,不走 build()(有副作用/可能 refresh)
                    ToolCallback cb = toolCallbackRegistry.get(tool.getToolCode());
                    if (cb == null)
                    {
                        continue;
                    }
                    int tokens = measureToolCached(tool.getToolCode(), cb);
                    if ("2".equals(tool.getToolType()))
                    {
                        mcp += tokens;
                    }
                    else
                    {
                        // tool_type=1 内置,及其它归内置
                        builtin += tokens;
                    }
                }
            }
            // 装配期自动生成的工具(不进 ai_tool 表,上面那轮遍历取不到),同样占 tools 数组的位置:
            //   - 挂了技能 -> loadSkill
            //   - 绑了生图模型 -> drawImage
            //   - 绑了视频模型 -> drawVideo
            //   - 绑了语音模型 -> speak
            // 不算进来会让「内置工具」这项系统性偏低。
            java.util.Map<Long, String> boundSkills = com.ruoyi.system.ai.agent.SkillLoadToolCallback
                    .collectBoundSkills(agent.getSkillIds(), assemblyCache::skill);
            if (!boundSkills.isEmpty())
            {
                builtin += measureDefinition(new com.ruoyi.system.ai.agent.SkillLoadToolCallback(
                        boundSkills, assemblyCache::skill).getToolDefinition());
            }
            if (StringUtils.hasText(agent.getImageModelCode()))
            {
                ToolCallback drawImage = toolCallbackRegistry.get("drawImage");
                if (drawImage != null)
                {
                    builtin += measureToolCached("drawImage", drawImage);
                }
            }
            if (StringUtils.hasText(agent.getVideoModelCode()))
            {
                builtin += measureDefinition(new com.ruoyi.system.ai.agent.VideoGenerationToolCallback(
                        agent.getVideoModelCode(), null, null, null).getToolDefinition());
            }
            if (StringUtils.hasText(agent.getTtsModelCode()))
            {
                builtin += measureDefinition(new com.ruoyi.system.ai.agent.SpeechGenerationToolCallback(
                        agent.getTtsModelCode(), null, null, null).getToolDefinition());
            }

            addIfPositive(list, "toolsBuiltin", "内置工具", builtin, false);
            addIfPositive(list, "toolsMcp", "MCP 工具", mcp, false);

            long subAgents = 0L;
            if (!CollectionUtils.isEmpty(agent.getChildAgents()))
            {
                for (AiAgentChild child : agent.getChildAgents())
                {
                    if (child == null || child.getChildAgentId() == null)
                    {
                        continue;
                    }
                    ToolDefinition def = SubAgentToolCallback.buildDefinition(child);
                    subAgents += measureDefinition(def);
                }
            }
            addIfPositive(list, "subAgents", "子智能体", subAgents, false);
        }
        catch (Exception e)
        {
            // 降级:不能让上下文面板拖挂整个聊天页
            log.warn("上下文分类计量失败,退化为 messages: {}", e.getMessage());
            list.clear();
            try
            {
                if (conversationId != null)
                {
                    long used = chatMessageRecorder.sumTokensSinceSummary(conversationId, lastSummaryId);
                    if (used > 0)
                    {
                        list.add(new ContextSegment("messages", "消息", used, true));
                    }
                }
            }
            catch (Exception ignored)
            {
            }
        }
        return sortDesc(list);
    }

    @EventListener
    public void onToolRegistryRefreshed(ToolRegistryRefreshedEvent event)
    {
        toolTokenCache.clear();
    }

    /** 测试/诊断用 */
    public void clearToolTokenCache()
    {
        toolTokenCache.clear();
    }

    private int measureToolCached(String toolCode, ToolCallback cb)
    {
        Integer cached = toolTokenCache.get(toolCode);
        if (cached != null)
        {
            return cached;
        }
        int tokens = measureDefinition(cb.getToolDefinition());
        toolTokenCache.put(toolCode, tokens);
        return tokens;
    }

    private int measureDefinition(ToolDefinition d)
    {
        if (d == null)
        {
            return 0;
        }
        return tokenEstimator.estimate(d.name())
                + tokenEstimator.estimate(d.description())
                + tokenEstimator.estimate(d.inputSchema())
                + TOOL_ENVELOPE_TOKENS;
    }

    private static void addIfPositive(List<ContextSegment> list, String key, String label,
                                      long tokens, boolean compactable)
    {
        if (tokens > 0)
        {
            list.add(new ContextSegment(key, label, tokens, compactable));
        }
    }

    private static List<ContextSegment> sortDesc(List<ContextSegment> list)
    {
        list.sort(Comparator.comparingLong(ContextSegment::tokens).reversed());
        return list;
    }
}
