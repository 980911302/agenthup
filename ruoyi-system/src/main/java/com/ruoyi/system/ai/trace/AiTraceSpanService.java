package com.ruoyi.system.ai.trace;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.ruoyi.system.domain.AiTraceSpan;
import com.ruoyi.system.mapper.AiTraceSpanMapper;

/**
 * AI 链路追踪查询服务。
 *
 * <p>只读聚合,不参与埋点写入。对外提供:
 * <ul>
 *   <li>{@link #runTrace(String)}:一轮对话(run)的完整调用树(扁平 spans + 根信息,前端按 parentSpanId 组树)</li>
 *   <li>{@link #sessionOverview(String, int)}:会话内所有 run 的聚合概览(llm/工具/子agent 次数、token)</li>
 * </ul>
 *
 * @author ruoyi
 */
@Service
public class AiTraceSpanService
{
    private static final Logger log = LoggerFactory.getLogger(AiTraceSpanService.class);

    private final AiTraceSpanMapper mapper;

    public AiTraceSpanService(AiTraceSpanMapper mapper)
    {
        this.mapper = mapper;
    }

    /**
     * 一轮对话的调用树。
     *
     * @return { runId, root(可能为 null), spans } ;无数据时 spans 为空列表
     */
    public Map<String, Object> runTrace(String runId)
    {
        Map<String, Object> out = new HashMap<>();
        out.put("runId", runId);
        List<AiTraceSpan> spans;
        try
        {
            spans = mapper.selectByRunId(runId);
        }
        catch (Exception e)
        {
            log.warn("读取链路追踪失败 run={}: {}", runId, e.getMessage());
            spans = List.of();
        }
        AiTraceSpan root = spans.stream()
                .filter(s -> AiTraceSpan.TYPE_TURN.equals(s.getSpanType()))
                .findFirst().orElse(null);
        out.put("root", root);
        out.put("spans", spans);
        return out;
    }

    /** 会话内所有 run 的聚合概览(最新在前)。查询失败返回空列表,不影响会话页。 */
    public List<Map<String, Object>> sessionOverview(String sessionId, int limit)
    {
        try
        {
            return mapper.selectRunSummaryBySession(sessionId, Math.min(Math.max(limit, 1), 100));
        }
        catch (Exception e)
        {
            log.warn("读取会话链路追踪概览失败 session={}: {}", sessionId, e.getMessage());
            return List.of();
        }
    }
}
