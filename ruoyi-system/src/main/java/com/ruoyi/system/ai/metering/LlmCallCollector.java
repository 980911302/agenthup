package com.ruoyi.system.ai.metering;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.openai.api.OpenAiApi;
import com.ruoyi.system.ai.memory.TokenEstimator;
import com.ruoyi.system.ai.trace.TraceSpanRecorder;
import com.ruoyi.system.domain.AiLlmCall;
import com.ruoyi.system.domain.AiTraceSpan;
import com.ruoyi.system.mapper.AiLlmCallMapper;
import com.ruoyi.system.ai.event.UiArtifactContext;
import com.ruoyi.system.ai.event.UiArtifactEmitter;
import com.ruoyi.system.tool.ToolBudget;
import com.ruoyi.system.tool.ToolBudgetExceededException;
import com.ruoyi.system.tool.ToolBudgetRegistry;
import com.ruoyi.system.tool.UiArtifact;
import com.ruoyi.system.tool.UiArtifactNames;

/**
 * 在流式订阅点消费 {@link ChatResponse}，识别「一轮调用结束」，落一行 {@code ai_llm_call}。
 *
 * <p>构造时显式传入 sessionId/agentId/conversationId/modelId/depth —— 不用 ThreadLocal。
 *
 * <p>判别逻辑：
 * <ul>
 *   <li>标准 OpenAI 流：usage 只在每轮末包出现 → 每个非空 usage 记一行</li>
 *   <li>累积式中转：每 chunk 带递增 completion → 只在流结束记最后一行</li>
 *   <li>全程无 usage → TokenEstimator 估算，usage_source='1'</li>
 * </ul>
 *
 * <p><b>工具循环差值记账</b>：Spring AI {@code UsageCalculator} 在工具续轮里给的是
 * 从本轮开始的累计 usage；落库必须记本次增量，否则 {@code sum(prompt_tokens)} 随轮数虚高。
 *
 * @author ruoyi
 */
public class LlmCallCollector
{
    private static final Logger log = LoggerFactory.getLogger(LlmCallCollector.class);

    public static final String SOURCE_REAL = "0";
    public static final String SOURCE_ESTIMATED = "1";

    private final String sessionId;
    private final Long agentId;
    private final String conversationId;
    private final Long modelId;
    private final String configModelName;
    private final int depth;
    private final AiLlmCallMapper mapper;
    private final TokenEstimator tokenEstimator;
    /** 可为 null(测试/无探针场景);有则在 flush 时按 responseId 取缓存命中数 */
    private final CacheUsageProbe cacheUsageProbe;
    /** 可为 null;有则在识别新 LLM 调用时推进工具预算的往返计数 */
    private final ToolBudgetRegistry budgetRegistry;

    private final long startMs = System.currentTimeMillis();
    private final Date turnStart = new Date(startMs);
    private long lastBoundaryMs = startMs;
    /** 本轮 LLM 流的真实起点(onLlmCallStarted 打点);0 = 调用方没打点,退回 lastBoundaryMs */
    private long llmStartedAtMs;

    private int callSeq = 0;
    private PendingCall pending;
    private final List<AiLlmCall> flushed = new ArrayList<>();

    private int sumPrompt;
    private int sumCompletion;
    private int sumTotal;
    private String lastModelName;
    private boolean anyEstimated;

    /** 链路追踪(可为 null,不追踪);非 final 以便旧构造链保持 null */
    private TraceSpanRecorder traceRecorder;
    private String runId;
    /** 下一个 LLM span 的父 span(工具循环里由 tool_batch 更新) */
    private Long parentSpanId;
    /** 最近一次 LLM span,供工具 span 挂靠 */
    private Long lastLlmSpanId;
    /** 当前这轮 LLM 调用已开启、尚未收尾的 span(流开始时建,flush 时补 tokens 收尾) */
    private Long currentLlmSpanId;

    /** 上一次落库时的累计值。Spring AI 在工具循环里给的是累计 usage(见 UsageCalculator),
     *  落库要记本次增量,否则 sum(prompt_tokens) 会随轮数指数级虚高。 */
    private int prevCumPrompt;
    private int prevCumCompletion;
    private int prevCumTotal;
    /** 本 collector 认为上游是累计式;prompt 负差值视为累计链重置(重置基准继续差值)。
     *  翻成 false 有两条证据(见 flushPending):①探针实测 raw == 当次请求真实 prompt(硬证据,
     *  自建工具循环下 Spring AI 不再累计,走的就是这条);②「prompt 涨而 completion 跌」(旧启发式,
     *  探针缺席时兜底)。只信启发式会漏判 —— completion 一路变大时它永远不触发。 */
    private boolean cumulativeUpstream = true;

    /** 可选:装了才推实时 token;不装则完全维持现有行为 */
    private UiArtifactEmitter uiEmitter;
    private UiArtifactContext uiCtx;
    private long lastEmitMs;

    public LlmCallCollector(String sessionId, Long agentId, String conversationId,
                            Long modelId, String configModelName, int depth,
                            AiLlmCallMapper mapper, TokenEstimator tokenEstimator)
    {
        this(sessionId, agentId, conversationId, modelId, configModelName, depth,
                mapper, tokenEstimator, null, null);
    }

    public LlmCallCollector(String sessionId, Long agentId, String conversationId,
                            Long modelId, String configModelName, int depth,
                            AiLlmCallMapper mapper, TokenEstimator tokenEstimator,
                            CacheUsageProbe cacheUsageProbe)
    {
        this(sessionId, agentId, conversationId, modelId, configModelName, depth,
                mapper, tokenEstimator, cacheUsageProbe, null);
    }

    public LlmCallCollector(String sessionId, Long agentId, String conversationId,
                            Long modelId, String configModelName, int depth,
                            AiLlmCallMapper mapper, TokenEstimator tokenEstimator,
                            CacheUsageProbe cacheUsageProbe, ToolBudgetRegistry budgetRegistry)
    {
        this.sessionId = sessionId;
        this.agentId = agentId;
        this.conversationId = conversationId;
        this.modelId = modelId;
        this.configModelName = configModelName;
        this.depth = depth;
        this.mapper = mapper;
        this.tokenEstimator = tokenEstimator;
        this.cacheUsageProbe = cacheUsageProbe;
        this.budgetRegistry = budgetRegistry;
        this.traceRecorder = null;
        this.runId = null;
    }

    /**
     * 带链路追踪的构造。
     *
     * @param traceRecorder span 记录器;null 表示不追踪
     * @param runId         本轮运行 id;null 表示不追踪
     * @param parentSpanId  首个 LLM span 的父 span(通常为 turn span)
     */
    public LlmCallCollector(String sessionId, Long agentId, String conversationId,
                            Long modelId, String configModelName, int depth,
                            AiLlmCallMapper mapper, TokenEstimator tokenEstimator,
                            CacheUsageProbe cacheUsageProbe, ToolBudgetRegistry budgetRegistry,
                            TraceSpanRecorder traceRecorder, String runId, Long parentSpanId)
    {
        this(sessionId, agentId, conversationId, modelId, configModelName, depth,
                mapper, tokenEstimator, cacheUsageProbe, budgetRegistry);
        this.traceRecorder = traceRecorder;
        this.runId = runId;
        this.parentSpanId = parentSpanId;
    }

    /** 可选:装了才推实时 token;不装则完全维持现有行为 */
    public void setUiEmitter(UiArtifactEmitter emitter, UiArtifactContext ctx)
    {
        this.uiEmitter = emitter;
        this.uiCtx = ctx;
    }

    /** 工具循环里更新下一个 LLM span 的父 span(通常为 tool_batch span) */
    public void setParentSpanId(Long parentSpanId)
    {
        this.parentSpanId = parentSpanId;
    }

    /**
     * 每轮 LLM 调用开始时调用:立即开一个 llm span(不等 usage),
     * 这样工具执行时 {@link #getLastLlmSpanId()} 已有值,父子关系正确。
     * flush 时再给这个 span 补 tokens 并收尾;trace 未开启时无副作用。
     */
    public void onLlmCallStarted()
    {
        // 本轮流的真实起点。必须打在 trace 守卫之前:duration 记账不依赖 trace 是否开启。
        llmStartedAtMs = System.currentTimeMillis();
        if (traceRecorder == null || runId == null || sessionId == null)
        {
            return;
        }
        // 上一轮流没带 usage 导致 span 未收尾,先收掉避免悬挂(started 永远挂着)
        if (currentLlmSpanId != null)
        {
            Long prev = currentLlmSpanId;
            currentLlmSpanId = null;
            // 已被在途 pending 认领的 span 不能在这里收:它还在等自己那轮的 usage,
            // 由 flushPending 补完 tokens 再收尾。收早了 tokens 就落不进去。
            if (pending == null || !prev.equals(pending.spanId))
            {
                traceRecorder.finish(prev, AiTraceSpan.STATUS_SUCCEEDED, null);
            }
        }
        Long spanId = traceRecorder.start(runId, sessionId, AiTraceSpan.TYPE_LLM,
                parentSpanId, span -> {
                    span.setAgentId(agentId);
                    span.setModelId(modelId);
                    span.setModelName(configModelName);
                    span.setDepth(depth);
                });
        currentLlmSpanId = spanId;
        lastLlmSpanId = spanId;
    }

    /** 最近一次 LLM span;工具执行前取它作为工具 span 的父 */
    public Long getLastLlmSpanId()
    {
        return lastLlmSpanId;
    }

    /**
     * 订阅点逐个喂入 ChatResponse。
     */
    public void onResponse(ChatResponse response)
    {
        if (response == null)
        {
            return;
        }
        ChatResponseMetadata meta = response.getMetadata();
        String modelName = meta != null ? meta.getModel() : null;
        String responseId = meta != null ? meta.getId() : null;
        String finishReason = extractFinishReason(response);
        Usage usage = meta != null ? meta.getUsage() : null;

        if (modelName != null && !modelName.isEmpty())
        {
            lastModelName = modelName;
        }

        if (!hasRealUsage(usage))
        {
            // 无 usage 的中间 chunk：若有 finish_reason 挂到 pending
            if (pending != null && finishReason != null && !finishReason.isEmpty())
            {
                pending.finishReason = finishReason;
            }
            return;
        }

        int prompt = nvl(usage.getPromptTokens());
        int completion = nvl(usage.getCompletionTokens());
        int total = nvl(usage.getTotalTokens());
        if (total <= 0)
        {
            total = prompt + completion;
        }
        int cachedTokens = extractCachedTokens(usage);

        // 关键验证日志：确认上游是否返回真实 usage
        log.info("LLM usage session={} agent={} depth={} prompt={} completion={} total={} model={} finish={}",
                sessionId, agentId, depth, prompt, completion, total,
                modelName != null ? modelName : configModelName, finishReason);

        if (pending != null)
        {
            // 同 prompt 且 completion 单调递增 → 累积式上游，只保留最后值
            // (同一次调用内流式多次下发 usage,不是跨调用累计;逻辑正确不要动)
            if (pending.promptTokens == prompt && completion >= pending.completionTokens)
            {
                pending.completionTokens = completion;
                pending.totalTokens = total > 0 ? total : prompt + completion;
                if (finishReason != null && !finishReason.isEmpty())
                {
                    pending.finishReason = finishReason;
                }
                if (modelName != null && !modelName.isEmpty())
                {
                    pending.modelName = modelName;
                }
                if (responseId != null && !responseId.isEmpty())
                {
                    pending.responseId = responseId;
                }
                applyCache(pending, prompt, cachedTokens);
                // 同一轮里 usage 可能多次下发,结束时刻取最后一次
                pending.endedAtMs = System.currentTimeMillis();
                // 同轮内 usage 刷新时也同步最新 prompt(通常不变,防御即可)
                notifyToolBudgetPromptTokens(currentRealPrompt(prompt));
                return;
            }
            // 否则是新一轮（工具续轮）：先落上一轮
            flushPending();
        }

        // 开启新一轮 pending(= 一次新的模型往返)
        callSeq++;
        notifyToolBudgetBeginRound();
        pending = new PendingCall();
        pending.callSeq = callSeq;
        pending.promptTokens = prompt;
        pending.completionTokens = completion;
        pending.totalTokens = total;
        pending.finishReason = finishReason;
        pending.modelName = modelName != null && !modelName.isEmpty() ? modelName : configModelName;
        pending.responseId = responseId;
        pending.usageSource = SOURCE_REAL;
        // usage 落在流的末包,所以「收到 usage」就是这一轮真正结束的时刻;起点取
        // onLlmCallStarted 的打点。旧实现拿 lastBoundaryMs(上一次 flush 的时刻)当起点,
        // 而 flush 是被**下一轮**的 usage 触发的 —— 于是第 N 行记的是第 N+1 轮的耗时,
        // 一轮工具循环的最后一行只剩 flush 的尾巴(线上实测 35ms)。
        pending.startedAtMs = llmStartedAtMs > 0 ? llmStartedAtMs : lastBoundaryMs;
        pending.endedAtMs = System.currentTimeMillis();
        // 认领本轮的 llm span,flush 时按这个 id 收尾(见 flushPending 尾部)
        pending.spanId = currentLlmSpanId;
        applyCache(pending, prompt, cachedTokens);
        // 喂当次真实 prompt 大小(完整上下文),供 ToolBudget 按 token 预算判定
        int realPrompt = currentRealPrompt(prompt);
        notifyToolBudgetPromptTokens(realPrompt);
        // 流内闸口:纯生成轮不触发工具调用的 acquire,判定必须下沉到流内
        checkStreamHealth(realPrompt);
    }

    /**
     * 流内闸口。预算/轮次判定原本只在工具调用的 {@code acquire()} 里做,纯生成轮
     * (或上游流损坏后的幻影轮)不调工具,所有闸口都会失效 —— 线上实测子 agent 的流
     * 进入重复发射状态后空转 30+ 轮、累计出 106 万假 token,run 活活锁死。
     *
     * <p>这里只拦两种「必然异常」:usage 流损坏(单轮读数超窗口两倍,真实请求到不了
     * 这个量级,上游会先 400)与轮次过硬顶;正常的「接近上限」仍留给工具调用时的
     * 软/硬判定处理。抛 {@link ToolBudgetExceededException}:子 agent 路径会被
     * 降级成「部分结果+说明」交回父级,父路径则让 run 以真实原因失败。
     */
    private void checkStreamHealth(int realPrompt)
    {
        if (budgetRegistry == null || sessionId == null || sessionId.isEmpty())
        {
            return;
        }
        ToolBudget budget;
        try
        {
            budget = budgetRegistry.get(sessionId);
        }
        catch (Exception e)
        {
            return;
        }
        if (budget == null)
        {
            return;
        }
        if (budget.isCorruptPrompt(agentId, realPrompt))
        {
            throw new ToolBudgetExceededException("[系统提示] 上游 usage 流损坏:单轮上下文读数 "
                    + realPrompt + " token 已超模型窗口两倍,判定为重放/累计回吐的脏数据,"
                    + "本轮运行已中止以防止预算误判与无效重试。");
        }
        if (budget.hardRoundsExceeded(agentId))
        {
            throw new ToolBudgetExceededException("[系统提示] 模型往返已过硬上限（"
                    + budget.rounds(agentId) + " 轮），本轮运行已中止。请基于已获得的信息收尾，"
                    + "剩余工作请说明后在新会话中继续。");
        }
    }

    /**
     * 把上游原始 prompt_tokens 还原成「当次调用的真实上下文大小」。
     *
     * <p><b>为什么必须还原</b>:Spring AI {@code UsageCalculator} 在工具续轮里给的是
     * 从本轮对话开始累计的 usage(见 {@link #flushPending()} 的差值记账)。落库走差值,
     * 但 ToolBudget 衡量的是「这个 agent 的窗口此刻占了多少」,语义是<b>当次值</b> ——
     * 直接喂累计值会随轮数单调暴涨,十几轮工具循环后必然越过硬顶,把上下文实际还很空的
     * agent(尤其工具循环多的子智能体)误判为「上下文接近上限」而硬阻断。
     *
     * <p>还原规则与 {@link #flushPending()} 完全同口径、共享同一份 {@code prevCum*} 状态:
     * 调用点都在 {@code flushPending()} 之后,{@code prevCumPrompt} 已是上一轮的原始累计值,
     * 差值即当次真实 prompt。首轮(prevCum=0)、非累计式上游(已翻转)、差值异常(≤0)
     * 三种情况都回退原始值,与落库兜底一致。
     */
    private int currentRealPrompt(int rawPrompt)
    {
        if (cumulativeUpstream && prevCumPrompt > 0)
        {
            int delta = rawPrompt - prevCumPrompt;
            if (delta > 0)
            {
                return delta;
            }
        }
        return rawPrompt;
    }

    /**
     * 通知工具预算：新的模型往返开始。
     * <p>rounds 按往返计而非按单次工具调用,并行工具共享同一轮配额。
     */
    private void notifyToolBudgetBeginRound()
    {
        if (budgetRegistry == null || sessionId == null || sessionId.isEmpty())
        {
            return;
        }
        try
        {
            ToolBudget budget = budgetRegistry.get(sessionId);
            if (budget != null)
            {
                budget.beginRound(agentId);
            }
        }
        catch (Exception e)
        {
            log.debug("推进工具预算轮次失败: {}", e.getMessage());
        }
    }

    /**
     * 把上游真实 prompt_tokens 喂给工具预算。
     * <p>用原始 usage 值(当次完整上下文),不是差值记账后的落库增量 ——
     * 预算要卡的是「窗口还剩多少」,不是「本轮新增了多少」。
     */
    private void notifyToolBudgetPromptTokens(int promptTokens)
    {
        if (budgetRegistry == null || sessionId == null || sessionId.isEmpty() || promptTokens <= 0)
        {
            return;
        }
        try
        {
            ToolBudget budget = budgetRegistry.get(sessionId);
            if (budget != null)
            {
                // 按 agent 记:ToolBudget 是 session 级共享的,子 agent 的上下文
                // 不占父的窗口,共用一个值会让父被子的用量误伤(见 ToolBudget#promptByAgent)
                budget.recordPromptTokens(agentId, promptTokens);
            }
        }
        catch (Exception e)
        {
            log.debug("记录工具预算 prompt_tokens 失败: {}", e.getMessage());
        }
    }

    /**
     * 取上游返回的缓存命中 token 数(兜底路径)。
     *
     * <p>读 Spring AI 已解析好的 {@code prompt_tokens_details.cached_tokens}(OpenAI 标准字段)。
     * <b>工具续轮下</b> Spring AI 会丢弃 nativeUsage,本方法恒返回 -1;
     * 主路径靠 {@link CacheUsageProbe} 在 HTTP 层抓取。
     *
     * @return 命中 token 数;上游未返回该字段时为 -1(表示"无数据",与"命中 0 个"区分)
     */
    private static int extractCachedTokens(Usage usage)
    {
        try
        {
            Object nativeUsage = usage.getNativeUsage();
            if (nativeUsage instanceof OpenAiApi.Usage ou && ou.promptTokensDetails() != null)
            {
                Integer cached = ou.promptTokensDetails().cachedTokens();
                if (cached != null)
                {
                    return Math.max(0, cached);
                }
            }
        }
        catch (Exception e)
        {
            // 观测数据缺失不能影响计费主流程
            log.debug("读取 cached_tokens 失败: {}", e.getMessage());
        }
        return -1;
    }

    /** miss 由 prompt - hit 推出,上游不需要单独返回。无数据时两者都留 0。 */
    private static void applyCache(PendingCall call, int promptTokens, int cachedTokens)
    {
        if (cachedTokens < 0)
        {
            return;
        }
        call.cacheHitTokens = cachedTokens;
        call.cacheMissTokens = Math.max(0, promptTokens - cachedTokens);
    }

    /**
     * 流结束：落库 pending / 估算兜底，返回本 collector 汇总。
     */
    public TurnUsage onComplete(String fallbackPromptText, String fallbackReplyText)
    {
        if (pending != null)
        {
            flushPending();
        }
        else if (flushed.isEmpty())
        {
            // 全程无 usage → 估算一行
            int estPrompt = tokenEstimator != null
                    ? tokenEstimator.estimate(fallbackPromptText) : safeLen(fallbackPromptText) / 3;
            int estCompletion = tokenEstimator != null
                    ? tokenEstimator.estimate(fallbackReplyText) : safeLen(fallbackReplyText) / 3;
            callSeq = 1;
            pending = new PendingCall();
            pending.callSeq = 1;
            pending.promptTokens = Math.max(estPrompt, 0);
            pending.completionTokens = Math.max(estCompletion, 0);
            pending.totalTokens = pending.promptTokens + pending.completionTokens;
            pending.modelName = lastModelName != null ? lastModelName : configModelName;
            pending.usageSource = SOURCE_ESTIMATED;
            pending.startedAtMs = startMs;
            pending.endedAtMs = System.currentTimeMillis();
            anyEstimated = true;
            flushPending();
            log.warn("LLM usage 缺失，已落估算 session={} agent={} total={}",
                    sessionId, agentId, sumTotal);
        }

        TurnUsage usage = new TurnUsage(sumPrompt, sumCompletion, sumTotal, flushed.size(),
                lastModelName != null ? lastModelName : configModelName,
                anyEstimated ? SOURCE_ESTIMATED : SOURCE_REAL,
                lastRealUsage(), turnStart);
        emitTokenUsage(true);
        return usage;
    }

    public long totalTokens()
    {
        return sumTotal;
    }

    public UsageSnapshot lastRealUsage()
    {
        if (flushed.isEmpty())
        {
            return null;
        }
        AiLlmCall last = flushed.get(flushed.size() - 1);
        if (SOURCE_ESTIMATED.equals(last.getUsageSource()))
        {
            // 若最后一轮是估算，找最后一次真实
            for (int i = flushed.size() - 1; i >= 0; i--)
            {
                AiLlmCall c = flushed.get(i);
                if (SOURCE_REAL.equals(c.getUsageSource()))
                {
                    return new UsageSnapshot(nvl(c.getPromptTokens()), nvl(c.getCompletionTokens()),
                            nvl(c.getTotalTokens()), c.getModelName(), SOURCE_REAL);
                }
            }
            return new UsageSnapshot(nvl(last.getPromptTokens()), nvl(last.getCompletionTokens()),
                    nvl(last.getTotalTokens()), last.getModelName(), SOURCE_ESTIMATED);
        }
        return new UsageSnapshot(nvl(last.getPromptTokens()), nvl(last.getCompletionTokens()),
                nvl(last.getTotalTokens()), last.getModelName(), SOURCE_REAL);
    }

    /**
     * 本 collector 已成功插入的 call_id 列表（按插入顺序）。
     * <p>用于流结束后把这些行精确绑定到最终 ASSISTANT 消息上 ——
     * 不用时间范围匹配：create_time 是秒精度，与带毫秒的 turnStart 比较会间歇性失配；
     * 且同会话并发轮次会互相误绑。
     * <p>sessionId 为空时不落库（B/C 轨旧路径），此时 callId 为 null，这里过滤掉。
     */
    public List<Long> insertedCallIds()
    {
        List<Long> ids = new ArrayList<>(flushed.size());
        for (AiLlmCall c : flushed)
        {
            if (c.getCallId() != null)
            {
                ids.add(c.getCallId());
            }
        }
        return ids;
    }

    public Date getTurnStart()
    {
        return turnStart;
    }

    public String getConversationId()
    {
        return conversationId;
    }

    private void flushPending()
    {
        if (pending == null)
        {
            return;
        }
        long now = System.currentTimeMillis();

        // 探针抓的是这一次 HTTP 请求的原始读数,hit+miss 就是上游为本次请求真实计费的
        // prompt_tokens。必须在差值记账之前取走:它是判定「上游到底累不累计」的硬证据。
        int[] cache = null;
        if (cacheUsageProbe != null && pending.responseId != null && !pending.responseId.isEmpty())
        {
            cache = cacheUsageProbe.take(pending.responseId);
        }
        int probePrompt = cache == null ? 0 : Math.max(0, cache[0]) + Math.max(0, cache[1]);
        // 非累计上游的硬判定。原来唯一的反证是「prompt 涨而 completion 跌」,可工具循环里
        // completion 常常一路变大(思考变长、工具入参变大),反证永远不触发,于是每轮都被
        // 当成累计值做差 —— 落库只剩「本轮新增」。线上实测:一个 8 次调用的会话真实 prompt
        // 79793,落库只记了 39995(少一半);同源的 currentRealPrompt 喂给 ToolBudget 的
        // 窗口占用也跟着缩水几十倍,token 预算闸门等于失效。
        // 累计式上游的 raw 必然大于当次请求的 prompt(至少多出第一轮),所以 raw 与探针读数
        // 相等只可能是非累计。首轮(prevCumPrompt==0)raw 本来就等于当次值,不带信息,跳过。
        if (cumulativeUpstream && prevCumPrompt > 0 && probePrompt > 0
                && probePrompt == pending.promptTokens)
        {
            log.info("LLM usage 判定为非累计上游(探针实测) session={} agent={} seq={} raw={} prevCum={}",
                    sessionId, agentId, pending.callSeq, pending.promptTokens, prevCumPrompt);
            cumulativeUpstream = false;
        }

        // 上游给的是累计值,落库记本次增量。负差值要区分两种语义:
        //  - prompt 跌:累计链中途重置(Spring AI 流式工具循环重开累计,实测:raw 从 85928
        //    掉回 21732 后继续累计)。本轮 raw 就是当次完整上下文,重置基准后**保持差值模式** ——
        //    若误判成"非累计上游"永久关差值,后续累计 raw 会被原样落库并喂给预算,
        //    把实际 32K 的上下文算成 203K 的幻影(线上事故)。
        //  - prompt 涨而 completion 跌:才是真·非累计上游(父级主路径每轮 completion 独立),
        //    本轮用原值并关闭差值,与旧行为一致。
        int deltaPrompt = pending.promptTokens;
        int deltaCompletion = pending.completionTokens;
        int deltaTotal = pending.totalTokens > 0
                ? pending.totalTokens
                : pending.promptTokens + pending.completionTokens;
        if (cumulativeUpstream)
        {
            deltaPrompt = pending.promptTokens - prevCumPrompt;
            deltaCompletion = pending.completionTokens - prevCumCompletion;
            deltaTotal = (pending.totalTokens > 0
                    ? pending.totalTokens
                    : pending.promptTokens + pending.completionTokens) - prevCumTotal;
            if (deltaPrompt < 0 || deltaTotal <= 0)
            {
                // 累计链重置:本轮记原值,基准在下方 prevCum* 更新处自然重置
                log.info("LLM usage 累计链重置 session={} agent={} seq={} raw={} prevCum={}",
                        sessionId, agentId, pending.callSeq, pending.promptTokens, prevCumPrompt);
                deltaPrompt = pending.promptTokens;
                deltaCompletion = pending.completionTokens;
                deltaTotal = pending.totalTokens > 0
                        ? pending.totalTokens
                        : pending.promptTokens + pending.completionTokens;
            }
            else if (deltaCompletion < 0)
            {
                deltaPrompt = pending.promptTokens;
                deltaCompletion = pending.completionTokens;
                deltaTotal = pending.totalTokens > 0
                        ? pending.totalTokens
                        : pending.promptTokens + pending.completionTokens;
                // 非累计式上游:后续也不要再做差值
                cumulativeUpstream = false;
            }
        }

        AiLlmCall row = new AiLlmCall();
        row.setSessionId(sessionId);
        row.setAgentId(agentId);
        row.setConversationId(conversationId);
        row.setModelId(modelId);
        row.setModelName(pending.modelName);
        row.setCallSeq(pending.callSeq);
        row.setDepth(depth);
        row.setFinishReason(pending.finishReason);
        row.setPromptTokens(deltaPrompt);
        row.setCompletionTokens(deltaCompletion);
        row.setTotalTokens(deltaTotal);
        row.setUsageSource(pending.usageSource);
        row.setDurationMs(Math.max(0L,
                (pending.endedAtMs > 0 ? pending.endedAtMs : now) - pending.startedAtMs));
        // take 必须用全长 responseId;truncate 只用于落库列宽
        row.setResponseId(truncate(pending.responseId, 64));
        row.setCreateTime(new Date());

        // 缓存:优先 HTTP 探针(覆盖工具续轮),兜底 nativeUsage 已解析值,都没有留 0。
        // 探针给的是该次请求的块量化绝对值,不要像 token 那样做差值;
        // 展示层用 CacheTokens.effectiveHit 与 prompt 对齐。
        int cacheHit = pending.cacheHitTokens;
        int cacheMiss = pending.cacheMissTokens;
        if (cache != null)
        {
            cacheHit = Math.max(0, cache[0]);
            cacheMiss = Math.max(0, cache[1]);
        }
        row.setCacheHitTokens(cacheHit);
        row.setCacheMissTokens(cacheMiss);

        try
        {
            if (sessionId != null && !sessionId.isEmpty())
            {
                mapper.insertLlmCall(row);
            }
        }
        catch (Exception e)
        {
            // schema 不匹配属于部署事故,不能和"偶发写库失败"用同一个日志级别 ——
            // 前者会导致整张表停止写入,必须在日志里能一眼看见。
            String msg = e.getMessage();
            if (msg != null && msg.contains("Unknown column"))
            {
                log.error("ai_llm_call 表结构与代码不匹配,计量已停止写入,请检查 DDL 是否执行: {}", msg);
            }
            else
            {
                log.warn("写入 ai_llm_call 失败 session={}: {}", sessionId, msg);
            }
        }

        // 更新累计基准必须用 pending 的原始累计值,不是差值
        if (cumulativeUpstream)
        {
            prevCumPrompt = pending.promptTokens;
            prevCumCompletion = pending.completionTokens;
            prevCumTotal = pending.totalTokens > 0
                    ? pending.totalTokens
                    : pending.promptTokens + pending.completionTokens;
        }

        sumPrompt += nvl(row.getPromptTokens());
        sumCompletion += nvl(row.getCompletionTokens());
        sumTotal += nvl(row.getTotalTokens());
        if (SOURCE_ESTIMATED.equals(row.getUsageSource()))
        {
            anyEstimated = true;
        }
        if (row.getModelName() != null)
        {
            lastModelName = row.getModelName();
        }
        final Long flushedSpanId = pending.spanId;
        final long flushedEndedAtMs = pending.endedAtMs;
        flushed.add(row);
        lastBoundaryMs = now;
        pending = null;

        // 链路追踪:llm span 在流开始时已开(onLlmCallStarted),这里只补用量并收尾;
        // 若没有未收尾的 span(极端情况)则现场开一个,保证不丢数据。
        if (traceRecorder != null && runId != null && sessionId != null)
        {
            final AiLlmCall fRow = row;
            // 认领自己那一轮的 span。直接用 currentLlmSpanId 会张冠李戴:flush 由下一轮的
            // usage 触发,那时 onLlmCallStarted 早把 currentLlmSpanId 换成下一轮的了 ——
            // 线上 trace 表里每个 llm span 都背着上一轮的 tokens、末尾还挂一个零宽幻影 span。
            Long spanId = flushedSpanId;
            if (spanId == null)
            {
                spanId = currentLlmSpanId;
                currentLlmSpanId = null;
            }
            if (spanId == null)
            {
                spanId = traceRecorder.start(runId, sessionId, AiTraceSpan.TYPE_LLM,
                        parentSpanId, span -> {
                            span.setAgentId(agentId);
                            span.setModelId(modelId);
                            span.setModelName(fRow.getModelName());
                            span.setCallSeq(fRow.getCallSeq());
                            span.setDepth(depth);
                        });
                // 现场补开的才更新父候选;正常路径 onLlmCallStarted 已经把 lastLlmSpanId
                // 指向当前那一轮了,这里再赋值会把它拨回上一轮,工具 span 就挂错父。
                lastLlmSpanId = spanId;
            }
            if (spanId != null)
            {
                traceRecorder.finish(spanId, AiTraceSpan.STATUS_SUCCEEDED, span -> {
                    span.setCallSeq(fRow.getCallSeq());
                    span.setPromptTokens(fRow.getPromptTokens());
                    span.setCompletionTokens(fRow.getCompletionTokens());
                    span.setTotalTokens(fRow.getTotalTokens());
                    span.setCacheHitTokens(fRow.getCacheHitTokens());
                    span.setCacheMissTokens(fRow.getCacheMissTokens());
                    span.setUsageSource(fRow.getUsageSource());
                    span.setDurationMs(fRow.getDurationMs());
                    // finish 默认盖当前时间,但这一轮其实早结束了(flush 被下一轮触发)
                    if (flushedEndedAtMs > 0)
                    {
                        span.setFinishedAt(new Date(flushedEndedAtMs));
                    }
                });
            }
        }
        emitTokenUsage(false);
    }

    private void emitTokenUsage(boolean force)
    {
        if (uiEmitter == null || uiCtx == null)
        {
            return;
        }
        UiArtifactNames.Spec spec = UiArtifactNames.spec(UiArtifactNames.RUN_TOKEN_USAGE);
        int interval = spec != null ? spec.minIntervalMs() : 500;
        long now = System.currentTimeMillis();
        if (!force && lastEmitMs > 0 && interval > 0 && now - lastEmitMs < interval)
        {
            return;
        }
        lastEmitMs = now;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("promptTokens", sumPrompt);
        payload.put("completionTokens", sumCompletion);
        payload.put("totalTokens", sumTotal);
        payload.put("callCount", flushed.size());
        try
        {
            uiEmitter.emit(uiCtx, new UiArtifact(UiArtifactNames.RUN_TOKEN_USAGE, 1, payload));
        }
        catch (Exception ex)
        {
            log.warn("推送实时 token 失败 session={}", sessionId, ex);
        }
    }

    private static boolean hasRealUsage(Usage usage)
    {
        if (usage == null)
        {
            return false;
        }
        int p = nvl(usage.getPromptTokens());
        int c = nvl(usage.getCompletionTokens());
        int t = nvl(usage.getTotalTokens());
        return p > 0 || c > 0 || t > 0;
    }

    private static String extractFinishReason(ChatResponse response)
    {
        Generation gen = response.getResult();
        if (gen == null)
        {
            return null;
        }
        ChatGenerationMetadata gm = gen.getMetadata();
        if (gm == null)
        {
            return null;
        }
        String fr = gm.getFinishReason();
        return fr != null && !fr.isEmpty() && !"null".equalsIgnoreCase(fr) ? fr : null;
    }

    private static int nvl(Integer v)
    {
        return v == null ? 0 : v;
    }

    private static int safeLen(String s)
    {
        return s == null ? 0 : s.length();
    }

    private static String truncate(String s, int max)
    {
        if (s == null)
        {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static final class PendingCall
    {
        int callSeq;
        int promptTokens;
        int completionTokens;
        int totalTokens;
        String finishReason;
        String modelName;
        String responseId;
        String usageSource = SOURCE_REAL;
        long startedAtMs;
        /** 收到本轮 usage 的时刻 = 这一轮流真正结束的时刻 */
        long endedAtMs;
        /** 本轮自己的 llm span,flush 时按它收尾(不能用 currentLlmSpanId,那已是下一轮的) */
        Long spanId;
        int cacheHitTokens;
        int cacheMissTokens;
    }

    /** 一轮对话（一个 collector 生命周期）的汇总 */
    public record TurnUsage(
            int promptTokens,
            int completionTokens,
            int totalTokens,
            int callCount,
            String modelName,
            String usageSource,
            UsageSnapshot lastUsage,
            Date turnStart
    )
    {
    }

    /** 最后一次 usage 快照，用于回填 ASSISTANT message.tokens */
    public record UsageSnapshot(
            int promptTokens,
            int completionTokens,
            int totalTokens,
            String modelName,
            String usageSource
    )
    {
    }
}
