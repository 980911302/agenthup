package com.ruoyi.system.mapper;

import com.ruoyi.system.domain.AiChatRun;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/** AI 对话运行 Mapper。 */
public interface AiChatRunMapper
{
    int insertAiChatRun(AiChatRun run);

    AiChatRun selectAiChatRunById(@Param("runId") String runId);

    AiChatRun selectByClientRequest(@Param("userId") Long userId,
                                    @Param("clientRequestId") String clientRequestId);

    AiChatRun selectActiveBySession(@Param("sessionId") String sessionId);

    AiChatRun selectLatestBySession(@Param("sessionId") String sessionId);

    List<AiChatRun> selectByRunIds(@Param("runIds") List<String> runIds);

    int markRunning(@Param("runId") String runId, @Param("workerId") String workerId,
                    @Param("now") java.util.Date now);

    int markFinalizing(@Param("runId") String runId, @Param("workerId") String workerId,
                       @Param("now") java.util.Date now);

    int updateHeartbeat(@Param("runId") String runId, @Param("workerId") String workerId,
                        @Param("now") java.util.Date now);

    int requestCancel(@Param("runId") String runId);

    int completeRun(@Param("runId") String runId,
                    @Param("status") String status,
                    @Param("errorCode") String errorCode,
                    @Param("errorMessage") String errorMessage,
                    @Param("requestMessageId") Long requestMessageId,
                    @Param("responseMessageId") Long responseMessageId,
                    @Param("now") java.util.Date now);

    int updateLastEventSeq(@Param("runId") String runId, @Param("seq") long seq);

    /** 投影成功后同时推进发布高水位与可恢复快照高水位。 */
    int advanceSnapshotSeq(@Param("runId") String runId,
                           @Param("lastEventSeq") long lastEventSeq,
                           @Param("snapshotSeq") long snapshotSeq);

    /** USER 消息落库后立即精确绑定，运行中快照也可恢复用户输入。 */
    int bindRequestMessage(@Param("runId") String runId,
                           @Param("requestMessageId") Long requestMessageId);

    int unbindMessageIdsBySession(@Param("sessionId") String sessionId);

    /** 物理删除某会话的全部运行记录(删除会话时调用)。 */
    int deleteBySessionId(@Param("sessionId") String sessionId);

    /**
     * 心跳超时判定。heartbeat_time / create_time 都由应用写入 Java Date,
     * 与 staleBefore 同一时钟,用 coalesce 比较即可。
     */
    int markStaleInterrupted(@Param("staleBefore") java.util.Date staleBefore,
                             @Param("now") java.util.Date now);

    List<AiChatRun> selectStaleActive(@Param("staleBefore") java.util.Date staleBefore);

    int markWorkerInterrupted(@Param("workerId") String workerId,
                              @Param("now") java.util.Date now);

    List<AiChatRun> selectActiveByWorker(@Param("workerId") String workerId);

    // 测试专用：H2 建表/删表，生产运行不触发。
    void createTableForTest();

    void dropTableForTest();

    /**
     * 任务健康度(首页)。
     * @return map keys: succeeded / failed / interrupted / cancelled / queued / running / finalizing / total
     */
    Map<String, Object> selectRunHealth(@Param("since") java.util.Date since);

    /**
     * 终态任务的平均耗时(毫秒),仅 SUCCEEDED/FAILED。
     * @return map keys: avgMs / sampleCount
     */
    Map<String, Object> selectRunDuration(@Param("since") java.util.Date since);
}
