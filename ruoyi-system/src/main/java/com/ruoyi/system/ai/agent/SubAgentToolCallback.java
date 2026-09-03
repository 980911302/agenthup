package com.ruoyi.system.ai.agent;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.ai.context.ToolResultText;
import com.ruoyi.system.ai.memory.ChatMessageRecorder;
import com.ruoyi.system.ai.memory.ConversationIds;
import com.ruoyi.system.ai.memory.TokenEstimator;
import com.ruoyi.system.ai.event.ChatEventSink;
import com.ruoyi.system.ai.metering.LlmCallCollector;
import com.ruoyi.system.ai.run.AgentToolLoop;
import com.ruoyi.system.ai.run.AgentToolLoopSpec;
import com.ruoyi.system.ai.run.ChatTurnCallbacks;
import com.ruoyi.system.ai.run.ToolCallIdMatcher;
import com.ruoyi.system.domain.AiAgentChild;
import com.ruoyi.system.mapper.AiLlmCallMapper;
import com.ruoyi.system.service.IAiChatSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import com.ruoyi.system.ai.sse.ChatEventJson;
import com.ruoyi.system.ai.trace.TraceSpanRecorder;
import com.ruoyi.system.domain.AiTraceSpan;
import org.springframework.ai.model.tool.internal.ToolCallReactiveContextHolder;
import reactor.util.context.ContextView;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 把一个子智能体包装成 {@link ToolCallback}(§5.2),挂到父 agent 上。
 *
 * <p>父 agent 通过原生 tool calling 决定调谁、传什么,子 agent 的输出作为 tool result 回到父上下文。
 * 子 agent 无状态(§5.3):conversationId 为 null,每次调用都是干净的;
 * 它收到的是父 agent 编的一个 query 字符串,不是用户原话,跨轮记忆没有意义。
 *
 * <p>本类不是 Spring bean -- 每次请求新建,持有 per-call 状态(sessionId/depth/parentAgentId)。
 * 依赖的 {@link AgentContextFactory} / {@link IAiChatSessionService} / {@link ChatMessageRecorder}
 * 是 Spring bean,通过构造器注入。
 *
 * <p><b>自己记账,不依赖外层 RecordingToolCallback 装饰</b>。
 * RecordingToolCallback 的 {@code source()} 靠 {@code delegate instanceof SyncMcpToolCallback} 判断,
 * 包了本类后会走 default="builtin",丢失 "agent" 来源。
 * 所以 {@code toolSource="agent"} 与 {@code subAgentId} 由本类在 {@link #call(String)} 里直接保证。
 *
 * @author ruoyi
 */
public class SubAgentToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(SubAgentToolCallback.class);

    /** 固定 inputSchema:只有一个 query 参数,交给子 agent 处理的任务描述 */
    private static final String INPUT_SCHEMA =
            "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"type\":\"object\","
          + "\"properties\":{\"query\":{\"type\":\"string\",\"description\":\"交给该智能体处理的任务描述,需包含必要背景\"}},"
          + "\"required\":[\"query\"]}";

    private final AiAgentChild child;
    private final AgentContextFactory ctxFactory;
    private final String sessionId;
    private final AgentCallDepth depth;
    /** 调用方(父 agent)ID,用于工具调用记录的 agentId 字段(成本归因到父 agent) */
    private final Long parentAgentId;
    private final IAiChatSessionService sessionService;
    private final ChatMessageRecorder recorder;
    /** 对话事件出口；父子智能体共享同一运行事件流。 */
    private final ChatEventSink eventSink;
    /**
     * 包裹本子智能体的上一层 code(顶层子智能体为 null)。
     * 作为 agent_start/agent_end 事件的 owner,前端据此把这个 agent step 嵌进上一层 agent,
     * 而不是平铺到顶层(支持 A→B→C 多层递归嵌套)。
     */
    private final String ownerAgentCode;
    private final AiLlmCallMapper llmCallMapper;
    private final TokenEstimator tokenEstimator;
    private final com.ruoyi.system.ai.metering.CacheUsageProbe cacheUsageProbe;
    private final com.ruoyi.system.tool.ToolBudgetRegistry budgetRegistry;
    /** 操作者身份(从父链路继承,传给子 agent 的工具做权限校验;可为 null) */
    private final AgentContextFactory.OperatorHolder operator;
    /** 流空闲超时秒数(<=0 关闭):工具执行期间流无输出,必须大于最长工具执行时长 */
    private final long idleTimeoutSeconds;
    /** 链路追踪(可为 null):子 agent span 记录器 */
    private final TraceSpanRecorder traceRecorder;
    private final AgentToolLoop agentToolLoop;

    public SubAgentToolCallback(AiAgentChild child, AgentContextFactory ctxFactory,
                               String sessionId, AgentCallDepth depth, Long parentAgentId,
                               IAiChatSessionService sessionService, ChatMessageRecorder recorder,
                               ChatEventSink eventSink, String ownerAgentCode,
                               AiLlmCallMapper llmCallMapper, TokenEstimator tokenEstimator,
                               AgentContextFactory.OperatorHolder operator,
                               com.ruoyi.system.ai.metering.CacheUsageProbe cacheUsageProbe,
                               com.ruoyi.system.tool.ToolBudgetRegistry budgetRegistry,
                               AgentToolLoop agentToolLoop) {
        this(child, ctxFactory, sessionId, depth, parentAgentId, sessionService, recorder,
                eventSink, ownerAgentCode, llmCallMapper, tokenEstimator, operator,
                cacheUsageProbe, budgetRegistry, 0L, agentToolLoop);
    }

    public SubAgentToolCallback(AiAgentChild child, AgentContextFactory ctxFactory,
                               String sessionId, AgentCallDepth depth, Long parentAgentId,
                               IAiChatSessionService sessionService, ChatMessageRecorder recorder,
                               ChatEventSink eventSink, String ownerAgentCode,
                               AiLlmCallMapper llmCallMapper, TokenEstimator tokenEstimator,
                               AgentContextFactory.OperatorHolder operator,
                               com.ruoyi.system.ai.metering.CacheUsageProbe cacheUsageProbe,
                               com.ruoyi.system.tool.ToolBudgetRegistry budgetRegistry,
                               long idleTimeoutSeconds, AgentToolLoop agentToolLoop) {
        this(child, ctxFactory, sessionId, depth, parentAgentId, sessionService, recorder,
                eventSink, ownerAgentCode, llmCallMapper, tokenEstimator, operator,
                cacheUsageProbe, budgetRegistry, idleTimeoutSeconds, null, agentToolLoop);
    }

    public SubAgentToolCallback(AiAgentChild child, AgentContextFactory ctxFactory,
                               String sessionId, AgentCallDepth depth, Long parentAgentId,
                               IAiChatSessionService sessionService, ChatMessageRecorder recorder,
                               ChatEventSink eventSink, String ownerAgentCode,
                               AiLlmCallMapper llmCallMapper, TokenEstimator tokenEstimator,
                               AgentContextFactory.OperatorHolder operator,
                               com.ruoyi.system.ai.metering.CacheUsageProbe cacheUsageProbe,
                               com.ruoyi.system.tool.ToolBudgetRegistry budgetRegistry,
                               long idleTimeoutSeconds, TraceSpanRecorder traceRecorder,
                               AgentToolLoop agentToolLoop) {
        this.child = child;
        this.ctxFactory = ctxFactory;
        this.sessionId = sessionId;
        this.depth = depth;
        this.parentAgentId = parentAgentId;
        this.sessionService = sessionService;
        this.recorder = recorder;
        this.eventSink = eventSink != null ? eventSink : ChatEventSink.noop();
        this.ownerAgentCode = ownerAgentCode;
        this.llmCallMapper = llmCallMapper;
        this.tokenEstimator = tokenEstimator;
        this.operator = operator;
        this.cacheUsageProbe = cacheUsageProbe;
        this.budgetRegistry = budgetRegistry;
        this.idleTimeoutSeconds = idleTimeoutSeconds;
        this.traceRecorder = traceRecorder;
        this.agentToolLoop = agentToolLoop;
    }

    /**
     * 拼装子智能体工具定义。计量与装配共用,避免两套文案漂移。
     */
    public static ToolDefinition buildDefinition(AiAgentChild child) {
        String name = child.getChildAgentCode();
        if (name == null || name.isEmpty()) {
            // child_agent_code 来自 left join,子 agent 被删时为 null,用 ID 兜底避免工具名冲突
            name = "agent_" + child.getChildAgentId();
        }
        String desc = child.getTriggerDesc();
        if (desc == null || desc.isEmpty()) {
            desc = "处理" + (child.getChildAgentName() != null ? child.getChildAgentName() : "子智能体") + "相关的任务";
        }
        return DefaultToolDefinition.builder()
                .name(name)
                .description(desc)
                .inputSchema(INPUT_SCHEMA)
                .build();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return buildDefinition(child);
    }

    @Override
    public String call(String toolInput) {
        return doCall(toolInput);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return doCall(toolInput);
    }

    /**
     * 执行子 agent:解析 query → 装配无状态上下文 → {@link AgentToolLoop} → 记账 → 返回文本。
     *
     * <p>必须 {@code blockLast} 阻塞到完成:子 agent 跑在父级工具线程上,结果要 return 给父级循环。
     * 异常向上抛(父循环感知失败),finally 仍记录本次调用。
     */
    private String doCall(String toolInput) {
        String query = parseQuery(toolInput);
        long start = System.currentTimeMillis();
        String code = getToolDefinition().name();
        ContextView traceCtx = ToolCallReactiveContextHolder.getContext();
        ToolCallIdMatcher idMatcher = traceCtx == null
                ? null : traceCtx.getOrDefault(ToolCallIdMatcher.CTX_KEY, null);
        String upstreamCallId = idMatcher == null ? null : idMatcher.take(code, toolInput);
        // 每次调用生成一个实例 ID:同一轮里同一个子 agent 可能被调多次(父模型常见
        // 「先派活再跟进进度」的用法),事件按 agentCode 归属必然串卡 —— 前端曾把第 2 次
        // 调用的全部步骤渲染进第 1 张卡片,第 2 张永远停在「思考中」。
        // invId 作为本次调用所有内部事件(工具/思考/文本/嵌套子 agent)的 owner 标签下传。
        String invId = upstreamCallId != null && !upstreamCallId.isBlank()
                ? upstreamCallId : code + "#" + java.util.UUID.randomUUID().toString().substring(0, 8);
        String result = null;
        boolean ok = true;
        // 声明在 try 外:finally 里要落库,失败中断时的半截思考同样有价值。
        // 循环每轮 flushThinking 会清缓冲;这里只兜底流中断时没冲出去的残留。
        StringBuilder reasoningBuf = new StringBuilder();
        // 同样在 try 外:预算耗尽中断时,已累积的部分回答要随降级结果交回父级。
        StringBuilder reply = new StringBuilder();
        // 链路追踪:开 subagent span,子 agent 的 LLM/工具挂它下面(父=外层 batch/工具 span)
        String traceRunId = traceCtx == null
                ? null : traceCtx.getOrDefault(TraceSpanRecorder.CTX_RUN_ID, null);
        Long traceParentSpanId = traceCtx == null
                ? null : traceCtx.getOrDefault(TraceSpanRecorder.CTX_PARENT_SPAN, null);
        Long subSpanId = null;
        if (traceRecorder != null && traceRunId != null)
        {
            subSpanId = traceRecorder.start(traceRunId, sessionId, AiTraceSpan.TYPE_SUBAGENT,
                    traceParentSpanId, span -> {
                        span.setAgentId(parentAgentId);
                        span.setSubAgentId(child.getChildAgentId());
                        span.setToolName(code);
                    });
        }

        // 推 agent_start 事件(owner 非空时前端把这个 agent step 嵌进上一层;invId 标识本次调用实例)
        eventSink.emit(ChatEventJson.agentStart(child.getChildAgentName(), code, ownerAgentCode, invId));
        try {
            // 深度 + 环路保护(§5.4):enter 抛 ServiceException 时本次调用直接失败
            AgentCallDepth next = depth.enter(child.getChildAgentId());

            // 子 agent 无状态:conversationId 为 null
            // 把 eventSink 传下去,子 agent 自己的工具回调才能推 tool 事件(owner=子 agent code)
            // 把 operator 传下去,子 agent 的工具(如 update/delete)能继承父链路的操作者身份做权限校验
            // invId 下传:子 agent 内部工具与更深层嵌套子 agent 的事件都以它为归属标签
            AgentContext ctx = ctxFactory.buildStateless(child.getChildAgentId(), sessionId, next,
                    eventSink, operator, invId);

            // 子 agent 可能用与父完全不同的模型:把它自己模型的输入预算注册到 session 级
            // ToolBudget,token 判定按 agent 取各自窗口(见 ToolBudget.inputBudgetByAgent)。
            // 重复调用重复注册,值相同无副作用;模型热更新后下一轮覆盖生效。
            if (budgetRegistry != null)
            {
                com.ruoyi.system.tool.ToolBudget budget = budgetRegistry.get(sessionId);
                if (budget != null)
                {
                    budget.registerInputBudget(child.getChildAgentId(), ctx.inputBudget());
                }
            }

            List<Message> messages = new ArrayList<>(2);
            if (ctx.systemPrompt() != null && !ctx.systemPrompt().isEmpty())
            {
                messages.add(new SystemMessage(ctx.systemPrompt()));
            }
            messages.add(new UserMessage(composeAssignedUser(query, traceRunId)));

            // 子智能体 depth = 进入后链长度(1+)
            int callDepth = next.chain().size();
            String configModelName = ctx.model() != null ? ctx.model().getModelCode() : null;
            LlmCallCollector collector = new LlmCallCollector(
                    sessionId, child.getChildAgentId(), null,
                    ctx.modelId(), configModelName, callDepth,
                    llmCallMapper, tokenEstimator, cacheUsageProbe, budgetRegistry,
                    traceRecorder, traceRunId, subSpanId);

            AtomicInteger reasoningIteration = new AtomicInteger(1);
            Runnable flushThinking = () -> recordThinking(reasoningBuf, traceRunId, invId,
                    "reasoning:" + reasoningIteration.getAndIncrement());

            Flux<ChatResponse> flux = agentToolLoop.run(new AgentToolLoopSpec(
                    ctx.chatModel(), ctx.chatOptions(), messages, ctx.tools(),
                    ctx.inputBudget(), collector, eventSink, ChatTurnCallbacks.noop(),
                    traceRunId, sessionId, null,
                    child.getChildAgentId(), subSpanId, invId,
                    reply, reasoningBuf, flushThinking, ctx.reasoningEnabled(), ctx.inputModalities()));
            // 空闲超时兜底:流僵死(无任何 chunk)超时时切断,降级返回已累积的部分结果,
            // 防止 blockLast 无限等把父级工具线程拖死。注意工具执行期间流本来就静默,
            // 该值必须大于最长工具执行时长(shell 30s / MCP 60s,默认 300s 足够)。
            if (idleTimeoutSeconds > 0)
            {
                flux = flux.timeout(java.time.Duration.ofSeconds(idleTimeoutSeconds),
                        Flux.error(new com.ruoyi.system.tool.ToolBudgetExceededException(
                                "[系统提示] 子智能体流超过 " + idleTimeoutSeconds
                                        + " 秒无任何输出,判定连接僵死,本轮已中断。")));
            }

            // blockLast 而非 toStream().forEach:toStream 另起线程订阅+队列,
            // 子 agent 的 Flux 若在调用线程上 publish 容易死锁;
            // blockLast 只用 CountDownLatch 等完成信号,doOnNext 回调在发布线程跑,不依赖调用线程。
            // 记最后一个非 null finish_reason:blockLast 后据此判定子 agent 是否正常完成。
            // 正常完成的流末尾是 STOP;中途断流(上游断连/响应不完整)常被 Spring AI 吞成
            // finish_reason=None 的末包,blockLast 静默返回,部分回答会被父误当成"子还在工作"
            // 的进度(实测 run 3f4db051:子 agent 流中途断,返回"先规划项目结构"的开头,
            // 父反复 runShell 等产出两分钟)。
            // 勿在此再累加/记账,避免与 loop.onResponse 双计
            String[] lastFinishReason = {null};
            flux.doOnNext(response -> {
                String fr = finishReasonOf(response);
                if (fr != null) {
                    lastFinishReason[0] = fr;
                }
            }).blockLast();

            result = reply.toString();
            // 流异常中断兜底:末轮 finish 非 STOP,子 agent 实际没正常完成。降级:仍把部分
            // 回答交回父级(有总比无好),但 ok=false 并附中断说明,父据此决定重试/换路子,
            // 不会误以为子还在工作而干等产出。
            if (!"STOP".equalsIgnoreCase(lastFinishReason[0])) {
                ok = false;
                String fr = lastFinishReason[0];
                result = (result.isEmpty() ? "" : result + "\n\n")
                        + "[子智能体「" + child.getChildAgentName() + "」流未正常完成"
                        + (fr == null ? ",上游响应中断(无 finish_reason)" : ",finish=" + fr) + "]";
                log.warn("子智能体[{}]流未正常完成 finish={} reply_len={}",
                        child.getChildAgentName(), fr, reply.length());
            }
            // 子 agent token 归因到自己的 session_agent 行(§5.2)
            LlmCallCollector.TurnUsage turn = collector.onComplete(query, result);
            recordUsage(turn, query, result);
            return capForParent(result);
        } catch (com.ruoyi.system.tool.ToolBudgetExceededException e) {
            // 子 agent 被流内闸口中断(预算耗尽/usage 流损坏/僵死超时):不穿透炸掉整个父 run
            // (父的窗口往往还很空),降级为「部分结果 + 中断说明」作为 tool result 交回父级,
            // 由父决定收尾或换路子。ok=false 落库(tool_success=0)、agent_end 事件照发。
            ok = false;
            String partial = reply.toString();
            result = (partial.isEmpty() ? "" : partial + "\n\n")
                    + "[子智能体「" + child.getChildAgentName() + "」提前中断]\n"
                    + e.getMessage();
            log.warn("子智能体[{}]中断,降级返回部分结果: {}", child.getChildAgentName(), e.getMessage());
            return capForParent(result);
        } catch (RuntimeException e) {
            ok = false;
            result = "ERROR: " + e.getMessage();
            throw e;
        } finally {
            // 链路追踪:收掉 subagent span
            if (traceRecorder != null && subSpanId != null)
            {
                final boolean fOk = ok;
                traceRecorder.finish(subSpanId,
                        fOk ? AiTraceSpan.STATUS_SUCCEEDED : AiTraceSpan.STATUS_FAILED,
                        span -> span.setDurationMs(System.currentTimeMillis() - start));
            }
            // 推 agent_end 事件(owner/invId 与 agent_start 一致,前端定位同一次调用实例)
            eventSink.emit(ChatEventJson.agentEnd(
                    child.getChildAgentName(), result, ok, System.currentTimeMillis() - start,
                    ownerAgentCode, invId));
            recordThinking(reasoningBuf, traceRunId, invId, "reasoning:" + invId);
            recordCall(query, result, System.currentTimeMillis() - start, ok,
                    traceRunId, invId);
        }
    }

    /**
     * 提取本次响应的 finish_reason(与 {@link LlmCallCollector} 同口径,过滤 null/"null"/空)。
     * blockLast 后据此判定子 agent 流是正常完成(STOP)还是异常中断(None/TOOL_CALLS/LENGTH)。
     */
    private static String finishReasonOf(ChatResponse response) {
        if (response.getResult() == null) {
            return null;
        }
        ChatGenerationMetadata gm = response.getResult().getMetadata();
        if (gm == null) {
            return null;
        }
        String fr = gm.getFinishReason();
        return (fr != null && !fr.isEmpty() && !"null".equalsIgnoreCase(fr)) ? fr : null;
    }

    /**
     * 解析入参里的 query 字段。模型可能传 {@code {"query":"..."}} 或裸字符串,都兼容。
     */
    private String parseQuery(String toolInput) {
        if (toolInput == null || toolInput.isEmpty()) {
            throw new ServiceException("调用子智能体[" + child.getChildAgentName() + "]缺少 query 参数");
        }
        try {
            JSONObject obj = JSON.parseObject(toolInput);
            String q = obj.getString("query");
            if (q == null || q.isEmpty()) {
                // 模型没按 schema 传,退化用整个输入当 query
                return toolInput;
            }
            return q;
        } catch (Exception e) {
            // 非 JSON,当裸字符串用
            return toolInput;
        }
    }

    /**
     * 子 agent token 归因(§5.2):先确保子 agent join 到 session(role=worker),再记 token。
     * usage 拿不到时用字符数/3 粗估(与 controller finalizeTurn 同策略)。
     *
     * @param turn 本轮 LlmCallCollector 汇总(真实或估算)
     */
    private void recordUsage(LlmCallCollector.TurnUsage turn, String query, String result) {
        try {
            long tokens = turn != null ? turn.totalTokens() : 0;
            long prompt = turn != null ? turn.promptTokens() : 0;
            long completion = turn != null ? turn.completionTokens() : 0;
            int calls = turn != null ? turn.callCount() : 0;
            if (tokens <= 0) {
                tokens = (query.length() + (result != null ? result.length() : 0)) / 3L;
            }
            long contextLen = query.length() + (result != null ? result.length() : 0);
            sessionService.ensureAgentJoined(sessionId, child.getChildAgentId(), "worker");
            sessionService.recordTokenUsage(sessionId, child.getChildAgentId(),
                    tokens, prompt, completion, calls, contextLen);
        } catch (Exception e) {
            log.warn("记录子智能体[{}] token 失败: {}", child.getChildAgentName(), e.getMessage());
        }
    }

    /**
     * 工具调用记录(§6.3):写一行 ai_chat_message,message_type=TOOL,tool_source=agent。
     * <p>这里直接调 recorder 而非走 RecordingToolCallback,保证 toolSource 准确为 "agent"。
     */
    /**
     * 子智能体的思考落库(THINKING 行)。
     *
     * <p><b>agent_id 必须写子 agent 自己的 ID</b>:前端 {@code useTurnBuilder} 就是按
     * 「行的 agent_id == agent step 登记的 sub_agent_id」来做嵌套的,写成父 ID 会平铺到顶层。
     *
     * <p>子 agent 无状态({@code AgentContext.conversationId} 为 null),这里的
     * conversation_id 只是给这一行一个归属键 —— 该 conversation 不参与任何 LLM 记忆装配,
     * 且 THINKING 是 visible_to_llm='1',双重保证它进不了上下文。
     *
     * <p>落库失败只告警:思考是展示信息,不能因此让子智能体调用失败。
     */
    private void recordThinking(StringBuilder buf, String runId, String invId, String stepId) {
        if (buf == null || buf.length() == 0) {
            return;
        }
        String text = buf.toString();
        buf.setLength(0);
        try {
            recorder.recordThinking(ConversationIds.of(sessionId, child.getChildAgentId()),
                    sessionId, child.getChildAgentId(), text, runId,
                    stepId, invId);
        } catch (Exception e) {
            log.warn("记录子智能体思考失败: agent={}", getToolDefinition().name(), e);
        }
    }

    String composeAssignedUser(String query, String runId)
    {
        String original = ctxFactory != null ? ctxFactory.lookupRunInputText(runId) : null;
        if (original == null || original.isBlank())
        {
            return query;
        }
        return "[用户原始请求]\n" + original.trim()
                + "\n\n[上级交办]\n" + query
                + "\n\n说明:上级交办中未经用户确认的细节(配色/版块/技术选型)属于建议,"
                + "与用户原始请求冲突时以后者为准;关键决策不确定时不要自行发挥。";
    }

    private void recordCall(String args, String result, long durationMs, boolean ok,
                            String runId, String invId) {
        try {
            String convId = ConversationIds.of(sessionId, parentAgentId);
            // 落库存全文,tokens 按交回父级的那段算 —— 与普通工具同一条规则
            recorder.recordToolCall(convId, sessionId, parentAgentId, child.getChildAgentId(),
                    getToolDefinition().name(), args, result, "agent", durationMs, ok,
                    null, invId, runId, invId, ownerAgentCode, capForParent(result));
        } catch (Exception e) {
            log.warn("记录子智能体调用失败: agent={}", getToolDefinition().name(), e);
        }
    }

    /**
     * 子智能体的回答交回父级前,按与普通工具相同的上限截断。
     *
     * <p>agent-as-tool 原先是这条规则的唯一例外:它的回答直接进父级上下文,不受任何上限约束。
     * 一个跑飞的子智能体能凭一条回答把父级窗口顶满,而跨轮重建时又会被
     * {@code DbChatMemory} 按上限裁一刀 —— 「重建结果 == 本轮所见」这条不变量在它这儿是破的。
     * 统一之后:本轮所见、tokens、跨轮重建三者对同一条子智能体回答给出同一段文本。
     *
     * <p>拿不到会话预算时不截断(与普通工具 {@code budget == null} 的处理一致)。
     */
    private String capForParent(String result) {
        if (result == null || budgetRegistry == null) {
            return result;
        }
        com.ruoyi.system.tool.ToolBudget budget = budgetRegistry.get(sessionId);
        if (budget == null) {
            return result;
        }
        return ToolResultText.cap(result, budget.maxResultLines(), budget.maxResultChars());
    }
}
