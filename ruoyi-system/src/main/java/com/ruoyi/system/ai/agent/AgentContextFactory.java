package com.ruoyi.system.ai.agent;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.ai.ChatModelFactory;
import com.ruoyi.system.ai.ImageModelFactory;
import com.ruoyi.system.ai.event.ChatEventSink;
import com.ruoyi.system.ai.event.UiArtifactEmitter;
import com.ruoyi.system.ai.memory.ChatMessageRecorder;
import com.ruoyi.system.ai.memory.ConversationIds;
import com.ruoyi.system.ai.memory.TokenEstimator;
import com.ruoyi.system.ai.run.AgentToolLoop;
import com.ruoyi.ai.contract.core.InvocationContext;
import com.ruoyi.ai.contract.kb.KnowledgeSearchPort;
import com.ruoyi.system.kb.search.KnowledgeSearchToolCallback;
import com.ruoyi.system.mapper.AiLlmCallMapper;
import com.ruoyi.system.domain.AiAgent;
import com.ruoyi.system.domain.AiAgentChild;
import com.ruoyi.system.domain.AiModel;
import com.ruoyi.system.domain.AiSkill;
import com.ruoyi.system.domain.AiTool;
import com.ruoyi.system.service.IAiAgentService;
import com.ruoyi.system.service.IAiChatSessionService;
import com.ruoyi.system.service.IAiSkillFileService;
import com.ruoyi.system.service.IAiSkillService;
import com.ruoyi.system.service.IAiToolService;
import com.ruoyi.system.tool.RecordingToolCallback;
import com.ruoyi.system.tool.ToolBudgetRegistry;
import com.ruoyi.system.tool.ToolCallbackRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * per-agent 装配模型、系统提示词与工具(§6.1)。
 *
 * <p>每次请求实时装配:工具列表可能因 MCP 掉线而变(ToolCallbackRegistry 是内存注册表,
 * 不经过 DB 行缓存);agent/tool/skill/model 等 DB 行走 {@link AgentAssemblyCache} 的
 * 30s TTL 快照,去掉了此前每轮成对重复的点查。
 *
 * @author ruoyi
 */
@Component
public class AgentContextFactory
{
    private static final Logger log = LoggerFactory.getLogger(AgentContextFactory.class);
    static final String DEFAULT_REASONING_EFFORT = "medium";

    /**
     * 本轮 @ 技能注入段的固定头部。三件事照抄 {@code MemoryRetriever.INJECTION_HEADER} 的经验:
     * 边界标签(让模型分得清这不是用户本轮说的话)、来源标注、<b>使用指令</b>(告诉它拿这个干嘛)。
     * 最后一句挡住重复加载 —— 正文已经在眼前,再调 loadSkill 就是白烧一次往返。
     */
    static final String TURN_SKILL_HEADER =
            "<turn_skills>\n本轮用户通过 @ 明确指定要使用的技能规则(系统提供,非用户输入原文)。"
                    + "请直接按下列规则执行,不要再调用 " + SkillLoadToolCallback.TOOL_NAME + " 取回它们。";

    @Autowired private IAiAgentService aiAgentService;
    @Autowired private IAiSkillService aiSkillService;
    @Autowired private IAiToolService aiToolService;
    /** 装配行缓存:agent/tool/skill/model 四类 DB 行的 30s TTL 快照,经 Service 的增删改即时失效 */
    @Autowired private AgentAssemblyCache assemblyCache;

    /**
     * 本轮 @ 技能注入正文的总长上限(字符)。超出后剩余技能跳过并告警 ——
     * 用户一次 @ 十几个技能不该把上下文顶爆,更不该顶穿 ContextOverflowGuard。
     */
    @Value("${ai.chat.skill.turn-inject-max-chars:20000}")
    private int turnSkillMaxChars;

    /**
     * 技能附件(渐进披露第三层)。required=false:裸 new 的单测实例退化成「技能没有附件」。
     */
    @Autowired(required = false)
    private IAiSkillFileService skillFileService;
    @Autowired private ChatModelFactory chatModelFactory;
    @Autowired private ImageModelFactory imageModelFactory;
    @Autowired private com.ruoyi.system.ai.VideoModelFactory videoModelFactory;
    @Autowired private com.ruoyi.system.ai.TtsModelFactory ttsModelFactory;
    @Autowired private KnowledgeSearchPort knowledgeSearchPort;
    @Autowired private ToolCallbackRegistry toolCallbackRegistry;
    @Autowired private ChatMessageRecorder recorder;
    @Autowired private IAiChatSessionService aiChatSessionService;
    @Autowired private AiLlmCallMapper aiLlmCallMapper;
    @Autowired private TokenEstimator tokenEstimator;
    @Autowired private com.ruoyi.system.ai.metering.CacheUsageProbe cacheUsageProbe;
    @Autowired private com.ruoyi.system.tool.AiToolProperties aiToolProperties;
    @Autowired private ToolBudgetRegistry budgetRegistry;
    @Autowired private com.ruoyi.system.tool.ToolConfirmBroker toolConfirmBroker;
    @Autowired private com.ruoyi.system.tool.ToolPolicyService toolPolicyService;
    @Autowired private com.ruoyi.system.tool.WorkspaceScopeService workspaceScopeService;
    @Autowired private com.ruoyi.system.ai.memory.ContextBudget contextBudget;
    @Autowired private com.ruoyi.system.ai.trace.TraceSpanRecorder traceRecorder;
    @Autowired private AgentToolLoop agentToolLoop;
    @Autowired private com.ruoyi.system.mapper.AiChatRunMapper aiChatRunMapper;
    @Autowired(required = false) private UiArtifactEmitter uiArtifactEmitter;
    // 渠道工具回传图片时按 fileId 从个人文件取回(见 ChannelToolCallback.loadMedia)
    @Autowired(required = false)
    private com.ruoyi.system.ai.userfile.IAiUserFileService aiUserFileService;
    @Autowired(required = false) private com.ruoyi.system.tool.channel.ChannelToolBroker channelToolBroker;
    @Autowired(required = false) private com.ruoyi.system.tool.channel.ChannelToolProperties channelToolProperties;

    /**
     * 为持久化运行装配顶层 agent。操作者与事件出口均显式传入，因执行线程不再是 HTTP 请求线程。
     */
    public AgentContext buildForRun(Long agentId, String sessionId,
                                    ChatEventSink eventSink, OperatorHolder operator)
    {
        return doBuild(agentId, sessionId, AgentCallDepth.root(), true,
                eventSink != null ? eventSink : ChatEventSink.noop(), operator, AgentRunOverrides.defaults());
    }

    /** 使用已落库的客户端覆盖项装配顶层默认智能体。 */
    public AgentContext buildForRun(Long agentId, String sessionId,
                                    ChatEventSink eventSink, OperatorHolder operator,
                                    AgentRunOverrides overrides)
    {
        return buildForRun(agentId, sessionId, eventSink, operator, overrides, null);
    }

    public AgentContext buildForRun(Long agentId, String sessionId,
                                    ChatEventSink eventSink, OperatorHolder operator,
                                    AgentRunOverrides overrides, String runId)
    {
        return doBuild(agentId, sessionId, AgentCallDepth.root(), true,
                eventSink != null ? eventSink : ChatEventSink.noop(), operator, null,
                overrides != null ? overrides : AgentRunOverrides.defaults(), runId);
    }

    /** 装配持久化运行中的子 agent。 */
    public AgentContext buildStateless(Long agentId, String sessionId, AgentCallDepth depth,
                                       ChatEventSink eventSink, OperatorHolder operator)
    {
        return buildStateless(agentId, sessionId, depth, eventSink, operator, null);
    }

    /**
     * 装配持久化运行中的子 agent,并以调用实例 {@code invId} 作为其内部事件的归属标签。
     *
     * <p>同一个子 agent 在一轮里可能被调用多次:内部工具/更深层嵌套 agent 的事件若仍按
     * agentCode 归属,多次调用的步骤在前端必然串进同一张卡片。invId 非空时取代
     * agentCode 成为该作用域的 owner 标签(见 doBuild)。
     */
    public AgentContext buildStateless(Long agentId, String sessionId, AgentCallDepth depth,
                                       ChatEventSink eventSink, OperatorHolder operator, String invId)
    {
        return doBuild(agentId, sessionId, depth, false,
                eventSink != null ? eventSink : ChatEventSink.noop(), operator, invId,
                AgentRunOverrides.defaults(), null);
    }

    /** 操作者身份快照,用于跨线程传递给 RecordingToolCallback */
    public record OperatorHolder(String username, Long userId, boolean admin, boolean unattended)
    {
        public OperatorHolder(String username, Long userId, boolean admin)
        {
            this(username, userId, admin, false);
        }
    }

    /**
     * 装配核心:取 agent 配置 -> 解析模型 -> 拼系统提示词 -> 挂普通工具 + 子 agent 工具。
     *
     * @param withMemory true=顶层,写 conversationId、owner=null;false=无状态,conversationId=null
     * @param operator   操作者身份(顶层在请求线程捕获;子 agent 由父链路传入,可为 null)
     */
    private AgentContext doBuild(Long agentId, String sessionId, AgentCallDepth depth,
                                 boolean withMemory, ChatEventSink eventSink, OperatorHolder operator)
    {
        return doBuild(agentId, sessionId, depth, withMemory, eventSink, operator, null,
                AgentRunOverrides.defaults(), null);
    }

    private AgentContext doBuild(Long agentId, String sessionId, AgentCallDepth depth,
                                 boolean withMemory, ChatEventSink eventSink, OperatorHolder operator,
                                 String invId)
    {
        return doBuild(agentId, sessionId, depth, withMemory, eventSink, operator, invId,
                AgentRunOverrides.defaults(), null);
    }

    private AgentContext doBuild(Long agentId, String sessionId, AgentCallDepth depth,
                                 boolean withMemory, ChatEventSink eventSink, OperatorHolder operator,
                                 AgentRunOverrides overrides)
    {
        return doBuild(agentId, sessionId, depth, withMemory, eventSink, operator, null, overrides, null);
    }

    private AgentContext doBuild(Long agentId, String sessionId, AgentCallDepth depth,
                                 boolean withMemory, ChatEventSink eventSink, OperatorHolder operator,
                                 String invId, AgentRunOverrides overrides, String runId)
    {
        AiAgent agent = assemblyCache.agent(agentId);
        if (agent == null)
        {
            throw new ServiceException("智能体不存在: " + agentId);
        }

        AiModel model = resolveAiModel(agent, overrides);
        Long modelId = model != null ? model.getModelId() : null;
        ChatModel chatModel = chatModelFactory.get(modelId);
        // 技能目录只认智能体自己配的技能,一个会话内恒定。
        // overrides.skillIds()(本轮 @ 进来的技能)刻意不参与装配 —— 它进 system prompt 会让
        // 请求前缀逐轮分叉,从技能指引段往后(剩余 system 段 + 全部历史 + 整个 tools 数组)
        // 全部落空,理由同 buildEnvSection 的注释。@ 的技能改由 ChatTurnRunner 注入当轮
        // user 消息(见 buildTurnSkillSection),只影响当轮尾部。
        String systemPrompt = buildSystemPrompt(agent, agent.getSkillIds());

        // owner 归属:顶层 agent(withMemory=true)的工具/子 agent 在顶层,owner=null;
        // 子 agent(withMemory=false)的内部事件优先归属调用实例 invId(同一 agent 一轮
        // 被调多次时不串卡),缺省回退自己的 code,前端据此嵌套展示。
        String ownerCode = withMemory ? null
                : (StringUtils.hasText(invId) ? invId : agent.getAgentCode());

        List<ToolCallback> tools = new ArrayList<>();
        tools.addAll(resolveTools(agent, sessionId, agent.getAgentId(), ownerCode, eventSink, operator));
        tools.addAll(resolveSubAgents(agent, sessionId, depth, agent.getAgentId(), ownerCode, eventSink, operator));
        // 智能体绑定了生图模型时,装配期自动生成一个 drawImage 工具(与子 agent 同模式:
        // 动态生成、不进 ai_tool 表、被 RecordingToolCallback 包装获得记账/事件流/会话绑定)。
        tools.addAll(resolveScreenshotTool(overrides, sessionId, agent.getAgentId(), ownerCode, eventSink, operator));
        tools.addAll(resolveImageTool(agent, sessionId, agent.getAgentId(), ownerCode, eventSink, operator));
        tools.addAll(resolveVideoTool(agent, sessionId, agent.getAgentId(), ownerCode, eventSink, operator));
        tools.addAll(resolveSpeechTool(agent, sessionId, agent.getAgentId(), ownerCode, eventSink, operator));
        // 挂了技能就自动带一个 loadSkill:系统提示词只给技能的适用场景,详细规则按需取。
        // 同样只喂 agent.getSkillIds():SkillLoadToolCallback 把技能名拼进了 tool description,
        // 名单一变工具定义就变,而工具定义是缓存前缀的一部分(Anthropic 官方口径下改工具定义
        // 直接作废 tools/system/messages 三级)。名单恒定,这块才不会逐轮重算。
        tools.addAll(resolveSkillTool(agent.getSkillIds(), sessionId, agent.getAgentId(), ownerCode, eventSink, operator));
        // 绑定了知识库时自动装配 searchKnowledge(带出处的向量检索)。
        tools.addAll(resolveKnowledgeTool(agent, sessionId, agent.getAgentId(), ownerCode, eventSink, operator));
        if (withMemory)
        {
            tools.addAll(resolveChannelTools(overrides, sessionId, runId, agent.getAgentId(),
                    ownerCode, eventSink, operator, tools));
        }

        // 关掉内部执行,工具由 AgentToolLoop 在循环外跑,中间才能插入 ContextCleaner。
        org.springframework.ai.openai.OpenAiChatOptions chatOptions =
                buildChatOptions(chatModel, tools, AgentContext.reasoningEnabled(model));

        logPrefixFingerprint(agentId, sessionId, systemPrompt, tools);

        return new AgentContext(agent.getAgentId(), agent.getAgentCode(),
                chatModel, chatOptions, tools, systemPrompt,
                withMemory ? ConversationIds.of(sessionId, agentId) : null,
                modelId, model,
                // 每个 agent 自己模型的输入预算:子智能体可能与父用不同模型,
                // 窗口上限不能共用父的值(见 ToolBudget.inputBudgetByAgent)
                contextBudget.inputBudget(model != null ? model.getContextWindow() : null,
                        model != null ? model.getMaxOutputTokens() : null));
    }

    /**
     * 打印请求前缀的指纹,用于排查 KV-cache 命中率异常。
     *
     * <p>上游按前缀匹配缓存,而前缀 = tools 定义 + systemPrompt + messages。前两者本应
     * 逐会话完全一致;一旦某个工具的 description 或 inputSchema 有一丁点不同(哪怕
     * 空白字符),从那个位置往后全部落空。这种差异从 {@code ai_llm_call} 的
     * prompt_tokens 上只能看出「差了几个 token」,定位不到是谁 —— 所以这里按工具逐个
     * 打 hash:开两个新会话对比日志,第一个对不上的工具就是元凶。
     *
     * <p>由 {@code ruoyi.ai.tool.log-prefix-fingerprint} 开关控制(默认关)。不用日志级别
     * 控制是因为 logback.xml 里写死了 {@code <logger name="com.ruoyi" level="info"/>},
     * 它会覆盖 application.yml 的 debug 设置,debug 日志根本出不来。
     */
    private void logPrefixFingerprint(Long agentId, String sessionId,
                                      String systemPrompt, List<ToolCallback> tools)
    {
        if (aiToolProperties == null || !aiToolProperties.isLogPrefixFingerprint())
        {
            return;
        }
        try
        {
            StringBuilder sb = new StringBuilder();
            for (ToolCallback cb : tools)
            {
                var def = cb.getToolDefinition();
                sb.append(def.name()).append('=')
                  .append(sha8(def.description())).append('/')
                  .append(sha8(def.inputSchema())).append(' ');
            }
            log.info("前缀指纹 agent={} session={} system={} tools[{}]: {}",
                    agentId, sessionId, sha8(systemPrompt), tools.size(), sb.toString().trim());
        }
        catch (Exception e)
        {
            log.warn("前缀指纹计算失败: {}", e.getMessage());
        }
    }

    /** SHA-256 前 8 个 hex 字符,够区分不够复原 */
    private static String sha8(String s)
    {
        if (s == null)
        {
            return "null";
        }
        try
        {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(8);
            for (int i = 0; i < 4; i++)
            {
                hex.append(String.format("%02x", d[i]));
            }
            return hex.toString();
        }
        catch (Exception e)
        {
            return "err";
        }
    }

    /**
     * 构造带工具定义、且关闭内部工具执行的 ChatOptions。
     * <p>从 ChatModel 默认 options 复制(保留 model/streamUsage 等),再覆写工具相关字段。
     */
    private org.springframework.ai.openai.OpenAiChatOptions buildChatOptions(
            ChatModel chatModel, List<ToolCallback> tools, boolean reasoningEnabled)
    {
        org.springframework.ai.openai.OpenAiChatOptions options;
        if (chatModel.getDefaultOptions() instanceof org.springframework.ai.openai.OpenAiChatOptions base)
        {
            options = org.springframework.ai.openai.OpenAiChatOptions.fromOptions(base);
        }
        else
        {
            options = org.springframework.ai.openai.OpenAiChatOptions.builder()
                    .streamUsage(true)
                    .build();
        }
        // false:ChatModel 遇 tool_calls 直接返回,由 AgentToolLoop 调 ToolCallingManager
        options.setInternalToolExecutionEnabled(false);
        // Spring AI 1.1.5 会把该属性序列化成 OpenAI Chat Completions 的
        // reasoning_effort。关闭时显式清空，避免复用模型默认 Options 时把旧值带进本轮。
        applyReasoningEnabled(options, reasoningEnabled);
        if (tools != null && !tools.isEmpty())
        {
            options.setToolCallbacks(tools);
        }
        else
        {
            options.setToolCallbacks(List.of());
        }
        return options;
    }

    /**
     * 将模型管理的 0/1 配置映射到 Spring AI 的单次请求 Options。
     *
     * <p>Spring AI 1.1.5 会将 {@code reasoningEffort} 序列化为 OpenAI Chat Completions
     * 的 {@code reasoning_effort}。关闭时清空，避免从默认 Options 复制后把旧值带进本轮。</p>
     */
    static void applyReasoningEnabled(org.springframework.ai.openai.OpenAiChatOptions options,
                                      boolean reasoningEnabled)
    {
        options.setReasoningEffort(reasoningEnabled ? DEFAULT_REASONING_EFFORT : null);
    }

    /**
     * 解析模型:AiAgent.modelCode(字符串)-> AiModel。
     * <p>modelCode 为空或查不到 -> null -> {@link ChatModelFactory#get} 抛「请选择对话模型」。
     * 返回完整 AiModel 以便计量归因与上下文预算(context_window / max_output_tokens)。
     */
    private AiModel resolveAiModel(AiAgent agent, AgentRunOverrides overrides)
    {
        if (overrides != null && StringUtils.hasText(overrides.modelCode()))
        {
            return assemblyCache.modelByCode(overrides.modelCode());
        }
        if (StringUtils.hasText(agent.getModelCode()))
        {
            return assemblyCache.modelByCode(agent.getModelCode());
        }
        return null;
    }

    /**
     * 按 toolIds 解析并挂载普通工具(§6.2)。
     * <p>不反射:按 ai_tool.tool_code(= ToolDefinition.name())从 Registry 取 callback。
     * MCP 掉线或工具禁用时跳过(降级为少一个工具,R4)。
     */
    private List<ToolCallback> resolveTools(AiAgent agent, String sessionId, Long agentId,
                                            String ownerCode, ChatEventSink eventSink, OperatorHolder operator)
    {
        if (agent.getToolIds() == null || agent.getToolIds().length == 0)
        {
            return List.of();
        }
        // 先把 toolId -> AiTool 解析出来(status 校验),再按 toolCode 取 callback
        List<AiTool> enabled = new ArrayList<>();
        for (Long toolId : agent.getToolIds())
        {
            AiTool tool = assemblyCache.tool(toolId);
            if (tool != null && "0".equals(tool.getStatus()))
            {
                enabled.add(tool);
            }
        }
        if (enabled.isEmpty())
        {
            return List.of();
        }

        // 按 enabled 的顺序占槽回填,而不是取到一个追加一个 ——
        // 工具定义位于请求最前面,顺序一变就让上游 KV-cache 整体失配(tools/system/messages 三层全废)。
        // 必须保证:无论是否走过下面的刷新重试,同一个 agent 得到的工具顺序完全一致。
        ToolCallback[] slots = new ToolCallback[enabled.size()];
        List<Integer> missing = new ArrayList<>();
        for (int i = 0; i < enabled.size(); i++)
        {
            ToolCallback cb = toolCallbackRegistry.get(enabled.get(i).getToolCode());
            if (cb != null)
            {
                slots[i] = wrapRecording(cb, sessionId, agentId, ownerCode, eventSink, operator);
            }
            else
            {
                missing.add(i);
            }
        }

        // 有缺失就自愈刷新一次再重试(修复"启动刷新没跑到 / MCP 刚重连还没刷"导致整批工具取不到)。
        // MCP 真掉线的工具刷新后仍 null,照常跳过;只刷一次,避免每请求高频扫描。
        if (!missing.isEmpty())
        {
            toolCallbackRegistry.refreshIfDue(5_000L);
            for (int i : missing)
            {
                AiTool tool = enabled.get(i);
                ToolCallback cb = toolCallbackRegistry.get(tool.getToolCode());
                if (cb != null)
                {
                    slots[i] = wrapRecording(cb, sessionId, agentId, ownerCode, eventSink, operator);
                }
                else
                {
                    log.warn("工具[{}]在容器中不可用(刷新后仍缺失,当前索引 {} 个工具),本次跳过",
                            tool.getToolCode(), toolCallbackRegistry.size());
                }
            }
        }

        List<ToolCallback> list = new ArrayList<>(enabled.size());
        for (ToolCallback cb : slots)
        {
            if (cb != null)
            {
                list.add(cb);
            }
        }
        return list;
    }

    /**
     * 把 ToolCallback 包成 RecordingToolCallback,带上操作者身份与预算注册表。
     * <p>operator 为 null 时走无操作者重载(兼容旧调用方,如非登录上下文)。
     * <p>withBudget 后 get(sessionId) 未 open 时仍放行 —— 只在 ChatRun 生命周期内限制。
     */
    private ToolCallback wrapRecording(ToolCallback cb, String sessionId, Long agentId,
                                       String ownerCode, ChatEventSink eventSink, OperatorHolder operator)
    {
        com.ruoyi.system.tool.ToolConfirmBroker confirm =
                (operator != null && operator.unattended()) ? null : toolConfirmBroker;
        if (operator != null)
        {
            return new RecordingToolCallback(cb, sessionId, agentId, null,
                    recorder, eventSink, ownerCode,
                    operator.username(), operator.userId(), operator.admin())
                    .withBudget(budgetRegistry)
                    .withConfirm(confirm)
                    .withPolicy(toolPolicyService)
                    .withWorkspaceKey(resolveWorkspaceKey(sessionId))
                    .withMcpWorkspaceRouting(aiToolProperties.isExecToolsDelegatedToMcp())
                    .withTracing(traceRecorder)
                    .withUiEmitter(uiArtifactEmitter);
        }
        return new RecordingToolCallback(cb, sessionId, agentId, null,
                recorder, eventSink, ownerCode)
                .withBudget(budgetRegistry)
                .withConfirm(confirm)
                .withPolicy(toolPolicyService)
                .withWorkspaceKey(resolveWorkspaceKey(sessionId))
                .withMcpWorkspaceRouting(aiToolProperties.isExecToolsDelegatedToMcp())
                .withTracing(traceRecorder)
                .withUiEmitter(uiArtifactEmitter);
    }

    private String resolveWorkspaceKey(String sessionId)
    {
        return workspaceScopeService != null
                ? workspaceScopeService.resolveWorkspaceKey(sessionId)
                : sessionId;
    }

    /**
     * 解析子 agent 工具(§5.2/§6.1):把每个 childAgent 包成 {@link SubAgentToolCallback}。
     *
     * <p>深度已达上限时不再挂(模型看不到自然不会调,比抛异常友好,§5.4);
     * 子 agent 调用记录由 {@code SubAgentToolCallback} 自己写(toolSource="agent"),
     * 不再包 RecordingToolCallback -- 否则 source 判断会错成 "builtin"。
     *
     * @param agent        当前 agent(取其 childAgents 列表)
     * @param sessionId    会话ID
     * @param depth        当前调用链深度
     * @param parentAgentId 父 agent ID(用于工具调用记录的 agentId 字段)
     * @param ownerCode    包裹这些子 agent 的上一层 code(顶层为 null),作为子 agent step 的 owner
     * @param eventSink    对话事件出口(传给子 agent 工具回调)
     */
    private List<ToolCallback> resolveSubAgents(AiAgent agent, String sessionId,
                                                AgentCallDepth depth, Long parentAgentId,
                                                String ownerCode, ChatEventSink eventSink, OperatorHolder operator)
    {
        if (CollectionUtils.isEmpty(agent.getChildAgents()) || depth.isMaxed())
        {
            return List.of();
        }
        List<ToolCallback> list = new ArrayList<>();
        agent.getChildAgents().stream()
                .sorted(Comparator.comparing(AiAgentChild::getSort,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .forEach(c -> {
                    // 子 agent 被软删时 childAgentCode/Name 为 null,这里仍可挂(name 兜底 agent_id)
                    if (c.getChildAgentId() == null)
                    {
                        return;
                    }
                    list.add(new SubAgentToolCallback(c, this, sessionId, depth,
                            parentAgentId, aiChatSessionService, recorder, eventSink, ownerCode,
                            aiLlmCallMapper, tokenEstimator, operator, cacheUsageProbe, budgetRegistry,
                            aiToolProperties.getSubAgentIdleTimeoutSeconds(), traceRecorder,
                            agentToolLoop));
                });
        return list;
    }

    /**
     * 服务端动态工具 -> 客户端等价工具。客户端声明了后者时,前者装配期跳过。
     *
     * <p>{@code captureScreenshot} 用服务器上的无头浏览器按 URL 截图:看不到用户实际打开的
     * 标签页,也没有用户的登录态。浏览器插件的 {@code screenshotTab} 截的是用户真正在看的
     * 画面。两个都摆在模型面前它会选错(实测选了服务端那个,白等 45 秒超时),所以客户端
     * 提供了等价能力时就不再装配服务端版本。</p>
     */
    private static final java.util.Map<String, String> CLIENT_SUPERSEDES =
            java.util.Map.of(ScreenshotToolCallback.TOOL_NAME, "screenshotTab");

    /** 客户端是否已声明了可取代该服务端工具的等价能力。 */
    private boolean supersededByClient(AgentRunOverrides overrides, String serverToolName)
    {
        String clientEquivalent = CLIENT_SUPERSEDES.get(serverToolName);
        if (clientEquivalent == null || overrides == null || overrides.clientTools() == null)
        {
            return false;
        }
        for (com.ruoyi.system.tool.channel.ChannelToolDef def : overrides.clientTools())
        {
            if (def != null && clientEquivalent.equals(def.name()))
            {
                return true;
            }
        }
        return false;
    }

    private List<ToolCallback> resolveScreenshotTool(AgentRunOverrides overrides, String sessionId,
                                                     Long agentId, String ownerCode,
                                                     ChatEventSink eventSink, OperatorHolder operator)
    {
        // mcp 模式:截图整体交给远端 tool-mcp-server,本地不再装配(captureScreenshot 同名会冲突)。
        if (aiToolProperties.isExecToolsDelegatedToMcp())
        {
            return List.of();
        }
        // 子 agent 拿到的 overrides 不带客户端清单,它们跑在后台、够不到客户端,保留服务端版本。
        if (supersededByClient(overrides, ScreenshotToolCallback.TOOL_NAME))
        {
            return List.of();
        }
        ToolCallback cb = new ScreenshotToolCallback(aiToolProperties);
        return List.of(wrapRecording(cb, sessionId, agentId, ownerCode, eventSink, operator));
    }

    /**
     * 解析生图工具:智能体绑定了 imageModelCode 时,装配一个 {@link ImageGenerationToolCallback}。
     *
     * <p>与 {@link #resolveSubAgents} 同模式 -- 装配期动态生成、不进 ai_tool 表、不进 Registry 索引。
     * 用 {@link #wrapRecording} 包一层,自动获得 tool_start/tool_end 事件、记账、会话沙箱绑定
     * (生图落盘需要 sessionId,由 RecordingToolCallback 绑定的 WorkspaceContext 提供)。
     *
     * <p>未绑定生图模型时返回空列表,零开销,前端工具列表也不显示 drawImage。
     */
    private List<ToolCallback> resolveImageTool(AiAgent agent, String sessionId, Long agentId,
                                                String ownerCode, ChatEventSink eventSink, OperatorHolder operator)
    {
        if (!StringUtils.hasText(agent.getImageModelCode()))
        {
            return List.of();
        }
        ToolCallback cb = new ImageGenerationToolCallback(
                agent.getImageModelCode(), imageModelFactory, assemblyCache::modelByCode, aiToolProperties);
        return List.of(wrapRecording(cb, sessionId, agentId, ownerCode, eventSink, operator));
    }

    /**
     * 解析视频工具:绑定了 videoModelCode 时装配 {@link VideoGenerationToolCallback}。
     * 与生图平行,不进 ai_tool 表。
     */
    private List<ToolCallback> resolveVideoTool(AiAgent agent, String sessionId, Long agentId,
                                                String ownerCode, ChatEventSink eventSink, OperatorHolder operator)
    {
        if (!StringUtils.hasText(agent.getVideoModelCode()))
        {
            return List.of();
        }
        ToolCallback cb = new VideoGenerationToolCallback(
                agent.getVideoModelCode(), videoModelFactory, assemblyCache::modelByCode, aiToolProperties);
        return List.of(wrapRecording(cb, sessionId, agentId, ownerCode, eventSink, operator));
    }

    /**
     * 解析语音工具:绑定了 ttsModelCode 时装配 {@link SpeechGenerationToolCallback}。
     * 与生图/视频平行,不进 ai_tool 表。
     */
    private List<ToolCallback> resolveSpeechTool(AiAgent agent, String sessionId, Long agentId,
                                                 String ownerCode, ChatEventSink eventSink, OperatorHolder operator)
    {
        if (!StringUtils.hasText(agent.getTtsModelCode()))
        {
            return List.of();
        }
        ToolCallback cb = new SpeechGenerationToolCallback(
                agent.getTtsModelCode(), ttsModelFactory, assemblyCache::modelByCode, aiToolProperties);
        return List.of(wrapRecording(cb, sessionId, agentId, ownerCode, eventSink, operator));
    }

    /**
     * 解析知识检索工具:当前会话选定了知识库时装配 {@link KnowledgeSearchToolCallback}。
     * <p>知识库从「智能体固定绑定」下沉为「会话级多选」:这里按 sessionId 查
     * ai_chat_session_kb(会话建立/中途修改时已持久化并做过 USE 权限校验)。
     * 子 agent 复用同一 sessionId,自然继承父会话的知识库选择;空选择则不装配。
     * <p>与 drawImage 同模式 —— 装配期动态生成、不进 ai_tool 表。
     */
    private List<ToolCallback> resolveKnowledgeTool(AiAgent agent, String sessionId, Long agentId,
                                                    String ownerCode, ChatEventSink eventSink, OperatorHolder operator)
    {
        List<Long> kbIds = aiChatSessionService.getSessionKbIds(sessionId);
        if (kbIds.isEmpty())
        {
            return List.of();
        }
        InvocationContext context = new InvocationContext(null,
                operator != null && operator.userId() != null ? String.valueOf(operator.userId()) : null,
                operator != null && operator.admin() ? java.util.Set.of("ADMIN") : java.util.Set.of(),
                null, null, null, java.util.Map.of());
        ToolCallback cb = new KnowledgeSearchToolCallback(
                kbIds.stream().map(String::valueOf).toList(), knowledgeSearchPort, context);
        return List.of(wrapRecording(cb, sessionId, agentId, ownerCode, eventSink, operator));
    }

    /**
     * 会话行上的渠道工具，仅顶层装配，且必须追加在 tools 数组末尾。
     */
    List<ToolCallback> resolveChannelTools(AgentRunOverrides overrides, String sessionId, String runId,
                                           Long agentId, String ownerCode, ChatEventSink eventSink,
                                           OperatorHolder operator, List<ToolCallback> already)
    {
        if (channelToolBroker == null || channelToolProperties == null
                || !channelToolProperties.allowedFor(operator != null ? operator.userId() : null))
        {
            return List.of();
        }
        List<com.ruoyi.system.tool.channel.ChannelToolDef> defs =
                overrides != null ? overrides.clientTools() : List.of();
        if (defs == null || defs.isEmpty())
        {
            return List.of();
        }
        java.util.Set<String> taken = new java.util.HashSet<>();
        if (already != null)
        {
            for (ToolCallback cb : already)
            {
                if (cb != null && cb.getToolDefinition() != null && cb.getToolDefinition().name() != null)
                {
                    taken.add(cb.getToolDefinition().name());
                }
            }
        }
        List<ToolCallback> out = new ArrayList<>();
        for (com.ruoyi.system.tool.channel.ChannelToolDef def : defs)
        {
            if (def == null || taken.contains(def.name()))
            {
                log.warn("渠道工具与已装配工具重名，已跳过: name={} sessionId={}",
                        def != null ? def.name() : null, sessionId);
                continue;
            }
            taken.add(def.name());
            ToolCallback cb = new com.ruoyi.system.tool.channel.ChannelToolCallback(
                    def, channelToolBroker, sessionId, runId, eventSink, ownerCode,
                    operator != null ? operator.userId() : null, aiUserFileService);
            out.add(wrapRecording(cb, sessionId, agentId, ownerCode, eventSink, operator));
        }
        return out;
    }

    /**
     * 拼装系统提示词(§6.5):角色(G1)+ 技能指引 + 协作说明。
     * <p>拼接顺序与分隔符固定,计量侧拆段估算依赖此结构,勿改文案。
     */
    private String buildSystemPrompt(AiAgent agent, Long[] skillIds)
    {
        StringBuilder sb = new StringBuilder();
        sb.append(buildRoleSection(agent));
        String skills = buildSkillSection(skillIds);
        if (StringUtils.hasText(skills))
        {
            sb.append("\n\n").append(skills);
        }
        String collab = buildCollabSection(agent);
        if (StringUtils.hasText(collab))
        {
            sb.append("\n\n").append(collab);
        }
        String workspace = buildWorkspaceToolsSection(agent);
        if (StringUtils.hasText(workspace))
        {
            sb.append("\n\n").append(workspace);
        }
        String env = buildEnvSection();
        if (StringUtils.hasText(env))
        {
            sb.append("\n\n").append(env);
        }
        return sb.toString();
    }

    /**
     * 环境信息。**只放对所有会话相同的内容**。
     *
     * <p>这段位于 system prompt 末尾,而 Spring AI 的 {@code ChatCompletionRequest}
     * 字段序是 {@code messages → model → stream → tools} —— messages 排在 tools 前面。
     * 所以 system prompt 末尾只要分叉一个字符,后面整个 tools 数组(本项目约 4100 token)
     * 全部落空。
     *
     * <p>曾经在这里放过 sessionId 与含 sessionId 的沙箱绝对路径,实测每个新会话首轮
     * 只命中 768 token、miss 4088(前缀指纹显示 16 个工具 hash 全同、唯一差异就是本段)。
     * 逐会话变化的东西一律不能进 system prompt:真要给模型,做成工具按需取,或者放进
     * 当轮 user 消息(那样只影响当轮尾部,不会让 tools 全废)。
     *
     * <p>时间同理不放 —— 它每次请求都变。需要时用 getCurrentTime 工具。
     */
    String buildEnvSection()
    {
        // 不能写绝对路径或 sessionId:messages 在 tools 前面,末尾一分叉整段工具定义 miss。
        // 运行时相对路径由 ProjectPaths 落到会话沙箱,前端工作区才能看见。
        return """
                ## 环境
                - 文件与命令使用相对路径，写入当前会话工作区；不要使用 /app 或其它绝对路径
                - 生图与截图也在会话工作区（路径见工具返回）""";
    }

    String buildWorkspaceToolsSection(AiAgent agent)
    {
        java.util.LinkedHashSet<String> codes = new java.util.LinkedHashSet<>();
        if (agent.getToolIds() != null)
        {
            for (Long toolId : agent.getToolIds())
            {
                AiTool tool = assemblyCache.tool(toolId);
                if (tool != null && StringUtils.hasText(tool.getToolCode()))
                {
                    codes.add(tool.getToolCode());
                }
            }
        }
        return com.ruoyi.system.tool.WorkspaceToolsPrompt.buildSection(codes);
    }

    /** 按 runId 取用户原话,给子智能体分辨「用户说了什么」与「上级交办」。 */
    public String lookupRunInputText(String runId)
    {
        if (!StringUtils.hasText(runId) || aiChatRunMapper == null)
        {
            return null;
        }
        com.ruoyi.system.domain.AiChatRun run = aiChatRunMapper.selectAiChatRunById(runId);
        return run == null ? null : run.getInputText();
    }

    /** 角色段(G1)。agentRole 为空时兜底一句。 */
    public String buildRoleSection(AiAgent agent)
    {
        return StringUtils.hasText(agent.getAgentRole())
                ? agent.getAgentRole()
                : "你是 " + agent.getAgentName() + "。";
    }

    /**
     * 技能指引段。无 skillIds 时返回空串(不含前导换行)。
     * <p>与历史行为一致:只要 skillIds 非空就输出标题,即使技能均已禁用。
     *
     * <p><b>只放 description,不放 promptTemplate</b>:后者是详细操作规则,动辄 1~2K token
     * (实测五个技能合计 4336 token),全程常驻系统提示词且压缩不掉。
     * description 是「什么时候用这个技能」的一句话,合计仅 263 token,降幅 94%。
     * 详细规则改由 {@link SkillLoadToolCallback} 按需取回(渐进披露)。
     */
    public String buildSkillSection(AiAgent agent)
    {
        return buildSkillSection(agent != null ? agent.getSkillIds() : null);
    }

    /** 使用已快照的有效技能列表构建指引，避免执行线程重新读取智能体关联。 */
    public String buildSkillSection(Long[] skillIds)
    {
        if (skillIds == null || skillIds.length == 0)
        {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("## 技能指引\n");
        sb.append("下列技能只给出适用场景。判断本次任务要用某个技能时,先用 ")
                .append(SkillLoadToolCallback.TOOL_NAME)
                .append(" 取回它的完整操作规则再动手。\n");
        for (Long skillId : skillIds)
        {
            AiSkill skill = assemblyCache.skill(skillId);
            if (skill != null && "0".equals(skill.getStatus()))
            {
                String desc = StringUtils.hasText(skill.getDescription())
                        ? skill.getDescription()
                        : skill.getSkillName();
                sb.append("- ").append(skill.getSkillName()).append(": ")
                        .append(desc).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 拷贝技能附件进会话沙箱并生成清单文本。
     *
     * <p>拷贝而不是直接读源目录,是因为 {@code WorkspaceSandbox} 的越界校验是安全底线,
     * 不能为了读技能文件去松动它;拷进沙箱后现成的 read/grep/find 全部可用。
     *
     * <p>任何失败都降级成空串:附件是锦上添花,拿不到不该让技能加载失败。
     */
    private String prepareSkillAttachments(Long skillId, String skillCode, String sessionId)
    {
        if (skillFileService == null || skillId == null)
        {
            return "";
        }
        try
        {
            java.nio.file.Path root = com.ruoyi.system.tool.WorkspaceSandbox
                    .resolveRoot(aiToolProperties, sessionId);
            skillFileService.copyToSession(skillId, skillCode, root);
            return skillFileService.describeForPrompt(skillId, skillCode);
        }
        catch (Exception e)
        {
            log.warn("技能附件准备失败 skillId={} session={}: {}", skillId, sessionId, e.getMessage());
            return "";
        }
    }

    /**
     * 本轮 @ 技能的规则正文段(由 {@code ChatTurnRunner} 注入当轮 user 消息,<b>不进系统提示词</b>)。
     *
     * <p><b>为什么直接给全文而不是走 loadSkill</b>:@ 是用户明确点名,不存在「要不要用」的判断,
     * 渐进披露在这里只会白白多一次模型往返。对标 Claude Code —— 模型自己判断要用才渐进披露,
     * 用户用 {@code /} 明确调用就当场全文加载。
     *
     * <p><b>只取不在智能体固定技能里的那些</b>:已在目录里的技能,系统提示词已列出、
     * {@code loadSkill} 也能取回,再注入一遍纯属重复。
     *
     * <p><b>调用方必须只把它拼进发送版文本</b>,落库仍写用户原话 —— 与跨会话长期记忆同一条红线
     * (记忆系统 spec §7.1)。注入不落库,历史里才不会逐轮累积技能正文:用户把 @ 粘着聊 20 轮,
     * 历史里始终只有原话,当轮才有这一份。
     *
     * @param agentId      当前智能体
     * @param turnSkillIds 本轮生效技能(智能体自带 ∪ 用户 @ 选择)
     * @return 注入段;没有 @ 技能、技能已停用或没配正文时返回空串
     */
    public String buildTurnSkillSection(Long agentId, Long[] turnSkillIds, String sessionId)
    {
        if (turnSkillIds == null || turnSkillIds.length == 0)
        {
            return "";
        }
        java.util.Set<Long> bound = new java.util.HashSet<>();
        AiAgent agent = assemblyCache.agent(agentId);
        if (agent != null && agent.getSkillIds() != null)
        {
            for (Long id : agent.getSkillIds())
            {
                if (id != null)
                {
                    bound.add(id);
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        int used = 0;
        for (Long skillId : turnSkillIds)
        {
            if (skillId == null || bound.contains(skillId))
            {
                continue;
            }
            AiSkill skill = assemblyCache.skill(skillId);
            if (skill == null || !"0".equals(skill.getStatus())
                    || !StringUtils.hasText(skill.getPromptTemplate()))
            {
                continue;
            }
            String template = skill.getPromptTemplate();
            if (used + template.length() > turnSkillMaxChars)
            {
                log.warn("本轮 @ 技能正文超出 {} 字符上限,跳过 skillId={} name={}",
                        turnSkillMaxChars, skillId, skill.getSkillName());
                continue;
            }
            used += template.length();
            sb.append("\n\n### 技能:").append(skill.getSkillName()).append('\n').append(template);
            // @ 的技能同样带附件:与 loadSkill 走同一条准备逻辑,清单同样只落在当轮消息里
            sb.append(prepareSkillAttachments(skillId, skill.getSkillCode(), sessionId));
        }
        if (sb.length() == 0)
        {
            return "";
        }
        return TURN_SKILL_HEADER + sb + "\n</turn_skills>";
    }

    /**
     * 解析技能加载工具:agent 挂了技能就自动装配一个 {@code loadSkill}。
     *
     * <p>与 {@link #resolveImageTool} 同模式 —— 装配期动态生成、不进 ai_tool 表。
     * <b>自动挂载而不是让用户手动绑</b>:系统提示词里已经写了「先用 loadSkill 取规则」,
     * 工具没挂上会让模型扑空;而挂载条件与技能存在与否天然一致。
     *
     * <p>技能全部被禁用时返回空列表(此时提示词里也没有可取的技能名)。
     */
    private List<ToolCallback> resolveSkillTool(Long[] skillIds, String sessionId, Long agentId,
                                                String ownerCode, ChatEventSink eventSink, OperatorHolder operator)
    {
        java.util.Map<Long, String> bound =
                SkillLoadToolCallback.collectBoundSkills(skillIds, assemblyCache::skill);
        if (bound.isEmpty())
        {
            return List.of();
        }
        // 附件端口带上 sessionId:loadSkill 真被调用时才把附件拷进该会话沙箱(懒加载,
        // 没用到的技能一个字节都不落地)。清单只进工具返回值,不进工具定义 —— 不碰缓存前缀。
        SkillLoadToolCallback.SkillAttachmentPort port =
                (sid, scode) -> prepareSkillAttachments(sid, scode, sessionId);
        ToolCallback cb = new SkillLoadToolCallback(bound, assemblyCache::skill, port);
        return List.of(wrapRecording(cb, sessionId, agentId, ownerCode, eventSink, operator));
    }

    /**
     * 协作说明段(§6.5 第3段)。无子智能体时返回空串(不含前导换行)。
     */
    public String buildCollabSection(AiAgent agent)
    {
        if (CollectionUtils.isEmpty(agent.getChildAgents()))
        {
            return "";
        }
        return "## 协作说明\n"
                + "你可以调用下属智能体处理专门领域的任务。它们看不到本次对话历史,"
                + "因此调用时必须把必要的背景信息完整写进 query 参数。\n";
    }

}
