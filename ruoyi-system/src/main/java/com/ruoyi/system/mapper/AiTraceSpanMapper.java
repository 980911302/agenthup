package com.ruoyi.system.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.system.domain.AiTraceSpan;

/**
 * AI 链路追踪 span 持久化。
 *
 * @author ruoyi
 */
public interface AiTraceSpanMapper
{
    /** 插入 span,回填自增 id */
    int insertSpan(AiTraceSpan span);

    /** 按 id 更新终态(状态/耗时/结束时间/用量) */
    int updateFinish(AiTraceSpan span);

    /** 取一轮对话的全部 span(按开始时间升序) */
    List<AiTraceSpan> selectByRunId(@Param("runId") String runId);

    /** 取一个会话内已落库 span 的轮次概览(run 维度,最新在前) */
    List<AiTraceSpan> selectTurnOverview(@Param("sessionId") String sessionId,
                                         @Param("limit") int limit);

    /** 会话内每个 run 的聚合统计(llm/工具/子agent 次数、token 总量) */
    List<java.util.Map<String, Object>> selectRunSummaryBySession(
            @Param("sessionId") String sessionId, @Param("limit") int limit);
}
