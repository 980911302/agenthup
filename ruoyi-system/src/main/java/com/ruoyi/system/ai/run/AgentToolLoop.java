package com.ruoyi.system.ai.run;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import com.ruoyi.system.ai.memory.ConversationIds;
import com.ruoyi.system.ai.agent.ModelInputModalities;
import com.ruoyi.system.tool.PromptMediaBuffer;
import com.ruoyi.system.tool.ToolBudgetExceededException;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.model.tool.internal.ToolCallReactiveContextHolder;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.ai.tool.resolution.StaticToolCallbackResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.ruoyi.system.ai.context.ContextCleaner;
import com.ruoyi.system.ai.context.ContextOverflowGuard;
import com.ruoyi.system.ai.event.ChatEventSink;
import com.ruoyi.system.ai.memory.ChatMessageRecorder;
import com.ruoyi.system.ai.metering.LlmCallCollector;
import com.ruoyi.system.ai.sse.ChatEventJson;
import com.ruoyi.system.ai.trace.TraceSpanRecorder;
import com.ruoyi.system.domain.AiTraceSpan;
import io.micrometer.observation.ObservationRegistry;
import reactor.core.publisher.Flux;
import reactor.util.context.Context;
import reactor.util.context.ContextView;
import reactor.core.scheduler.Schedulers;

/**
 * 自建工具循环:stream → (可选)executeToolCalls → clean → 再 stream。
 *
 * <p>由本类驱动({@code internalToolExecutionEnabled=false}+{@link ToolCallingManager}),
 * 以便在每轮工具结果之后插入 {@link ContextCleaner}。结构对齐 OpenAiChatModel 官方内部循环,
 * 含 ReactiveContext / boundedElastic / returnDirect。循环只返回 {@link Flux},
 * 消费方式留给调用方。见 docs/ai/ai-context-compactor-phase2.md。
 */
@Component
public class AgentToolLoop
{
    private static final Logger log = LoggerFactory.getLogger(AgentToolLoop.class);

    /** 防御性断路:与 ToolBudget 业务轮次上限是两回事 */
    private static final int MAX_TOOL_LOOP_ITERATIONS = 100;

    @Autowired
    private ChatMessageRecorder messageRecorder;
    @Autowired
    private TraceSpanRecorder traceRecorder;

    /** 并行工具执行开关(默认关,开启后同一轮多个 tool_calls 并发执行) */
    @Value("${ai.chat.run.parallel-tools:false}")
    private boolean parallelTools;

    /** 最大并行工具数 */
    @Value("${ai.chat.run.max-parallel-tools:5}")
    private int maxParallelTools;

    /** 单个工具执行超时(毫秒),超时返回错误 ToolResponse */
    @Value("${ai.chat.run.tool-execution-timeout-ms:30000}")
    private long toolExecutionTimeoutMs;

    /**
     * 主链路 LLM 流空闲超时秒数:单段流式输出超过该时长无任何 chunk 判定上游僵死。
     * <p>只罩 {@code chatModel.stream} 这一段(在 concatWith 之前),工具执行期间流段
     * 已 complete 不计时。须大于最长串行工具静默期;{@code <=0} 关闭。
     */
    @Value("${ai.chat.run.llm-idle-timeout-seconds:300}")
    private long llmIdleTimeoutSeconds;

    /**
     * 串行工具批次整体超时秒数:超时为本批所有 toolCall 合成错误 ToolResponse,
     * 循环继续让模型看到失败自行收尾,不阻塞 worker 线程。
     * <p>须大于子 agent 合法总时长(其内部 idle 300s、预算轮次内可达 10 分钟级);{@code <=0} 关闭。
     */
    @Value("${ai.chat.run.tool-batch-timeout-seconds:600}")
    private long toolBatchTimeoutSeconds;

    /** 并行工具专用线程池(与 chatRunTaskExecutor 隔离,防止父子任务互相占槽死锁) */
    @Autowired
    @Qualifier("parallelToolTaskExecutor")
    private Executor parallelToolTaskExecutor;
    @Autowired
    private ContextCleaner contextCleaner;
    @Autowired
    private ContextOverflowGuard overflowGuard;

    /** 返回冷 {@link Flux};由调用方订阅。 */
    public Flux<ChatResponse> run(AgentToolLoopSpec spec)
    {
        AtomicInteger iterations = new AtomicInteger(0);
        ToolCallingManager toolCallingManager = buildToolCallingManager(spec.tools());
        return runToolLoop(spec, spec.messages(), toolCallingManager, iterations);
    }

    /**
     * 自建工具循环:stream → (可选)executeToolCalls → clean → 再 stream。
     * <p>结构对齐 OpenAiChatModel 官方内部循环,含 ReactiveContext / boundedElastic / returnDirect。
     */
    private Flux<ChatResponse> runToolLoop(
            AgentToolLoopSpec spec,
            List<Message> messages,
            ToolCallingManager toolCallingManager,
            AtomicInteger iterations)
    {
        ChatModel chatModel = spec.chatModel();
        ChatOptions options = spec.options();
        int inputBudget = spec.inputBudget();
        StringBuilder reply = spec.reply();
        StringBuilder reasoning = spec.reasoning();
        Runnable flushThinking = spec.flushThinking();
        LlmCallCollector collector = spec.collector();
        ChatEventSink eventSink = spec.eventSink();
        ChatTurnCallbacks cb = spec.callbacks();
        String runId = spec.runId();
        String sessionId = spec.sessionId();
        String conversationId = spec.conversationId();
        Long agentId = spec.agentId();
        String ownerAgentCode = spec.ownerAgentCode();

        if (!cb.shouldContinue())
        {
            return Flux.empty();
        }
        if (iterations.incrementAndGet() > MAX_TOOL_LOOP_ITERATIONS)
        {
            return Flux.error(new IllegalStateException(
                    "工具循环超过最大迭代 " + MAX_TOOL_LOOP_ITERATIONS + ",已中断以防死循环"));
        }
        if (chatModel == null)
        {
            return Flux.error(new IllegalStateException("ChatModel 未装配"));
        }

        // 链路追踪:每轮流开始时开 llm span(工具执行才能挂到正确的父节点)
        collector.onLlmCallStarted();
        Prompt prompt = new Prompt(new ArrayList<>(messages), options);
        AtomicReference<ChatResponse> aggregated = new AtomicReference<>();

        // 空闲超时兜底:只罩流式段(在 concatWith 之前),工具执行期间流段已 complete 不计时。
        // 与 SubAgentToolCallback 的整流超时是两个口径:这里防上游半开连接的静默,
        // 那里防含工具静默在内的总时长。复用 ToolBudgetExceededException 是为了让
        // 子 agent 路径走 SubAgentToolCallback 既有 catch 降级为部分结果,而非炸掉父 run。
        Flux<ChatResponse> source = chatModel.stream(prompt);
        if (llmIdleTimeoutSeconds > 0)
        {
            source = source.timeout(Duration.ofSeconds(llmIdleTimeoutSeconds),
                    Flux.error(new ToolBudgetExceededException(
                            "[系统提示] 模型流超过 " + llmIdleTimeoutSeconds
                                    + " 秒无任何输出,判定连接僵死,本轮已中断。")));
        }
        // MessageAggregator:透传流式 chunk 给前端,同时在结束时给出完整 ChatResponse 供工具判定
        Flux<ChatResponse> stream = new MessageAggregator().aggregate(source, aggregated::set);

        return stream
                // chunk 消费移出 WebClient 共享的 reactor-http-nio 事件循环:emit 链路上的
                // Redis/JDBC(broker.publish、投影、collector 轮界 flush)一旦变慢,会拖累
                // 全实例所有流式连接。boundedElastic 的串行 worker 保序,且 reply/reasoning
                // 的 append 与 emit 仍在同一线程,不拆开(见 AgentToolLoopThreadingTest)。
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(chunk -> onResponse(chunk, reply, reasoning, collector, eventSink, cb,
                        iterations.get(), ownerAgentCode, spec.reasoningEnabled()))
                .concatWith(Flux.deferContextual(ctxView -> {
                    // 本轮流已结束:先把攒下的思考落一条 THINKING 行,再决定是否执行工具。
                    // 落在工具之前,message_id 顺序才与流式时间线一致(思考 → 工具 → 下一轮)。
                    flushThinking.run();
                    ChatResponse response = aggregated.get();
                    // 判定「模型是否要求调工具」直接用 response.hasToolCalls() ——
                    // 这正是官方 DefaultToolExecutionEligibilityPredicate 内部用的条件。
                    //
                    // ⚠️ 不能用 ToolExecutionEligibilityPredicate.isToolExecutionRequired():
                    // 它回答的是「ChatModel *内部* 该不该执行工具」,实现为
                    //   isInternalToolExecutionEnabled(options) && response.hasToolCalls()
                    // 我们把 internalToolExecutionEnabled 设成了 false(循环自己驱动),
                    // 第一个条件恒假 -> 整个判定永远 false -> 模型返回的 tool_calls
                    // 被当成普通回复丢掉,表现为「模型说要调工具但一次都没调」。
                    if (response == null
                            || !response.hasToolCalls()
                            || !cb.shouldContinue())
                    {
                        return Flux.empty();
                    }
                    return Flux.defer(() -> {
                        // 中间 assistant 必须在 TOOL 行之前落库:message_id 自增即时间线顺序,
                        // 顺序反了跨轮重建时 tool 会跑到 assistant 前面,直接违反协议。
                        AssistantMessage out = response.getResult().getOutput();
                        // 子智能体按无状态设计传 null conversationId,但 ai_chat_message.conversation_id
                        // 是 NOT NULL;这里兜底为 sessionId:agentId,与 TOOL/THINKING 行口径一致,
                        // 不改变子智能体「不读上下文」的语义。
                        String msgConversationId = conversationId != null
                                ? conversationId : ConversationIds.of(sessionId, agentId);
                        messageRecorder.recordAssistantToolCalls(
                                msgConversationId, sessionId, agentId, out.getText(), out.getToolCalls(),
                                runId, "tool-batch:" + iterations.get(), null);
                        ToolExecutionResult toolResult;
                        // 链路追踪:这一批 tool_calls 建一个 tool_batch span,具体工具挂它下面;
                        // 下一个 LLM 调用又以 batch 为父,树就串起来了。
                        Long batchSpanId = null;
                        if (traceRecorder != null && runId != null)
                        {
                            batchSpanId = traceRecorder.start(runId, sessionId,
                                    AiTraceSpan.TYPE_TOOL_BATCH, collector.getLastLlmSpanId(),
                                    span -> span.setAgentId(null));
                        }
                        // 把 runId + batch span 放进 Reactor 上下文:串行工具同线程可见,
                        // 并行工具由 ParallelToolCallingManager 复制到子线程。
                        // ContextView 只读,用 Context.of() 复制后 put 新键,保留原上下文。
                        //
                        // ToolCallIdMatcher 走同一条路:RecordingToolCallback 拿不到 tool_call_id,
                        // 只能由这里把本批次的 tool_calls 递进去让它自己认领。
                        Context traceCtx = Context.of(ctxView != null ? ctxView : Context.empty())
                                .put(ToolCallIdMatcher.CTX_KEY, new ToolCallIdMatcher(
                                        response.getResult().getOutput().getToolCalls()));
                        if (runId != null)
                        {
                            traceCtx = traceCtx.put(TraceSpanRecorder.CTX_RUN_ID, runId);
                            if (batchSpanId != null)
                            {
                                traceCtx = traceCtx.put(
                                        TraceSpanRecorder.CTX_PARENT_SPAN, batchSpanId);
                            }
                        }
                        long batchStartMs = System.currentTimeMillis();
                        // 子智能体嵌套时父批次 matcher 还在 ThreadLocal,清掉会让后续父工具丢 id
                        ContextView previous = ToolCallReactiveContextHolder.getContext();
                        List<Media> promptMedias = List.of();
                        try
                        {
                            if (!parallelTools && toolBatchTimeoutSeconds > 0)
                            {
                                // 串行路径官方管理器同步执行且无超时,挂死工具会永久占住 worker 线程
                                SerialToolBatchOutcome outcome = executeSerialToolCallsBounded(
                                        toolCallingManager, prompt, response, traceCtx);
                                toolResult = outcome.result();
                                promptMedias = outcome.medias();
                            }
                            else
                            {
                                ToolCallReactiveContextHolder.setContext(traceCtx);
                                try
                                {
                                    toolResult = toolCallingManager.executeToolCalls(prompt, response);
                                }
                                finally
                                {
                                    promptMedias = PromptMediaBuffer.drain();
                                }
                            }
                        }
                        finally
                        {
                            ToolCallReactiveContextHolder.setContext(previous);
                            if (traceRecorder != null && batchSpanId != null)
                            {
                                traceRecorder.finish(batchSpanId, AiTraceSpan.STATUS_SUCCEEDED,
                                        span -> span.setDurationMs(
                                                System.currentTimeMillis() - batchStartMs));
                            }
                            // 下一个 LLM 调用以这一批工具为父
                            if (runId != null)
                            {
                                collector.setParentSpanId(batchSpanId);
                            }
                        }
                        if (toolResult.returnDirect())
                        {
                            ChatResponse direct = ChatResponse.builder().from(response)
                                    .generations(ToolExecutionResult.buildGenerations(toolResult))
                                    .build();
                            onResponse(direct, reply, reasoning, collector, eventSink, cb,
                                    iterations.get(), ownerAgentCode, spec.reasoningEnabled());
                            return Flux.just(direct);
                        }
                        List<Message> next = new ArrayList<>(toolResult.conversationHistory());
                        next = appendPromptMedia(next, promptMedias, spec.inputModalities(),
                                outcome -> eventSink.emit(ChatEventJson.mediaGated(
                                        outcome.accepted(), outcome.rejected())));
                        next = applyContextClean(next, inputBudget, eventSink, conversationId);
                        next = applyOverflowGuard(next, inputBudget, eventSink);
                        if (!cb.shouldContinue())
                        {
                            return Flux.empty();
                        }
                        // 递归下一轮(仍在 boundedElastic,与官方一致)
                        return runToolLoop(spec, next, toolCallingManager, iterations);
                    }).subscribeOn(Schedulers.boundedElastic());
                }));
    }

    private List<Message> applyContextClean(List<Message> messages, int inputBudget,
                                            ChatEventSink eventSink, String conversationId)
    {
        if (contextCleaner == null || inputBudget <= 0)
        {
            return messages;
        }
        List<Message> cleaned = contextCleaner.clean(messages, inputBudget);
        ContextCleaner.CleanStats stats = contextCleaner.lastStats();
        if (stats != null && stats.pairsCleared() > 0)
        {
            log.info("轮内上下文清理: before={} after={} pairs={} freed≈{}",
                    stats.tokensBefore(), stats.tokensAfter(),
                    stats.pairsCleared(), stats.estimatedFreed());
            // 标记落库:不落的话下一轮重建又是原文,占位落点每轮漂移把缓存前缀反复打断
            if (conversationId != null)
            {
                messageRecorder.markPruned(conversationId, stats.clearedToolCallIds());
            }
            eventSink.emit(ChatEventJson.contextCleaned(
                    stats.tokensBefore(), stats.tokensAfter(), stats.pairsCleared()));
        }
        return cleaned;
    }

    /**
     * 工具批次产出的媒体挂进下一轮请求。
     *
     * <p>逐份判定而非一刀切:模型可能支持图片却不支持文档,也可能声明支持视频但传输层
     * 送不出去(见 {@link ModelInputModalities})。送得出去的挂上,送不出去的按原因归类,
     * 给模型一段说明 —— 不能静默丢弃,否则它会以为自己看过。包级可见供单测锁住分支。
     */
    static List<Message> appendPromptMedia(List<Message> messages, List<Media> medias,
                                           ModelInputModalities modalities)
    {
        return appendPromptMedia(messages, medias, modalities, null);
    }

    /**
     * 同上,并把门控结果回报给 {@code onGate}。
     *
     * <p>判定结果此前不留痕迹:不落库、不进 trace、前端看不见,想确认一张图到底进没进
     * 模型只能读模型的回答反推。回调让调用方把结果发成事件,判定从此可见。
     */
    static List<Message> appendPromptMedia(List<Message> messages, List<Media> medias,
                                           ModelInputModalities modalities,
                                           Consumer<MediaGateReport> onGate)
    {
        if (medias == null || medias.isEmpty())
        {
            return messages;
        }
        ModelInputModalities caps = modalities == null
                ? ModelInputModalities.of(null) : modalities;
        List<Media> accepted = new ArrayList<>();
        // 保序,让说明里的模态顺序稳定可测
        Map<String, Integer> rejected = new LinkedHashMap<>();
        for (Media m : medias)
        {
            if (m != null && caps.accepts(m.getMimeType()))
            {
                accepted.add(m);
            }
            else
            {
                String modality = m == null ? null : ModelInputModalities.modalityOf(m.getMimeType());
                rejected.merge(modality == null ? "unknown" : modality, 1, Integer::sum);
            }
        }

        List<Message> next = new ArrayList<>(messages);
        if (!accepted.isEmpty())
        {
            next.add(UserMessage.builder()
                    .text("以下是刚才工具产出的媒体,请查看并据此检查。")
                    .media(accepted)
                    .build());
        }
        if (!rejected.isEmpty())
        {
            next.add(new UserMessage(rejectionNotice(rejected, caps)));
        }
        if (onGate != null)
        {
            onGate.accept(MediaGateReport.of(accepted.size(), rejected, caps));
        }
        return next;
    }

    /**
     * 未送达媒体的说明文案。
     *
     * <p>必须直接否定工具返回里的原话。工具照常报告「Image attached: xxx」—— 那句话对
     * 前端是真的(附件确实送到了界面),但模型没收到内容。只含糊说「已跳过」时,模型
     * 面对两条冲突信息往往选择相信工具返回。
     */
    private static String rejectionNotice(Map<String, Integer> rejected, ModelInputModalities caps)
    {
        StringBuilder sb = new StringBuilder("[系统提示] 本轮有媒体产物未送达给你:");
        for (Map.Entry<String, Integer> e : rejected.entrySet())
        {
            String modality = e.getKey();
            sb.append("\n- ").append(e.getValue()).append(" 份")
              .append(MediaGateReport.modalityLabel(modality)).append(":")
              .append(MediaGateReport.reason(modality, caps));
        }
        sb.append("\n工具返回里出现的 \"Image attached\"、截图路径或文件路径，只表示文件已生成")
          .append("并送给了界面，你并没有看到其中的内容。不要声称自己看过，也不要据此描述。")
          .append("请改用其他方式验证：read 检查文本结构，或 bash 用 curl 抓取渲染结果。");
        return sb.toString();
    }

    /** clean 只精简可恢复的工具往返;guard 是清理无效时真正丢历史的最后防线。 */
    List<Message> applyOverflowGuard(List<Message> messages, int inputBudget,
                                     ChatEventSink eventSink)
    {
        if (overflowGuard == null)
        {
            return messages;
        }
        List<Message> guarded = overflowGuard.enforce(messages, inputBudget);
        ContextOverflowGuard.TrimStats stats = overflowGuard.lastStats();
        if (stats != null && stats.turnsDropped() > 0)
        {
            eventSink.emit(ChatEventJson.contextOverflowTrimmed(
                    stats.tokensBefore(), stats.tokensAfter(), stats.turnsDropped()));
        }
        return guarded;
    }

    private ToolCallingManager buildToolCallingManager(List<ToolCallback> tools)
    {
        List<ToolCallback> list = tools != null ? tools : List.of();
        if (parallelTools)
        {
            log.info("并行工具执行已开启 maxParallelTools={} timeoutMs={}",
                    maxParallelTools, toolExecutionTimeoutMs);
            Duration perTool = Duration.ofMillis(toolExecutionTimeoutMs);
            Duration single = toolBatchTimeoutSeconds > 0
                    ? Duration.ofSeconds(toolBatchTimeoutSeconds) : perTool;
            return new ParallelToolCallingManager(
                    new StaticToolCallbackResolver(list),
                    DefaultToolExecutionExceptionProcessor.builder().build(),
                    parallelToolTaskExecutor,
                    maxParallelTools,
                    perTool,
                    single);
        }
        return ToolCallingManager.builder()
                .observationRegistry(ObservationRegistry.NOOP)
                .toolCallbackResolver(new StaticToolCallbackResolver(list))
                .toolExecutionExceptionProcessor(
                        DefaultToolExecutionExceptionProcessor.builder().build())
                .build();
    }

    /** 串行工具批次的产出:结果 + 从执行线程回收的 prompt media(截图)。 */
    record SerialToolBatchOutcome(ToolExecutionResult result, List<Media> medias)
    {
    }

    /**
     * 串行工具批次的有界执行:提交到 parallelToolTaskExecutor 等待,超时则为本批所有
     * toolCall 合成错误 ToolResponse 让循环继续(模型看到失败自行收尾),worker 线程立即释放。
     * <p>上下文复制与媒体回收方向照抄 {@link ParallelToolCallingManager}:ReactiveContext
     * 往执行线程复制;PromptMediaBuffer 是 ThreadLocal,在执行线程 drain 后经返回值带回。
     * 超时后被放弃的执行线程自然结束,其后续事件由 run 终态闸门丢弃,无害。
     */
    SerialToolBatchOutcome executeSerialToolCallsBounded(
            ToolCallingManager manager, Prompt prompt, ChatResponse response, Context traceCtx)
    {
        CompletableFuture<SerialToolBatchOutcome> future = CompletableFuture.supplyAsync(() -> {
            ToolCallReactiveContextHolder.setContext(traceCtx);
            try
            {
                ToolExecutionResult executed = manager.executeToolCalls(prompt, response);
                return new SerialToolBatchOutcome(executed, PromptMediaBuffer.drain());
            }
            finally
            {
                // 异常路径的残留媒体丢弃(调用方已按超时/错误收尾),并清 ThreadLocal 防线程池复用串批
                PromptMediaBuffer.drain();
                ToolCallReactiveContextHolder.clearContext();
            }
        }, parallelToolTaskExecutor);
        try
        {
            return future.get(toolBatchTimeoutSeconds, TimeUnit.SECONDS);
        }
        catch (TimeoutException e)
        {
            log.warn("串行工具批次超时(>{}s),合成错误响应继续", toolBatchTimeoutSeconds);
            future.cancel(true);
            return new SerialToolBatchOutcome(timedOutSerialResult(prompt, response), List.of());
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return new SerialToolBatchOutcome(timedOutSerialResult(prompt, response), List.of());
        }
        catch (ExecutionException e)
        {
            // 官方管理器极少抛(参数断言类);保持与内联执行相同的穿透语义
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw cause instanceof RuntimeException runtimeException ? runtimeException
                    : new IllegalStateException(cause);
        }
    }

    /** 批次超时的合成结果:每个 toolCall 回填错误 ToolResponse,历史保持
     * assistant(tool_calls)+tool 协议顺序,下一轮 LLM 能看到全部失败。 */
    private static ToolExecutionResult timedOutSerialResult(Prompt prompt, ChatResponse response)
    {
        Generation generation = response.getResults().stream()
                .filter(g -> g.getOutput() != null
                        && g.getOutput().getToolCalls() != null
                        && !g.getOutput().getToolCalls().isEmpty())
                .findFirst().orElse(null);
        AssistantMessage assistant = generation != null ? generation.getOutput()
                : new AssistantMessage("");
        List<ToolResponseMessage.ToolResponse> errors = assistant.getToolCalls() != null
                ? assistant.getToolCalls().stream()
                        .map(call -> new ToolResponseMessage.ToolResponse(call.id(), call.name(),
                                "Error: Tool execution timed out; the batch was abandoned. "
                                        + "The tool may still be running in the background, do not wait for it."))
                        .toList()
                : List.of();
        ToolResponseMessage toolResponse = ToolResponseMessage.builder().responses(errors).build();
        List<Message> history = new ArrayList<>(prompt.getInstructions());
        history.add(assistant);
        history.add(toolResponse);
        return ToolExecutionResult.builder()
                .conversationHistory(history)
                .returnDirect(false)
                .build();
    }

    private void onResponse(ChatResponse response, StringBuilder reply, StringBuilder reasoningBuf,
                            LlmCallCollector collector,
                            ChatEventSink eventSink, ChatTurnCallbacks callbacks, int iteration,
                            String ownerAgentCode, boolean reasoningEnabled)
    {
        if (!callbacks.shouldContinue())
        {
            return;
        }
        if (response.getResult() != null && response.getResult().getOutput() != null)
        {
            AssistantMessage output = response.getResult().getOutput();
            String reasoning = reasoningEnabled ? ChatTurnRunner.extractReasoning(output) : null;
            if (reasoning != null && !reasoning.isEmpty())
            {
                eventSink.emit(ChatEventJson.reasoning(reasoning,
                        "reasoning:" + iteration, ownerAgentCode));
                if (reasoningBuf != null)
                {
                    reasoningBuf.append(reasoning);
                }
            }
            String text = output.getText();
            if (text != null && !text.isEmpty())
            {
                reply.append(text);
                eventSink.emit(ChatEventJson.text(text, ownerAgentCode));
            }
        }
        collector.onResponse(response);
    }
}
