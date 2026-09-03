package com.ruoyi.system.ai.trace;

import java.util.Date;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.ruoyi.system.domain.AiTraceSpan;
import com.ruoyi.system.mapper.AiTraceSpanMapper;

/**
 * AI 链路追踪 span 记录器(轻量埋点,失败全降级)。
 *
 * <p>一轮对话(run)内,把 LLM / 工具 / 子 agent 的调用按父子关系记成
 * {@code ai_trace_span} 树,供会话瀑布图使用。与 metering 的职责区分:
 * {@code ai_llm_call} 记「打了多少次上游接口」,span 记「谁触发谁、每步耗时」。
 *
 * <p>埋点策略:
 * <ul>
 *   <li>不引入 OpenTelemetry,表结构就是协议,查询即聚合</li>
 *   <li>任何异常都吞掉只打 debug——追踪不能影响对话主流程</li>
 *   <li>并行工具线程通过 Reactor {@code ContextView}({@link #CTX_RUN_ID}/{@link #CTX_PARENT_SPAN})
 *       传递父 span,配合 Spring AI 的 {@code ToolCallReactiveContextHolder} 复制语义,线程安全</li>
 * </ul>
 *
 * @author ruoyi
 */
@Component
public class TraceSpanRecorder
{
    private static final Logger log = LoggerFactory.getLogger(TraceSpanRecorder.class);

    /** Reactor Context 里传递当前 runId 的键 */
    public static final String CTX_RUN_ID = "ai.trace.runId";
    /** Reactor Context 里传递当前工具父 span 的键 */
    public static final String CTX_PARENT_SPAN = "ai.trace.parentSpanId";

    private final AiTraceSpanMapper mapper;
    /** spanId -> 开始时间戳,finish 时若调用方没给 duration 就自动补 */
    private final ConcurrentHashMap<Long, Long> startMs = new ConcurrentHashMap<>();

    public TraceSpanRecorder(AiTraceSpanMapper mapper)
    {
        this.mapper = mapper;
    }

    /**
     * 开启一个 span。
     *
     * @return spanId;不可用(参数缺失/落库失败)时返回 null,调用方跳过 finish
     */
    public Long start(String runId, String sessionId, String spanType, Long parentSpanId,
                      Consumer<AiTraceSpan> fill)
    {
        if (runId == null || runId.isEmpty() || sessionId == null || spanType == null)
        {
            return null;
        }
        try
        {
            AiTraceSpan span = new AiTraceSpan();
            span.setRunId(runId);
            span.setSessionId(sessionId);
            span.setParentSpanId(parentSpanId);
            span.setSpanType(spanType);
            span.setStatus(AiTraceSpan.STATUS_STARTED);
            span.setDepth(0);
            span.setStartedAt(new Date());
            span.setCreateTime(new Date());
            if (fill != null)
            {
                fill.accept(span);
            }
            mapper.insertSpan(span);
            startMs.put(span.getSpanId(), System.currentTimeMillis());
            return span.getSpanId();
        }
        catch (Exception e)
        {
            log.debug("trace span 开启失败(降级): type={} err={}", spanType, e.getMessage());
            return null;
        }
    }

    /** 结束一个 span。spanId 为 null(未开启成功)时直接跳过。 */
    public void finish(Long spanId, String status, Consumer<AiTraceSpan> fill)
    {
        if (spanId == null)
        {
            return;
        }
        try
        {
            AiTraceSpan span = new AiTraceSpan();
            span.setSpanId(spanId);
            span.setStatus(status);
            span.setFinishedAt(new Date());
            if (fill != null)
            {
                fill.accept(span);
            }
            // 调用方没给耗时(常见于不需要关心时长的 span)就按记录的开始时间自动补
            if (span.getDurationMs() == null)
            {
                Long started = startMs.remove(spanId);
                if (started != null)
                {
                    span.setDurationMs(Math.max(0L, System.currentTimeMillis() - started));
                }
            }
            mapper.updateFinish(span);
        }
        catch (Exception e)
        {
            log.debug("trace span 结束失败(降级): span={} err={}", spanId, e.getMessage());
        }
    }
}
