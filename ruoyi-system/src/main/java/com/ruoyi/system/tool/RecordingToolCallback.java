package com.ruoyi.system.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ruoyi.system.ai.context.ToolResultText;
import com.ruoyi.system.ai.memory.ChatMessageRecorder;
import com.ruoyi.system.ai.memory.ConversationIds;
import com.ruoyi.system.ai.run.ToolCallIdMatcher;
import com.ruoyi.system.ai.event.ChatEventSink;
import com.ruoyi.system.ai.event.UiArtifactContext;
import com.ruoyi.system.ai.event.UiArtifactEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.SyncMcpToolCallback;
import org.springframework.ai.model.tool.internal.ToolCallReactiveContextHolder;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import com.ruoyi.system.ai.sse.ChatEventJson;
import com.ruoyi.system.ai.trace.TraceSpanRecorder;
import com.ruoyi.system.domain.AiTraceSpan;
import reactor.util.context.ContextView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 工具调用装饰器:预算 / 人工确认 / 记账 / 事件 / PII 落库脱敏。
 *
 * @author ruoyi
 */
public class RecordingToolCallback implements ToolCallback
{
    private static final Logger log = LoggerFactory.getLogger(RecordingToolCallback.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ToolCallback delegate;
    private final String sessionId;
    /** 文件工作区键；项目会话共享 project-{id}，消息与记账仍使用 sessionId。 */
    private String workspaceKey;
    /**
     * 是否给 MCP 远端执行工具注入工作区键(exec-tools-mode=mcp 时开)。
     * <p>注入点见 {@link #routeWorkspaceKey(String)}:只在 delegate 是 SyncMcpToolCallback
     * 时把 {@code _workspaceKey} 塞进 args envelope,让 OPI server 按会话隔离落盘;
     * 模型可见的 inputSchema 不含该字段。内置工具不走这里。
     */
    private boolean mcpWorkspaceRouting = false;
    private final Long agentId;
    private final Long subAgentId;
    private final ChatMessageRecorder recorder;
    private final ChatEventSink eventSink;
    private final String ownerAgentCode;
    private final String operatorUsername;
    private final Long operatorUserId;
    private final boolean operatorAdmin;

    private ToolBudgetRegistry budgetRegistry;
    private ToolConfirmBroker confirmBroker;
    private ToolPolicyService policyService;
    /** 链路追踪(可为 null):工具 span 记录器 */
    private TraceSpanRecorder traceRecorder;
    /** 当前线程正在执行的工具 span(并行工具各线程独立) */
    private final ThreadLocal<Long> toolSpan = new ThreadLocal<>();
    /** 可选:未注入时用无持久化的本地发射器,保证单测与旧构造仍能发 ui */
    private UiArtifactEmitter uiEmitter = new UiArtifactEmitter();

    public RecordingToolCallback(ToolCallback delegate, String sessionId, Long agentId,
                                 Long subAgentId, ChatMessageRecorder recorder, ChatEventSink eventSink,
                                 String ownerAgentCode)
    {
        this(delegate, sessionId, agentId, subAgentId, recorder, eventSink, ownerAgentCode,
                null, null, false);
    }

    public RecordingToolCallback(ToolCallback delegate, String sessionId, Long agentId,
                                 Long subAgentId, ChatMessageRecorder recorder, ChatEventSink eventSink,
                                 String ownerAgentCode,
                                 String operatorUsername, Long operatorUserId, boolean operatorAdmin)
    {
        this.delegate = delegate;
        this.sessionId = sessionId;
        this.workspaceKey = sessionId;
        this.agentId = agentId;
        this.subAgentId = subAgentId;
        this.recorder = recorder;
        this.eventSink = eventSink != null ? eventSink : ChatEventSink.noop();
        this.ownerAgentCode = ownerAgentCode;
        this.operatorUsername = operatorUsername;
        this.operatorUserId = operatorUserId;
        this.operatorAdmin = operatorAdmin;
    }

    public RecordingToolCallback withBudget(ToolBudgetRegistry budgetRegistry)
    {
        this.budgetRegistry = budgetRegistry;
        return this;
    }

    public RecordingToolCallback withConfirm(ToolConfirmBroker confirmBroker)
    {
        this.confirmBroker = confirmBroker;
        return this;
    }

    public RecordingToolCallback withPolicy(ToolPolicyService policyService)
    {
        this.policyService = policyService;
        return this;
    }

    public RecordingToolCallback withTracing(TraceSpanRecorder traceRecorder)
    {
        this.traceRecorder = traceRecorder;
        return this;
    }

    public RecordingToolCallback withUiEmitter(UiArtifactEmitter uiEmitter)
    {
        if (uiEmitter != null)
        {
            this.uiEmitter = uiEmitter;
        }
        return this;
    }

    public RecordingToolCallback withWorkspaceKey(String workspaceKey)
    {
        if (workspaceKey != null && !workspaceKey.isBlank())
        {
            this.workspaceKey = workspaceKey;
        }
        return this;
    }

    public RecordingToolCallback withMcpWorkspaceRouting(boolean mcpWorkspaceRouting)
    {
        this.mcpWorkspaceRouting = mcpWorkspaceRouting;
        return this;
    }

    @Override
    public ToolDefinition getToolDefinition()
    {
        return delegate.getToolDefinition();
    }

    @Override
    public String call(String toolInput)
    {
        // record 记账/事件用模型原始输入,routeWorkspaceKey 只为 MCP 委托附加信封字段
        return record(toolInput, () -> delegate.call(routeWorkspaceKey(toolInput)));
    }

    @Override
    public String call(String toolInput, ToolContext toolContext)
    {
        return record(toolInput, () -> delegate.call(routeWorkspaceKey(toolInput), toolContext));
    }

    /**
     * MCP 远端执行工具:把会话工作区键塞进 args envelope,让远端 server 弹出后按会话 bind、
     * 落到 {@code {workspace-root}/{workspaceKey}} —— 工具写入与主应用工作区抽屉看到同一目录。
     * <p>仅当开启 {@link #mcpWorkspaceRouting} 且 delegate 是 MCP 工具时生效;
     * 注入失败退回原始 input(远端落到共享沙箱,不阻断调用)。
     */
    private String routeWorkspaceKey(String toolInput)
    {
        if (!mcpWorkspaceRouting || !(delegate instanceof SyncMcpToolCallback))
        {
            return toolInput;
        }
        try
        {
            if (toolInput == null || toolInput.isBlank())
            {
                return "{\"" + WorkspaceSandbox.WORKSPACE_KEY_FIELD + "\":\"" + workspaceKey + "\"}";
            }
            ObjectNode node = (ObjectNode) MAPPER.readTree(toolInput);
            node.put(WorkspaceSandbox.WORKSPACE_KEY_FIELD, workspaceKey);
            return MAPPER.writeValueAsString(node);
        }
        catch (Exception e)
        {
            log.warn("注入 workspaceKey 到 MCP 工具参数失败,远端将落到共享沙箱: {}", e.getMessage());
            return toolInput;
        }
    }

    private String record(String args, Supplier<String> action)
    {
        long start = System.currentTimeMillis();
        String result = null;
        boolean ok = true;
        String toolName = getToolDefinition().name();
        String src = source();

        // 链路追踪:工具 span 挂到 Reactor 上下文里的 runId/batch span 下
        // (并行工具线程由 ParallelToolCallingManager 复制上下文,这里读到的是各自的父)
        ContextView traceCtx = ToolCallReactiveContextHolder.getContext();
        String traceRunId = traceCtx == null
                ? null : traceCtx.getOrDefault(TraceSpanRecorder.CTX_RUN_ID, null);
        Long traceParentSpanId = traceCtx == null
                ? null : traceCtx.getOrDefault(TraceSpanRecorder.CTX_PARENT_SPAN, null);
        // tool_call_id:重建 assistant(tool_calls)+tool 配对的唯一钥匙,拿不到就留 null
        ToolCallIdMatcher idMatcher = traceCtx == null
                ? null : traceCtx.getOrDefault(ToolCallIdMatcher.CTX_KEY, null);
        String toolCallId = idMatcher == null ? null : idMatcher.take(toolName, args);
        String stepId = toolCallId != null && !toolCallId.isBlank()
                ? toolCallId : "tool:" + java.util.UUID.randomUUID();
        Long toolSpanId = null;
        if (traceRecorder != null && traceRunId != null)
        {
            final Long fAgentId = agentId;
            final Long fSubAgentId = subAgentId;
            toolSpanId = traceRecorder.start(traceRunId, sessionId, AiTraceSpan.TYPE_TOOL,
                    traceParentSpanId, span -> {
                        span.setAgentId(fAgentId);
                        span.setSubAgentId(fSubAgentId);
                        span.setToolName(toolName);
                    });
            toolSpan.set(toolSpanId);
        }

        // —— 预算(在 tool_start 之前) ——
        ToolBudget budget = budgetRegistry == null ? null : budgetRegistry.get(sessionId);
        if (budget != null)
        {
            // 带上 agentId:token 判定只看本 agent 自己的上下文。
            // 共用一个值时,子智能体把 prompt 顶到窗口上限会把只用了几 K 的父一起卡死。
            ToolBudget.Verdict verdict = budget.acquire(toolName, agentId);
            if (verdict == ToolBudget.Verdict.HARD_EXCEEDED)
            {
                finishToolSpan(false, 0);
                throw new ToolBudgetExceededException(budget.describeExhausted());
            }
            if (verdict == ToolBudget.Verdict.SOFT_EXCEEDED)
            {
                String notice = budget.describeExhausted();
                emitAndRecordRejected(toolName, src, args, notice, true,
                        traceRunId, toolCallId, stepId);
                return notice;
            }
        }

        // —— 危险操作人工确认 ——
        if (needsConfirm(toolName) && confirmBroker != null && sessionId != null)
        {
            String confirmId = confirmBroker.newConfirmId();
            // 先建步骤再进入 WAITING，刷新恢复时快照不会被后到的 start 覆盖成 RUNNING。
            eventSink.emit(ChatEventJson.toolStart(toolName, src, ownerAgentCode,
                    PiiRedactor.forStorage(args), stepId));
            eventSink.emit(ChatEventJson.toolConfirmRequired(
                    confirmId, toolName, src, PiiRedactor.forStorage(args), ownerAgentCode, stepId));
            boolean approved = confirmBroker.await(sessionId, confirmId, toolName);
            if (!approved)
            {
                String notice = "[系统提示] 用户拒绝或超时未确认危险操作「" + toolName
                        + "」，已取消执行。请改用更安全的方式，或向用户说明原因。";
                // tool_start 已发过,这里只发 end
                long ms = System.currentTimeMillis() - start;
                finishToolSpan(false, ms);
                String safeArgs = PiiRedactor.forStorage(args);
                String safeResult = PiiRedactor.forStorage(notice);
                eventSink.emit(ChatEventJson.toolEnd(toolName, src, safeArgs, safeResult, true, ms,
                        ownerAgentCode, null, stepId));
                try
                {
                    recorder.recordToolCall(ConversationIds.of(sessionId, agentId), sessionId, agentId,
                            subAgentId, toolName, safeArgs, safeResult, src, ms, true, null, toolCallId,
                            traceRunId, stepId, ownerAgentCode);
                }
                catch (Exception logEx)
                {
                    log.warn("记录工具调用失败: tool={}", toolName, logEx);
                }
                return notice;
            }
            // 已确认:继续执行(tool_start 已发,不再重复)
            try
            {
                String prevSession = WorkspaceContext.bind(sessionId);
                String prevWorkspace = WorkspaceContext.bindWorkspaceKey(workspaceKey);
                String prevOperator = OperatorContext.bind(operatorUsername, operatorUserId, operatorAdmin);
                try
                {
                    result = action.get();
                }
                finally
                {
                    OperatorContext.restore(prevOperator);
                    WorkspaceContext.restoreWorkspaceKey(prevWorkspace);
                    WorkspaceContext.restore(prevSession);
                }
                return finishOk(toolName, src, args, result, budget, start, true,
                        toolCallId, traceRunId, stepId);
            }
            catch (RuntimeException e)
            {
                return finishError(toolName, src, args, e, start, true,
                        toolCallId, traceRunId, stepId);
            }
        }

        // —— 普通路径 ——
        // 带入参一起发:执行中前端就能显示「在读哪个文件 / 在跑什么命令」,
        // 而不是一个只有工具名的空壳(见 ChatEventJson#toolStart)。
        eventSink.emit(ChatEventJson.toolStart(toolName, src, ownerAgentCode,
                PiiRedactor.forStorage(args), stepId));
        try
        {
            String prevSession = WorkspaceContext.bind(sessionId);
            String prevWorkspace = WorkspaceContext.bindWorkspaceKey(workspaceKey);
            String prevOperator = OperatorContext.bind(operatorUsername, operatorUserId, operatorAdmin);
            try
            {
                result = action.get();
            }
            finally
            {
                OperatorContext.restore(prevOperator);
                WorkspaceContext.restoreWorkspaceKey(prevWorkspace);
                WorkspaceContext.restore(prevSession);
            }
            return finishOk(toolName, src, args, result, budget, start, false,
                    toolCallId, traceRunId, stepId);
        }
        catch (RuntimeException e)
        {
            return finishError(toolName, src, args, e, start, false,
                    toolCallId, traceRunId, stepId);
        }
    }

    /**
     * @param startAlreadyEmitted 确认路径已发过 tool_start 时为 true,finally 里不再依赖外层 start 事件顺序
     */
    private String finishOk(String toolName, String src, String args, String result,
                            ToolBudget budget, long start, boolean startAlreadyEmitted,
                            String toolCallId, String runId, String stepId)
    {
        // 返回给模型的是原文(可截断);落库/事件用脱敏
        String forModel = capForModel(result, budget);
        List<ToolAttachment> attachments = takeAttachments(toolName);
        collectPromptMedia();
        // 未抛异常也可能逻辑失败(如 runShell 非零退出);原文仍交给模型
        boolean ok = resolveCallOk();
        long ms = System.currentTimeMillis() - start;
        finishToolSpan(ok, ms);
        String safeArgs = PiiRedactor.forStorage(args);
        String safeResult = PiiRedactor.forStorage(result);
        // 事件带截断后的正文,不带全文。这一份要走三段路:WebSocket 推给浏览器、
        // 投影进 ai_chat_run_step.output_data(超阈值溢出到文件,表内预览)、跨实例 Redis 广播。
        // 带全文意味着一个 50KB 的工具结果三处各存/各传一遍,而全文在 tools/*.txt 与
        // ai_chat_message 里本来就有,前端点「查看完整结果」照常拉得到。
        // 用同一套上限:展示出来的就是模型这轮真正读到的那段,与历史 timeline 口径也对齐。
        String eventResult = PiiRedactor.forStorage(forModel);
        eventSink.emit(ChatEventJson.toolEnd(toolName, src, safeArgs, eventResult, ok, ms,
                ownerAgentCode, attachments, stepId));
        emitUiArtifacts(stepId, runId, attachments);
        try
        {
            // 落库存全文(审计),tokens 按 forModel —— 也就是模型这轮真正读到的那段。
            // 让 recorder 照着 result 再推一遍就会重新分叉出第二份截断公式。
            recorder.recordToolCall(ConversationIds.of(sessionId, agentId), sessionId, agentId,
                    subAgentId, toolName, safeArgs, safeResult, src, ms, ok, attachments,
                    toolCallId, runId, stepId, ownerAgentCode, forModel);
        }
        catch (Exception logEx)
        {
            log.warn("记录工具调用失败: tool={}", toolName, logEx);
        }
        return forModel;
    }

    private String finishError(String toolName, String src, String args, RuntimeException e,
                               long start, boolean startAlreadyEmitted, String toolCallId,
                               String runId, String stepId)
    {
        String result = "ERROR: " + describeError(e);
        log.warn("工具执行失败 tool={} args={}", toolName, truncate(PiiRedactor.forStorage(args), 800), e);
        long ms = System.currentTimeMillis() - start;
        finishToolSpan(false, ms);
        String safeArgs = PiiRedactor.forStorage(args);
        String safeResult = PiiRedactor.forStorage(result);
        eventSink.emit(ChatEventJson.toolEnd(toolName, src, safeArgs, safeResult, false, ms,
                ownerAgentCode, null, stepId));
        try
        {
            recorder.recordToolCall(ConversationIds.of(sessionId, agentId), sessionId, agentId,
                    subAgentId, toolName, safeArgs, safeResult, src, ms, false, null, toolCallId,
                    runId, stepId, ownerAgentCode);
        }
        catch (Exception logEx)
        {
            log.warn("记录工具调用失败: tool={}", toolName, logEx);
        }
        throw e;
    }

    /** 链路追踪:结束当前工具 span(ThreadLocal 里取,失败/拒绝也算完成终态) */
    private void finishToolSpan(boolean ok, long ms)
    {
        Long spanId = toolSpan.get();
        toolSpan.remove();
        if (traceRecorder != null && spanId != null)
        {
            traceRecorder.finish(spanId,
                    ok ? AiTraceSpan.STATUS_SUCCEEDED : AiTraceSpan.STATUS_FAILED,
                    span -> span.setDurationMs(ms));
        }
    }

    private void emitAndRecordRejected(String toolName, String src, String args, String notice, boolean ok,
                                       String runId, String toolCallId, String stepId)
    {
        finishToolSpan(ok, 0);
        String safeArgs = PiiRedactor.forStorage(args);
        String safeResult = PiiRedactor.forStorage(notice);
        eventSink.emit(ChatEventJson.toolStart(toolName, src, ownerAgentCode, safeArgs, stepId));
        eventSink.emit(ChatEventJson.toolEnd(toolName, src, safeArgs, safeResult, ok, 0,
                ownerAgentCode, null, stepId));
        try
        {
            recorder.recordToolCall(ConversationIds.of(sessionId, agentId), sessionId, agentId,
                    subAgentId, toolName, safeArgs, safeResult, src, 0, ok, null, toolCallId,
                    runId, stepId, ownerAgentCode);
        }
        catch (Exception logEx)
        {
            log.warn("记录工具调用失败: tool={}", toolName, logEx);
        }
    }

    private boolean needsConfirm(String toolName)
    {
        if (policyService != null)
        {
            return policyService.requireConfirm(toolName);
        }
        return "bash".equals(toolName);
    }

    private List<ToolAttachment> takeAttachments(String toolName)
    {
        if (delegate instanceof AttachmentAware aware)
        {
            try
            {
                return aware.lastAttachments();
            }
            catch (Exception ex)
            {
                log.warn("取工具附件失败: tool={}", toolName, ex);
            }
        }
        return null;
    }

    /**
     * 在 tool_end 之后发出登记过的 UI 产物。
     * 失败只打日志,绝不回灌到工具结果(观察者故障不能打断模型循环)。
     */
    private void emitUiArtifacts(String producerStepId, String runId,
                                 List<ToolAttachment> attachments)
    {
        List<UiArtifact> artifacts = new ArrayList<>();
        if (delegate instanceof UiArtifactAware aware)
        {
            try
            {
                List<UiArtifact> declared = aware.lastArtifacts();
                if (declared != null) artifacts.addAll(declared);
            }
            catch (Exception ex)
            {
                log.warn("取工具 UI 产物失败: tool={}", getToolDefinition().name(), ex);
            }
        }

        // 生图/视频/语音等工具已经通过附件声明了工作区文件。若工具没有自己给出
        // workspace.changes，则在统一装饰器里补成结构化文件变化，避免每个生成工具重复实现。
        boolean hasWorkspaceChanges = artifacts.stream().anyMatch(a -> a != null
                && UiArtifactNames.WORKSPACE_CHANGES.equals(a.name()));
        if (!hasWorkspaceChanges)
        {
            UiArtifact attachmentChanges = WorkspaceChanges.attachmentArtifact(attachments);
            if (attachmentChanges != null) artifacts.add(attachmentChanges);
        }
        if (artifacts.isEmpty())
        {
            return;
        }
        if (producerStepId == null || producerStepId.isBlank())
        {
            log.warn("跳过 UI 产物: tool={} 缺少 producerStepId,无法幂等", getToolDefinition().name());
            return;
        }
        UiArtifactContext ctx = new UiArtifactContext(
                eventSink, sessionId, runId, null, agentId, ownerAgentCode, producerStepId);
        for (UiArtifact artifact : artifacts)
        {
            try
            {
                uiEmitter.emit(ctx, artifact);
            }
            catch (Exception ex)
            {
                log.warn("发送 UI 产物失败,不影响工具结果: name={}",
                        artifact != null ? artifact.name() : null, ex);
            }
        }
    }

    private void collectPromptMedia()
    {
        if (delegate instanceof PromptMediaAware aware)
        {
            try
            {
                PromptMediaBuffer.addAll(aware.lastPromptMedia());
            }
            catch (Exception ex)
            {
                log.warn("取工具视觉附件失败: tool={}", delegate.getToolDefinition().name(), ex);
            }
        }
    }

    /** 未实现 {@link ToolOutcomeAware} 或返回 null 时,保持「未抛异常即成功」。 */
    private boolean resolveCallOk()
    {
        if (delegate instanceof ToolOutcomeAware aware)
        {
            try
            {
                Boolean reported = aware.lastCallOk();
                if (reported != null)
                {
                    return reported;
                }
            }
            catch (Exception ex)
            {
                log.warn("取工具逻辑成败失败: tool={}", getToolDefinition().name(), ex);
            }
        }
        return true;
    }

    /**
     * 裁出本轮交给模型的正文。截断规则本身在 {@link ToolResultText} —— 跨轮重建与 tokens 估算
     * 也从那里取,三处同源,加新维度不必再记得同步另外两个文件。
     */
    private String capForModel(String result, ToolBudget budget)
    {
        if (result == null)
        {
            return null;
        }
        int maxLines = budget == null ? Integer.MAX_VALUE : budget.maxResultLines();
        int maxChars = budget == null ? Integer.MAX_VALUE : budget.maxResultChars();
        String capped = ToolResultText.cap(result, maxLines, maxChars);
        if (budget != null)
        {
            budget.recordResult(capped.length());
        }
        return capped;
    }

    private static String describeError(Throwable e)
    {
        StringBuilder sb = new StringBuilder();
        Throwable cur = e;
        int depth = 0;
        while (cur != null && depth < 5)
        {
            String msg = cur.getMessage();
            if (msg != null && !msg.isEmpty())
            {
                if (sb.length() > 0)
                {
                    sb.append(" <- ");
                }
                sb.append(cur.getClass().getSimpleName()).append(": ").append(truncate(msg, 300));
            }
            cur = cur.getCause();
            depth++;
        }
        return sb.length() > 0 ? sb.toString() : e.getClass().getSimpleName();
    }

    private static String truncate(String s, int max)
    {
        if (s == null)
        {
            return "";
        }
        String one = s.replaceAll("\\s+", " ");
        return one.length() <= max ? one : one.substring(0, max) + "…";
    }

    private String source()
    {
        if (subAgentId != null)
        {
            return "agent";
        }
        if (delegate instanceof com.ruoyi.system.tool.channel.ChannelToolCallback)
        {
            return com.ruoyi.system.tool.channel.ChannelToolCallback.SOURCE;
        }
        return delegate instanceof SyncMcpToolCallback ? "mcp" : "builtin";
    }
}
