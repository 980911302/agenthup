package com.ruoyi.system.mapper;

import com.ruoyi.system.domain.AiChatRunStep;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** Run 可恢复步骤投影 Mapper。 */
public interface AiChatRunStepMapper
{
    int upsert(AiChatRunStep step);

    int updateOutput(@Param("runId") String runId,
                     @Param("stepId") String stepId,
                     @Param("outputData") String outputData,
                     @Param("lastEventSeq") long lastEventSeq);

    List<AiChatRunStep> selectByRunId(@Param("runId") String runId);

    int markOpenTerminal(@Param("runId") String runId,
                         @Param("status") String status,
                         @Param("lastEventSeq") long lastEventSeq);

    int bindMessageId(@Param("runId") String runId,
                      @Param("stepId") String stepId,
                      @Param("messageId") Long messageId);

    int deleteBySessionId(@Param("sessionId") String sessionId);

    int deleteByRunId(@Param("runId") String runId);

    /** status=WAITING 的 step 数。权威信号在库里,不读进程内 Broker,也不看 confirm_id。 */
    int countOpenConfirmByRunId(@Param("runId") String runId);

    /** 一批 run 里仍有 WAITING step 的 run_id。 */
    List<String> selectOpenConfirmRunIds(@Param("runIds") List<String> runIds);

    /** 最近一条 WAITING step;confirmId 仅供 UI 回传。 */
    AiChatRunStep selectLatestOpenConfirm(@Param("runId") String runId);

    void createTableForTest();

    void dropTableForTest();
}
