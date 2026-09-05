package com.ruoyi.system.ai.run;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.content.Media;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import org.springframework.util.StringUtils;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.system.ai.agent.AgentContext;
import com.ruoyi.system.ai.agent.AgentContextFactory;
import com.ruoyi.system.ai.agent.AgentRunOverrides;
import com.ruoyi.system.ai.agent.ModelInputModalities;
import com.ruoyi.system.ai.sse.ChatEventJson;
import com.ruoyi.system.ai.context.ContextCompactor;
import com.ruoyi.system.ai.context.ContextSegment;
import com.ruoyi.system.ai.context.ContextUsageAnalyzer;
import com.ruoyi.system.ai.event.ChatEventSink;
import com.ruoyi.system.ai.memory.ChatMessageRecorder;
import com.ruoyi.system.ai.memory.ChatMessageKind;
import com.ruoyi.system.ai.memory.ContextBudget;
import com.ruoyi.system.ai.memory.ConversationIds;
import com.ruoyi.system.ai.memory.TokenEstimator;
import com.ruoyi.system.ai.metering.CacheUsageProbe;
import com.ruoyi.system.ai.metering.LlmCallCollector;
import com.ruoyi.system.ai.trace.TraceSpanRecorder;
import com.ruoyi.system.domain.AiAgent;
import com.ruoyi.system.domain.AiChatMessage;
import com.ruoyi.system.domain.AiModel;
import com.ruoyi.system.domain.AiTraceSpan;
import com.ruoyi.system.mapper.AiChatMessageMapper;
import com.ruoyi.system.mapper.AiLlmCallMapper;
import com.ruoyi.system.service.IAiAgentService;
import com.ruoyi.system.service.IAiChatSessionService;
import com.ruoyi.system.service.IAiModelService;
import com.ruoyi.system.tool.AiToolProperties;
import com.ruoyi.system.tool.ToolBudgetRegistry;
import com.ruoyi.system.tool.WorkspaceSandbox;
import com.ruoyi.system.tool.WorkspaceScopeService;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

/**
 * 一轮对话的公共执行主干：装配 AgentContext → {@link AgentToolLoop} → 流式订阅 → 记账落盘。
 *
 * <p>工具循环由 {@link AgentToolLoop} 驱动({@code internalToolExecutionEnabled=false}),
 * 以便在每轮工具结果之后插入上下文清理。见 docs/ai/ai-context-compactor-phase2.md。
 */
@Component
public class ChatTurnRunner
{
    private static final Logger log = LoggerFactory.getLogger(ChatTurnRunner.class);

    /**
     * 最近一次上游 prompt 与当前可重建记忆的容差。两者超出该比例时，
     * 前者不再能代表“现在”（例如最终回答刚落库、记忆已清空，或旧版曾截断历史）。
     */
    private static final double CONTEXT_MEASUREMENT_TOLERANCE = 1.5;

    @Autowired
    private AgentContextFactory agentContextFactory;
    @Autowired
    private AiLlmCallMapper llmCallMapper;
    @Autowired
    private TokenEstimator tokenEstimator;
    @Autowired
    private CacheUsageProbe cacheUsageProbe;
    @Autowired
    private ChatMessageRecorder messageRecorder;
    @Autowired
    private AiChatMessageMapper messageMapper;
    @Autowired
    private IAiChatSessionService sessionService;
    @Autowired
    private IAiAgentService agentService;
    @Autowired
    private IAiModelService modelService;
    @Autowired
    private ContextBudget contextBudget;
    @Autowired
    private ContextUsageAnalyzer contextUsageAnalyzer;
    @Autowired
    private AiToolProperties toolProperties;
    @Autowired
    private WorkspaceScopeService workspaceScopeService;
    @Autowired
    private ToolBudgetRegistry budgetRegistry;
    @Autowired
    private TraceSpanRecorder traceRecorder;
    @Autowired
    private AgentToolLoop agentToolLoop;
    @Autowired(required = false)
    private com.ruoyi.system.ai.event.UiArtifactEmitter uiArtifactEmitter;
    @Autowired
    private ChatMemory chatMemory;
    @Autowired
    private ContextCompactor contextCompactor;
    @Autowired
    private com.ruoyi.system.mapper.AiChatSessionMapper sessionMapper;
    /** 跨会话长期记忆读侧:每轮自动检索注入本轮 user 前(见 docs/上下文与记忆模块.md §7)。 */
    @Autowired(required = false)
    private com.ruoyi.system.ai.memory.longterm.MemoryRetriever memoryRetriever;

    /**
     * 启动一轮流式对话。返回 {@link Disposable}，入口可在连接关闭时 dispose。
     */
    public Disposable run(ChatTurnRequest request, ChatEventSink sink, ChatTurnCallbacks callbacks)
    {
        ChatEventSink eventSink = sink != null ? sink : ChatEventSink.noop();
        ChatTurnCallbacks cb = callbacks != null ? callbacks : noopCallbacks();

        AgentContext context = assembleContext(request, eventSink);
        AiModel model = context.model();
        String modelName = model != null ? model.getModelCode() : null;
        ModelInputModalities modalities = context.inputModalities();
        List<Media> medias = buildMedias(request.sessionId(), request.attachments(), modalities);
        String userText = composeUserText(request.message(), request.attachments(), modalities,
                report -> eventSink.emit(ChatEventJson.mediaGated(
                        report.accepted(), report.rejected())));

        // 压缩必须在装配之前:压完再 get 才能拿到带前情提要的历史。
        // 失败会安静返回 false,不影响本轮。
        if (contextCompactor != null && context.conversationId() != null)
        {
            contextCompactor.compactIfNeeded(context.conversationId(), request.sessionId(),
                    request.agentId(), context.chatModel(),
                    model != null ? model.getContextWindow() : null,
                    model != null ? model.getMaxOutputTokens() : null,
                    context.modelId(), modelName);
        }

        // 自建工具循环后不能再用 MessageChatMemoryAdvisor —— 它每次调用都会
        // 重新注入记忆并写入用户消息,循环里会重复 N 次。这里把语义显式做一遍,只做一次。
        // 顺序必须稳定:[system] + [记忆历史] + [user]
        // (详见 ai-prompt-cache-optimization.md §2,顺序错了缓存全废)
        int inputBudget = resolveInputBudget(model);
        InitialMessages initial = buildInitialMessagesForRun(context, userText, medias, request);
        List<Message> messages = initial.messages();
        Long requestMessageId = initial.requestMessageId();
        if (uiArtifactEmitter != null)
        {
            uiArtifactEmitter.bindTurn(request.runId(), requestMessageId);
        }
        cb.onRequestMessageRecorded(requestMessageId);
        messages = agentToolLoop.applyOverflowGuard(messages, inputBudget, eventSink);

        StringBuilder reply = new StringBuilder();
        // 思考按「轮」攒:一轮内的 reasoning chunk 拼成一段,轮末落一条 THINKING 行。
        // 不按 chunk 落库(会碎成上百行),也不整轮攒到最后(那样历史里思考会挤在工具之后,
        // 与流式时看到的顺序对不上)。
        StringBuilder reasoning = new StringBuilder();
        AtomicInteger reasoningIteration = new AtomicInteger(1);
        Runnable flushThinking = () -> flushThinking(request, context.conversationId(), reasoning,
                reasoningIteration.getAndIncrement());
        // 链路追踪:turn 根 span(与 run 1:1);AtomicReference 让 lambda 可读
        AtomicReference<Long> turnSpanRef = new AtomicReference<>();
        if (traceRecorder != null && request.runId() != null)
        {
            turnSpanRef.set(traceRecorder.start(request.runId(), request.sessionId(),
                    AiTraceSpan.TYPE_TURN, null, span -> span.setAgentId(request.agentId())));
        }
        Long turnSpanId = turnSpanRef.get();
        LlmCallCollector collector = new LlmCallCollector(
                request.sessionId(), request.agentId(), context.conversationId(),
                context.modelId(), modelName, 0,
                llmCallMapper, tokenEstimator, cacheUsageProbe, budgetRegistry,
                traceRecorder, request.runId(), turnSpanId);
        if (uiArtifactEmitter != null)
        {
            collector.setUiEmitter(uiArtifactEmitter, new com.ruoyi.system.ai.event.UiArtifactContext(
                    eventSink, request.sessionId(), request.runId(), requestMessageId,
                    request.agentId(), context.agentCode(), null));
        }

        Flux<ChatResponse> flux = agentToolLoop.run(new AgentToolLoopSpec(
                context.chatModel(), context.chatOptions(), messages, context.tools(),
                inputBudget, collector, eventSink, cb,
                request.runId(), request.sessionId(), context.conversationId(),
                request.agentId(), turnSpanId, null, reply, reasoning, flushThinking,
                context.reasoningEnabled(), context.inputModalities()));

        // 记账必须覆盖全部三种终局:正常完成 / 出错 / 取消。
        // 原来只在 complete 里记,于是用户中断、上游报错、连接断开、服务停机
        // 都会让整轮的会话汇总凭空消失 —— ai_llm_call 里明明已经有行,
        // ai_chat_session.total_tokens 却是 0(实测 77 个会话里 15 个对不上)。
        AtomicBoolean finalized = new AtomicBoolean(false);

        return flux
                .doFinally(signal -> {
                    // CANCEL 不会触发下面任何一个回调,只能在这里兜;
                    // complete/error 已各自记过,靠 finalized 去重不会重复累加。
                    if (signal == SignalType.CANCEL)
                    {
                        finalizeQuietly(finalized, request, reply, collector,
                                context.conversationId(), requestMessageId, "被取消");
                    }
                    // 链路追踪:无论正常/出错/取消都收掉 turn span(耗时由 recorder 自动补)
                    if (traceRecorder != null && turnSpanRef.get() != null)
                    {
                        traceRecorder.finish(turnSpanRef.get(),
                                signal == SignalType.ON_COMPLETE
                                        ? AiTraceSpan.STATUS_SUCCEEDED
                                        : AiTraceSpan.STATUS_FAILED,
                                null);
                    }
                    if (uiArtifactEmitter != null)
                    {
                        uiArtifactEmitter.unbindTurn(request.runId());
                    }
                })
                .subscribe(
                response -> { /* 流式 chunk 已在 loop 内 onResponse */ },
                error -> {
                    finalizeQuietly(finalized, request, reply, collector,
                            context.conversationId(), requestMessageId, "出错中断");
                    cb.onFailed(error);
                },
                () -> {
                    try
                    {
                        if (!cb.shouldPersistFinalAnswer())
                        {
                            return;
                        }
                        // 只写最终 assistant,不写中间 tool_calls 轮
                        String finalReply = reply.toString();
                        Long responseMessageId = null;
                        if (context.conversationId() != null)
                        {
                            responseMessageId = messageRecorder.insert(
                                    context.conversationId(), request.sessionId(), request.agentId(),
                                    "ASSISTANT", finalReply, "0", null,
                                    request.runId(), ChatMessageKind.ASSISTANT_FINAL, "answer", null);
                        }
                        LlmCallCollector.TurnUsage usage = finalized.compareAndSet(false, true)
                                ? finalizeTurn(request, finalReply, collector, context.conversationId(),
                                        requestMessageId, responseMessageId)
                                : null;
                        Map<String, Object> contextUsage = buildContextUsage(
                                request.sessionId(), request.agentId(), model);
                        cb.onSucceeded(finalReply, usage, contextUsage,
                                requestMessageId, responseMessageId);
                    }
                    catch (Throwable e)
                    {
                        log.error("对话收尾失败 session={}", request.sessionId(), e);
                        cb.onFailed(e);
                    }
                });
    }

    /**
     * 非正常终局的兜底记账:把已经跑完的部分记进会话汇总。
     *
     * <p>{@code collector.onComplete()} 还负责 flush 最后一轮 pending —— 不调它的话,
     * 连那一行 {@code ai_llm_call} 都会丢。
     *
     * <p>guard 与正常完成路径互斥,保证一轮只记一次。记账失败只告警:
     * 这已经是异常路径,不能再抛出去盖掉真正的错误原因。
     */
    private void finalizeQuietly(AtomicBoolean guard, ChatTurnRequest request,
                                 StringBuilder reply, LlmCallCollector collector,
                                 String conversationId, Long requestMessageId, String why)
    {
        if (!guard.compareAndSet(false, true))
        {
            return;
        }
        try
        {
            finalizeTurn(request, reply.toString(), collector, conversationId,
                    requestMessageId, null);
            log.info("对话{},已补记本轮用量 session={}", why, request.sessionId());
        }
        catch (Exception e)
        {
            log.warn("补记本轮用量失败 session={}: {}", request.sessionId(), e.getMessage());
        }
    }

    /**
     * 把跨会话记忆注入进「发给模型的那份」user 文本(记忆系统 spec §7.1 红线)。
     *
     * <p><b>发送版与落库版必须拆开</b>:注入的记忆只进发给模型的那份,落库存用户原话 ——
     * 否则审计流被污染(用户看到自己没说过的话),且注入内容永久沉进历史,轮轮累积、
     * 记忆 supersede 后历史里那份过时版本还在跟新版本打架。
     *
     * <p>注入拼在本轮 user 文本前面,顺序 {@code [system] + [历史] + [本轮 user]} 不变,
     * KV-cache 前缀一字节不动(有 {@code ChatTurnMessageOrderTest} 锁这个顺序)。
     *
     * <p>旁路语义:检索/embedding 失败由 {@code MemoryRetriever} 自行静默降级返回不注入,
     * 这里不抛异常,正常对话继续。
     *
     * @param userText 用户原话(未注入)
     * @param userId   会话用户(来自 {@code ChatTurnRequest.operator()};可为 null=无登录身份,不注入)
     * @param agentId  当前 agent
     * @return 发给模型用的 user 文本(有注入时 = 注入文本 + "\n\n" + 用户原话;否则原样返回)
     */
    static String composeMemoryInjectedText(String userText, Long userId, Long agentId,
                                            com.ruoyi.system.ai.memory.longterm.MemoryRetriever retriever)
    {
        if (retriever == null || userId == null || !StringUtils.hasText(userText))
        {
            return userText != null ? userText : "";
        }
        com.ruoyi.system.ai.memory.longterm.MemoryInjection injection =
                retriever.retrieve(userId, agentId, userText);
        if (!injection.isInjected())
        {
            return userText;
        }
        return injection.injectedText().get() + "\n\n" + userText;
    }

    /**
     * 手工组装初始消息,等价于原 MessageChatMemoryAdvisor 在单次 ChatClient 调用时做的事。
     *
     * <p><b>顺序即缓存前缀,改动前先读 ai-prompt-cache-optimization.md §2</b>:
     * [system] + [记忆历史] + [本轮 user]。system 会话内静态、历史只追加,
     * 前缀稳定才有上游 KV-cache 命中。
     *
     * <p>工具往返不在这里拼:它们作为 assistant(tool_calls)+tool 真实消息躺在记忆历史里,
     * 由 DbChatMemory.get 重建。
     *
     * <p><b>发送版与落库版必须拆开</b>(记忆系统 spec §7.1):跨会话长期记忆注入只进
     * {@code sendText}(发给模型的那份),{@code messageRecorder.insert} 落的是用户原话。
     * 混在一起会污染 {@code ai_chat_message} 审计流,还会让注入内容沉进历史轮轮累积。
     *
     * <p>包级可见是为了让 {@code ChatTurnMessageOrderTest} 与
     * {@code ChatTurnMemoryInjectionTest} 直接锁住这两条不变量 ——
     * 它们是本项目缓存命中率与审计流的地基,不能只靠人工回归。
     */
    InitialMessages buildInitialMessagesForRun(AgentContext context, String userText,
                                                        List<Media> medias, ChatTurnRequest request)
    {
        List<Message> messages = new ArrayList<>();
        if (StringUtils.hasText(context.systemPrompt())) messages.add(new SystemMessage(context.systemPrompt()));
        if (context.conversationId() != null) messages.addAll(chatMemory.get(context.conversationId()));
        // 跨会话长期记忆注入:只进发给模型的那份,落库仍用用户原话(记忆系统 spec §7.1 红线)。
        // userId 来自请求操作者身份(ChatRunExecutor 用 command.userId() 构造 OperatorHolder);
        // agentId 即请求的 agentId。检索失败由 MemoryRetriever 静默降级,不阻塞本轮。
        Long userId = request.operator() != null ? request.operator().userId() : null;
        String sendText = composeMemoryInjectedText(userText, userId, request.agentId(), memoryRetriever);
        // 本轮 @ 技能的规则正文:与长期记忆同为「非用户原话」的注入段,同样只进发送版 ——
        // 下面 messageRecorder.insert 落的仍是 userText。不落库是关键:用户把 @ 粘着聊 20 轮,
        // 历史里也不会攒 20 份技能正文,只有当轮这一份。
        // 它进不了 system prompt 是刻意的(见 AgentContextFactory#buildTurnSkillSection),
        // 拼在用户原话之前 = 只影响当轮尾部,前缀一字节不动。
        // 旁路语义同长期记忆:工厂缺席(子 agent 直调 / 单测)时静默不注入,不阻塞本轮。
        String turnSkills = agentContextFactory == null ? ""
                : agentContextFactory.buildTurnSkillSection(request.agentId(), request.skillIds(), request.sessionId());
        if (StringUtils.hasText(turnSkills))
        {
            sendText = turnSkills + "\n\n" + sendText;
        }
        UserMessage.Builder ub = UserMessage.builder().text(sendText != null ? sendText : "");
        if (medias != null && !medias.isEmpty()) ub.media(medias);
        UserMessage userMessage = ub.build();
        messages.add(userMessage);
        Long messageId = null;
        if (context.conversationId() != null)
        {
            messageId = messageRecorder.insert(context.conversationId(), request.sessionId(),
                    request.agentId(), "USER", userText, "0", null,
                    request.runId(), ChatMessageKind.USER_INPUT, "user", null);
        }
        return new InitialMessages(messages, messageId);
    }

    /** 包级可见:{@code ChatTurnMessageOrderTest} / {@code ChatTurnMemoryInjectionTest} 直接断言,不必反射。 */
    record InitialMessages(List<Message> messages, Long requestMessageId) { }

    private int resolveInputBudget(AiModel model)
    {
        Integer window = model != null ? model.getContextWindow() : null;
        Integer maxOut = model != null ? model.getMaxOutputTokens() : null;
        return contextBudget.inputBudget(window, maxOut);
    }

    private AgentContext assembleContext(ChatTurnRequest request, ChatEventSink eventSink)
    {
        // 对话统一走 Run 持久化链路:操作者身份与事件出口均由调用方显式传入
        return agentContextFactory.buildForRun(
                request.agentId(), request.sessionId(), eventSink, request.operator(),
                new AgentRunOverrides(request.modelCode(), request.skillIds(), request.clientTools()),
                request.runId());
    }

    /**
     * 把攒下的一段思考落成 THINKING 行,并清空缓冲。
     *
     * <p>无会话记忆(子 agent)或本轮没有思考时什么都不做。落库失败只告警 ——
     * 思考是展示信息,不能因为它写不进去而中断整轮对话。
     */
    private void flushThinking(ChatTurnRequest request, String conversationId, StringBuilder buf,
                               int iteration)
    {
        if (conversationId == null || buf == null || buf.length() == 0)
        {
            return;
        }
        String text = buf.toString();
        buf.setLength(0);
        try
        {
            messageRecorder.recordThinking(conversationId, request.sessionId(), request.agentId(), text,
                    request.runId(), "reasoning:" + iteration, null);
        }
        catch (Exception e)
        {
            log.warn("落思考记录失败 session={}: {}", request.sessionId(), e.getMessage());
        }
    }

    /**
     * 一轮结束：落 collector、回填消息归因、附件、审计文件、会话汇总。
     * <p>以原 A 轨逻辑为基准合并；messageId 由调用方按需再查（持久化轨 completeRun 需要）。
     */
    public LlmCallCollector.TurnUsage finalizeTurn(ChatTurnRequest request, String reply,
                                                   LlmCallCollector collector, String conversationId)
    {
        return finalizeTurn(request, reply, collector, conversationId, null, null);
    }

    public LlmCallCollector.TurnUsage finalizeTurn(ChatTurnRequest request, String reply,
                                                   LlmCallCollector collector, String conversationId,
                                                   Long requestMessageId, Long responseMessageId)
    {
        try
        {
            String message = request.message() == null ? "" : request.message();
            LlmCallCollector.TurnUsage turn = collector.onComplete(message, reply);

            if (conversationId != null && turn.lastUsage() != null)
            {
                try
                {
                    if (responseMessageId != null)
                    {
                        LlmCallCollector.UsageSnapshot u = turn.lastUsage();
                        // tokens 列不再回填：上游只给每次调用的总量,没有"单条消息多少 token"这个数据。
                        // 用整轮 completion_tokens 标最后一条 ASSISTANT,在多轮工具调用时是错误归因
                        // (实测曾把 2375 字符的消息标成 21906 token)。
                        // 这一列只用于上下文占比与预算展示,统一走 TokenEstimator 保证口径一致;
                        // 真实用量在 ai_llm_call 与 ai_chat_session.total_tokens,那两处才是权威。
                        messageRecorder.updateAttribution(responseMessageId,
                                u.promptTokens(), u.completionTokens(), u.modelName(), u.usageSource());
                        List<Long> callIds = collector.insertedCallIds();
                        if (!callIds.isEmpty())
                        {
                            llmCallMapper.bindMessageIdByIds(responseMessageId, callIds);
                        }
                    }
                }
                catch (Exception e)
                {
                    log.warn("回填消息归因失败: {}", e.getMessage());
                }
            }

            persistAttachments(request, requestMessageId);

            // 本轮新增的上下文字符数(会话表 context_length 累加用)。
            // 原先是 ContextFileStore.appendHistory 的返回值 —— 那份 .md 只有写入方没有消费方,
            // 为了拿一个字符数去读写整个文件并维护它的格式并不值当,直接算。
            // 口径与子智能体侧(SubAgentToolCallback.recordUsage)一致:问 + 答的字符数。
            long userLen = message == null ? 0L : message.length();
            long replyLen = reply == null ? 0L : reply.length();

            long tokenEst = turn.totalTokens() > 0 ? turn.totalTokens()
                    : (message.length() + (reply == null ? 0 : reply.length())) / 3L;
            sessionService.recordTokenUsage(
                    request.sessionId(), request.agentId(),
                    tokenEst, turn.promptTokens(), turn.completionTokens(),
                    turn.callCount(), userLen + replyLen);
            return turn;
        }
        catch (Exception e)
        {
            log.warn("落盘上下文/记 token 失败: {}", e.getMessage());
            return null;
        }
    }

    private void persistAttachments(ChatTurnRequest request, Long requestMessageId)
    {
        List<ChatTurnAttachment> list = request.attachments();
        if (list == null || list.isEmpty() || requestMessageId == null)
        {
            return;
        }
        try
        {
            messageMapper.updateAttachmentsById(requestMessageId, JSON.toJSONString(list));
        }
        catch (Exception e)
        {
            log.warn("回填附件元数据失败: {}", e.getMessage());
        }
    }

    /**
     * 把附件清单拼进用户消息。
     * <p>行为以原 A 轨文案为准（与持久化轨的标点/措辞差异已收敛到本侧）。
     */
    public String composeUserText(String message, List<ChatTurnAttachment> attachments,
                                  ModelInputModalities caps)
    {
        return composeUserText(message, attachments, caps, null);
    }

    /**
     * 同上,并把上传附件的门控结果回报给 {@code onGate}。
     *
     * <p>用户最关心的恰恰是「我传的这张图,模型到底看见没有」。判定就发生在这里,但结果
     * 原先只以一段文字混在提示词里给模型看,界面上无从得知 —— 回调让它能发成事件。
     */
    public String composeUserText(String message, List<ChatTurnAttachment> attachments,
                                  ModelInputModalities caps,
                                  java.util.function.Consumer<MediaGateReport> onGate)
    {
        String text = message == null ? "" : message;
        if (attachments == null || attachments.isEmpty())
        {
            return text;
        }
        ModelInputModalities modalities = caps == null ? ModelInputModalities.of(null) : caps;
        StringBuilder sb = new StringBuilder(text);
        // 标题不再一刀切说「可用 read 读取」:那是对已直接提供的媒体的错误指引,
        // 实测模型会照标题去 read 一遍已在上下文里的图,同一份内容进两次上下文。
        // 读取指引改为逐条按文件给,末尾只对确实需要读的文件补一句。
        sb.append("\n\n[本次上传的文件(位于会话工作区)]");
        // 送不进模型的媒体按模态计数,末尾统一给一句说明 —— 逐条重复太吵
        Map<String, Integer> blind = new LinkedHashMap<>();
        // 非媒体文件(txt/md/csv 等)才需要引导模型去 read
        int readable = 0;
        int accepted = 0;
        for (ChatTurnAttachment a : attachments)
        {
            if (a == null || a.getPath() == null)
            {
                continue;
            }
            MimeType mime = parseMimeQuietly(a.getMime());
            if (modalities.accepts(mime))
            {
                accepted++;
                // 已经通过 Media 通道给模型看了。这里必须给出指令而不只是陈述事实 ——
                // 只说「已提供」时模型仍会去 read,得明说不要读。
                sb.append("\n- ").append(a.getPath())
                  .append("(").append(mediaLabel(mime))
                  .append(",内容已随本条消息直接进入你的上下文;")
                  .append("直接据此作答,不要再用 read 读取它)");
                continue;
            }
            sb.append("\n- ").append(a.getPath());
            if (a.getMime() != null)
            {
                sb.append("(").append(a.getMime());
                if (a.getSize() != null)
                {
                    sb.append(", ").append(formatSize(a.getSize()));
                }
                sb.append(")");
            }
            String modality = ModelInputModalities.modalityOf(mime);
            if (modality != null)
            {
                blind.merge(modality, 1, Integer::sum);
            }
            else
            {
                readable++;
            }
        }
        if (readable > 0)
        {
            sb.append("\n\n以上未标注「已进入上下文」的文件尚未被读取,需要其内容时用 read / grep 按路径读取。");
        }
        if (!blind.isEmpty())
        {
            sb.append("\n\n[系统提示] 以下上传内容没能送进你的上下文:");
            for (Map.Entry<String, Integer> e : blind.entrySet())
            {
                // 原因判定与工具侧共用 MediaGateReport —— 此处原本只有两个分支,
                // 漏掉「格式不在可发送范围内」,同一份文件因进入方式不同会得到不同说法。
                sb.append("\n- ").append(e.getValue()).append(" 个")
                  .append(mediaLabel(e.getKey())).append("附件:")
                  .append(MediaGateReport.reason(e.getKey(), modalities));
            }
            sb.append("\n如果回答依赖它们的内容，请直接告诉用户需要换用支持对应输入的模型，不要猜测内容。");
        }
        if (onGate != null && (accepted > 0 || !blind.isEmpty()))
        {
            onGate.accept(MediaGateReport.of(accepted, blind, modalities));
        }
        return sb.toString();
    }

    /** MIME 解析失败返回 null —— 附件的 mime 由前端往返回来,不能当可信输入。 */
    private static MimeType parseMimeQuietly(String mime)
    {
        if (mime == null || mime.isBlank())
        {
            return null;
        }
        try
        {
            return MimeTypeUtils.parseMimeType(mime);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static String mediaLabel(MimeType mime)
    {
        return mediaLabel(ModelInputModalities.modalityOf(mime));
    }

    private static String mediaLabel(String modality)
    {
        if (modality == null)
        {
            return "文件";
        }
        switch (modality)
        {
            case ModelInputModalities.IMAGE: return "图片";
            case ModelInputModalities.FILE:  return "文档";
            case ModelInputModalities.VIDEO: return "视频";
            case ModelInputModalities.AUDIO: return "音频";
            default: return "文件";
        }
    }

    /**
     * 构造多模态 Media 列表 —— 逐份按模型声明与传输层能力判定,不再只认图片。
     * <p>模型支持文档时 PDF 也会走 Media 通道;视频恒被拒(见 {@link ModelInputModalities})。
     */
    public List<Media> buildMedias(String sessionId, List<ChatTurnAttachment> attachments,
                                   ModelInputModalities caps)
    {
        ModelInputModalities modalities = caps == null ? ModelInputModalities.of(null) : caps;
        if (attachments == null || attachments.isEmpty())
        {
            return List.of();
        }
        List<Media> medias = new ArrayList<>();
        for (ChatTurnAttachment a : attachments)
        {
            if (a == null || a.getPath() == null)
            {
                continue;
            }
            MimeType mimeType = parseMimeQuietly(a.getMime());
            if (!modalities.accepts(mimeType))
            {
                continue;
            }
            try
            {
                java.nio.file.Path root = workspaceScopeService.resolveRoot(toolProperties, sessionId, false);
                // resolveSafe 防路径穿越:path 虽然由上传接口产生,但它经前端往返回来,不能当可信输入
                java.nio.file.Path file = WorkspaceSandbox.resolveSafe(root, a.getPath());
                if (!java.nio.file.Files.isRegularFile(file))
                {
                    log.warn("附件不存在,跳过: {}", a.getPath());
                    continue;
                }
                // name 必须给:PDF 走 input_file 时它就是模型看到的 filename,
                // 缺省会退化成 Media 自动生成的随机名,模型无从判断这是哪份文档。
                medias.add(Media.builder()
                        .mimeType(mimeType)
                        .data(new FileSystemResource(file))
                        .name(attachmentName(a, file))
                        .build());
            }
            catch (Exception e)
            {
                log.warn("附件转 Media 失败,跳过 {}: {}", a.getPath(), e.getMessage());
            }
        }
        return medias;
    }

    private static String attachmentName(ChatTurnAttachment a, java.nio.file.Path file)
    {
        if (a.getName() != null && !a.getName().isBlank())
        {
            return a.getName();
        }
        return file.getFileName().toString();
    }

    /**
     * 从 AssistantMessage 的 metadata 提取推理内容(思考链)。
     * <p>模型不支持思考时返回 null。
     *
     * <p><b>reasoningContent 必须放在第一位</b>:OpenAI 兼容路径下,上游发的 snake_case
     * {@code reasoning_content} 由 Spring AI 反序列化成 {@code ChatCompletionMessage.reasoningContent()}
     * (字段上标了 {@code @JsonProperty}),再以<b>驼峰</b> key 放进 metadata ——
     * 见 {@code OpenAiChatModel.lambda$internalStream$5},与 finishReason/refusal/annotations 同一批写入。
     * Spring AI 没有为这个 key 暴露常量,只能写字面量;跨版本变动的风险由后面几个兜底 key 兜着。
     * 曾因为只找 snake_case 而一次都没命中,思考功能整条链路空转。
     */
    public static String extractReasoning(AssistantMessage msg)
    {
        if (msg == null || msg.getMetadata() == null)
        {
            return null;
        }
        String[] keys = {"reasoningContent", "reasoning_content", "reasoning", "thinking", "thinking_content"};
        for (String key : keys)
        {
            Object v = msg.getMetadata().get(key);
            if (v instanceof String s && !s.isEmpty())
            {
                return s;
            }
        }
        return null;
    }

    /**
     * 构建上下文用量快照(含分类 segments 与 metrics)。
     * <p>公开给 Controller 的 GET /context 复用,避免再维护一份。
     * <p>分段按 TokenEstimator 估算；最近一次真实 prompt 与当前可重建记忆一致时，
     * 用它校准绝对值。两者显著偏离时以当前记忆为准并显式标记估算口径。
     */
    public Map<String, Object> buildContextUsage(String sessionId, Long agentId, AiModel model)
    {
        Map<String, Object> map = new HashMap<>();
        String conversationId = agentId != null
                ? ConversationIds.of(sessionId, agentId)
                : sessionId;

        long lastSummaryId = 0L;
        boolean hasSummary = false;
        try
        {
            AiChatMessage summary = messageRecorder.latestSummary(conversationId);
            if (summary != null)
            {
                lastSummaryId = summary.getMessageId();
                hasSummary = true;
            }
        }
        catch (Exception ignored)
        {
        }

        List<ContextSegment> segments =
                contextUsageAnalyzer.analyze(agentId, sessionId, conversationId, lastSummaryId);
        long estimatedTotal = 0L;
        for (ContextSegment seg : segments)
        {
            estimatedTotal += seg.tokens();
        }

        // 最近一次顶层调用的 prompt_tokens 是“上次请求”的真实观测，
        // 不必然等于最终回答等新消息落库后的“当前记忆”。
        // 差值记账减掉的是 Spring AI 的累计包装,减完后每行就是那次调用自己的完整 prompt,
        // 不是相对上一次的增量 —— 所以取末次即可,不能求和(求和是本轮累计消耗量,计费口径)。
        long realPrompt = 0L;
        long peakPrompt = 0L;
        try
        {
            realPrompt = llmCallMapper.selectLatestPromptTokens(sessionId, conversationId);
        }
        catch (Exception e)
        {
            log.debug("读取最近一次真实 prompt 失败: {}", e.getMessage());
        }
        // peak 与 latest 分开查：peakUsed=本会话曾经最挤(见 design §3.3)
        try
        {
            peakPrompt = llmCallMapper.selectPeakPromptTokens(sessionId, conversationId);
        }
        catch (Exception e)
        {
            log.debug("读取会话 prompt 峰值失败: {}", e.getMessage());
        }
        if (peakPrompt < realPrompt)
        {
            peakPrompt = realPrompt;
        }

        boolean promptConsistent = isPromptMeasurementConsistent(realPrompt, estimatedTotal);
        String usedSource;
        long used;
        List<Map<String, Object>> segmentMaps = new ArrayList<>();
        long compactableTokens = 0L;
        if (realPrompt > 0 && estimatedTotal > 0 && promptConsistent)
        {
            usedSource = "real";
            used = realPrompt;
            // 分段_i = 估算_i / Σ估算 × used;同一把尺子做比值,cl100k 系统性偏高大部分抵消
            long allocated = 0L;
            for (int i = 0; i < segments.size(); i++)
            {
                ContextSegment seg = segments.get(i);
                long scaled;
                if (i == segments.size() - 1)
                {
                    // 最后一段吃掉舍入误差,保证 sum == used
                    scaled = Math.max(0L, used - allocated);
                }
                else
                {
                    scaled = Math.round(seg.tokens() * (double) used / estimatedTotal);
                    allocated += scaled;
                }
                if (seg.compactable())
                {
                    compactableTokens += scaled;
                }
                Map<String, Object> s = new HashMap<>();
                s.put("key", seg.key());
                s.put("label", seg.label());
                s.put("tokens", scaled);
                s.put("compactable", seg.compactable());
                segmentMaps.add(s);
            }
        }
        else
        {
            // 当前可重建记忆才是下一次请求真正会发送的上下文。
            // 最近 prompt 明显偏离时不能强行把所有分段缩到它的数值，否则会把
            // 已落库的新消息（以及旧版截断后的完整历史）伪装成不存在。
            usedSource = realPrompt > 0 && estimatedTotal > 0 ? "reconciled" : "estimated";
            used = estimatedTotal > 0 ? estimatedTotal : realPrompt;
            for (ContextSegment seg : segments)
            {
                if (seg.compactable())
                {
                    compactableTokens += seg.tokens();
                }
                Map<String, Object> s = new HashMap<>();
                s.put("key", seg.key());
                s.put("label", seg.label());
                s.put("tokens", seg.tokens());
                s.put("compactable", seg.compactable());
                segmentMaps.add(s);
            }
        }

        if (model == null && agentId != null)
        {
            try
            {
                AiAgent agent = agentService.selectAiAgentById(agentId);
                if (agent != null && agent.getModelCode() != null)
                {
                    model = modelService.selectByModelCode(agent.getModelCode());
                }
            }
            catch (Exception ignored)
            {
            }
        }

        Integer window = model != null ? model.getContextWindow() : null;
        Integer maxOut = model != null ? model.getMaxOutputTokens() : null;
        int budget = contextBudget.inputBudget(window, maxOut);
        int threshold = contextBudget.threshold(window, maxOut);
        double percent = budget > 0 ? (used * 100.0 / budget) : 0;
        long peakUsed = Math.max(peakPrompt, used);
        double peakPercent = budget > 0 ? (peakUsed * 100.0 / budget) : 0;
        String modelName = model != null ? model.getModelCode() : null;

        // 前端 ≈ 绑的是 trustworthy;估算总量时标 false 以复用现有样式,不改前端
        boolean trustworthy = "real".equals(usedSource);
        if (trustworthy)
        {
            try
            {
                trustworthy = llmCallMapper.countEstimatedBySession(sessionId) == 0;
            }
            catch (Exception ignored)
            {
            }
        }

        List<Map<String, Object>> metrics = new ArrayList<>();
        try
        {
            Map<String, Object> cache = llmCallMapper.sumCacheTokensBySession(sessionId);
            long hit = toLongMetric(cache != null ? cache.get("hit") : null);
            long miss = toLongMetric(cache != null ? cache.get("miss") : null);
            if (hit + miss > 0)
            {
                long calls = toLongMetric(cache != null ? cache.get("calls") : null);
                // 只跑了一次调用时标注「首轮」:样本太小,不代表稳态水平。
                // 别据此把首轮从统计里排掉 —— call_seq 是 Run 内序号,每轮对话都从 1 开始,
                // 不带工具的对话永远只有 call_seq=1,加 call_seq>1 会一行不剩(实测 99.5% 数据被扔掉);
                // 且上游缓存是账号级的,同一 system 前缀跨会话就能复用,首轮照样命中。
                metrics.add(cacheHitMetric("cacheHitRate",
                        calls <= 1 ? "平均缓存命中率(首轮)" : "平均缓存命中率", hit, miss));

                // 只有子智能体真跑过才拆开:没跑过时「主」恒等于「总」,多一行纯噪声。
                // 拆的理由是这个总值是 token 加权的,谁调用量大谁主导 —— 子智能体轮次多、
                // 上下文长,常年把总值拉向自己那一侧,只看总值会误判主链路的缓存效果。
                long mainHit = toLongMetric(cache != null ? cache.get("mainHit") : null);
                long mainMiss = toLongMetric(cache != null ? cache.get("mainMiss") : null);
                long subHit = toLongMetric(cache != null ? cache.get("subHit") : null);
                long subMiss = toLongMetric(cache != null ? cache.get("subMiss") : null);
                if (subHit + subMiss > 0)
                {
                    if (mainHit + mainMiss > 0)
                    {
                        metrics.add(cacheHitMetric("cacheHitRateMain", "主智能体命中率", mainHit, mainMiss));
                    }
                    metrics.add(cacheHitMetric("cacheHitRateSub", "子智能体命中率", subHit, subMiss));
                }
            }
        }
        catch (Exception ignored)
        {
        }
        {
            Map<String, Object> m = new HashMap<>();
            m.put("key", "threshold");
            m.put("label", "压缩线");
            m.put("value", threshold);
            m.put("unit", "tokens");
            metrics.add(m);
        }
        if (modelName != null)
        {
            Map<String, Object> m = new HashMap<>();
            m.put("key", "model");
            m.put("label", "模型");
            m.put("value", modelName);
            m.put("unit", "text");
            metrics.add(m);
        }

        map.put("spend", buildSpend(sessionId));
        map.put("used", used);
        map.put("peakUsed", peakUsed);
        map.put("peakPercent", Math.round(peakPercent * 10) / 10.0);
        map.put("budget", budget);
        map.put("threshold", threshold);
        map.put("percent", Math.round(percent * 10) / 10.0);
        map.put("compactableTokens", compactableTokens);
        map.put("segments", segmentMaps);
        map.put("metrics", metrics);
        map.put("modelName", modelName);
        map.put("contextWindow", window);
        map.put("maxOutputTokens", maxOut);
        map.put("hasSummary", hasSummary);
        map.put("trustworthy", trustworthy);
        return map;
    }

    /**
     * 真实 prompt 是上一次调用时的观测，估算量是此刻从数据库重建的记忆。
     * 只有两者在合理容差内才能用观测值校准当前环图。
     */
    static boolean isPromptMeasurementConsistent(long observedPrompt, long estimatedMemory)
    {
        if (observedPrompt <= 0 || estimatedMemory <= 0)
        {
            return false;
        }
        double ratio = observedPrompt / (double) estimatedMemory;
        return ratio >= 1.0 / CONTEXT_MEASUREMENT_TOLERANCE
                && ratio <= CONTEXT_MEASUREMENT_TOLERANCE;
    }

    /**
     * 会话累计消耗:总量 + 按智能体拆分。
     *
     * <p><b>与环图是两个口径,不要混</b>:环图量「当前 prompt 占窗口多少」(只算 depth=0,
     * 子 agent 无状态、不占父的窗口);这里量「本会话累计花掉多少」(含子 agent,无上限)。
     * 实测子 agent 常占 60%~80%,只看环图会以为还很空。
     *
     * <p>不做 top-N 截断:全量返回,由前端决定展开几行 —— 折叠阈值是展示决策,
     * 改它不该动后端。
     */
    private Map<String, Object> buildSpend(String sessionId)
    {
        Map<String, Object> spend = new HashMap<>();
        List<Map<String, Object>> agents = new ArrayList<>();
        long total = 0L;
        long prompt = 0L;
        long completion = 0L;
        long calls = 0L;
        try
        {
            List<Map<String, Object>> rows = sessionMapper.selectAgentSpendBySession(sessionId);
            if (rows != null)
            {
                for (Map<String, Object> r : rows)
                {
                    long t = toLongMetric(r.get("tokens"));
                    total += t;
                    prompt += toLongMetric(r.get("promptTokens"));
                    completion += toLongMetric(r.get("completionTokens"));
                    calls += toLongMetric(r.get("callCount"));
                    agents.add(r);
                }
            }
        }
        catch (Exception e)
        {
            // 消耗明细是展示信息,查不到就少一块,不能拖垮整个上下文接口
            log.debug("读取会话消耗明细失败 session={}: {}", sessionId, e.getMessage());
        }
        spend.put("totalTokens", total);
        spend.put("promptTokens", prompt);
        spend.put("completionTokens", completion);
        spend.put("callCount", calls);
        spend.put("agents", agents);
        return spend;
    }

    /**
     * 缓存命中率 metric 行。
     *
     * <p>命中为 0 时不标绿:绿色在其他行表示「好」,用在 0% 上语义冲突。
     */
    private static Map<String, Object> cacheHitMetric(String key, String label, long hit, long miss)
    {
        long total = hit + miss;
        Map<String, Object> m = new HashMap<>();
        m.put("key", key);
        m.put("label", label);
        m.put("value", total > 0 ? Math.round(hit * 1000.0 / total) / 10.0 : 0.0);
        m.put("unit", "percent");
        if (hit > 0)
        {
            m.put("tone", "success");
        }
        return m;
    }

    private static long toLongMetric(Object v)
    {
        if (v instanceof Number n)
        {
            return n.longValue();
        }
        return 0L;
    }

    private static String formatSize(long bytes)
    {
        if (bytes >= 1024 * 1024)
        {
            return String.format("%.1fMB", bytes / 1024.0 / 1024);
        }
        if (bytes >= 1024)
        {
            return String.format("%.1fKB", bytes / 1024.0);
        }
        return bytes + "B";
    }

    private static ChatTurnCallbacks noopCallbacks()
    {
        return new ChatTurnCallbacks()
        {
            @Override
            public void onSucceeded(String reply, LlmCallCollector.TurnUsage usage,
                                    Map<String, Object> contextUsage,
                                    Long requestMessageId, Long responseMessageId)
            {
            }

            @Override
            public void onFailed(Throwable error)
            {
            }
        };
    }
}
