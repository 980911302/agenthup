package com.ruoyi.system.ai.run;

import com.ruoyi.system.domain.AiChatRunStep;
import com.ruoyi.system.mapper.AiChatRunMapper;
import com.ruoyi.system.mapper.AiChatRunStepMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class ChatRunProjectionServiceTest
{
    @Test
    void chunksAreBufferedAndFlushedAtomicallyAtTerminal()
    {
        AiChatRunStepMapper steps = mock(AiChatRunStepMapper.class);
        AiChatRunMapper runs = mock(AiChatRunMapper.class);
        ChatRunProjectionService service = new ChatRunProjectionService(steps, runs);

        service.project(event(1, "{\"type\":\"text\",\"stepId\":\"answer\",\"text\":\"你\"}"));
        service.project(event(2, "{\"type\":\"text\",\"stepId\":\"answer\",\"text\":\"好\"}"));

        verify(steps, never()).upsert(org.mockito.ArgumentMatchers.any());
        verify(runs, never()).advanceSnapshotSeq(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());

        service.project(event(3, "{\"type\":\"done\"}"));

        ArgumentCaptor<AiChatRunStep> captor = ArgumentCaptor.forClass(AiChatRunStep.class);
        verify(steps).upsert(captor.capture());
        assertThat(captor.getValue().getStepType()).isEqualTo("content");
        assertThat(captor.getValue().getStepId()).isEqualTo("answer");
        assertThat(captor.getValue().getOutputData()).isEqualTo("你好");
        verify(runs).advanceSnapshotSeq("run-1", 3, 3);
    }

    @Test
    void subAgentTextUpdatesItsAgentStepInsteadOfChangingStepType()
    {
        AiChatRunStepMapper steps = mock(AiChatRunStepMapper.class);
        AiChatRunMapper runs = mock(AiChatRunMapper.class);
        ChatRunProjectionService service = new ChatRunProjectionService(steps, runs);

        service.project(event(1, "{\"type\":\"agent_start\",\"stepId\":\"call-7\",\"invId\":\"call-7\",\"name\":\"研究员\"}"));
        service.project(event(2, "{\"type\":\"text\",\"stepId\":\"call-7\",\"parentStepId\":\"call-7\",\"text\":\"结果\"}"));
        service.project(event(3, "{\"type\":\"agent_end\",\"stepId\":\"call-7\",\"invId\":\"call-7\",\"name\":\"研究员\",\"result\":\"结果\",\"ok\":true}"));

        ArgumentCaptor<AiChatRunStep> captor = ArgumentCaptor.forClass(AiChatRunStep.class);
        verify(steps, org.mockito.Mockito.times(3)).upsert(captor.capture());
        assertThat(captor.getAllValues()).allMatch(step -> "agent".equals(step.getStepType()));
        assertThat(captor.getAllValues().get(1).getOutputData()).isEqualTo("结果");
        // 节点不能是自己的父:自指会让恢复时按父指针建树的该子树整体成环丢失。
        // 落 null 而非保留,upsert 的 coalesce 才不会覆盖 agent_start 写好的真实父。
        assertThat(captor.getAllValues())
                .noneMatch(step -> step.getStepId() != null
                        && step.getStepId().equals(step.getParentStepId()));
    }

    /** 嵌套子智能体:内层自己的输出 chunk 不能把 agent_start 写好的外层父指针冲掉。 */
    @Test
    void nestedSubAgentTextKeepsOuterParentInsteadOfClearingIt()
    {
        AiChatRunStepMapper steps = mock(AiChatRunStepMapper.class);
        AiChatRunMapper runs = mock(AiChatRunMapper.class);
        ChatRunProjectionService service = new ChatRunProjectionService(steps, runs);

        service.project(event(1, "{\"type\":\"agent_start\",\"stepId\":\"inner-9\",\"invId\":\"inner-9\","
                + "\"owner\":\"outer-3\",\"parentStepId\":\"outer-3\",\"name\":\"内层\"}"));
        service.project(event(2, "{\"type\":\"text\",\"owner\":\"inner-9\",\"stepId\":\"inner-9\","
                + "\"parentStepId\":\"inner-9\",\"text\":\"内层结果\"}"));
        service.project(event(3, "{\"type\":\"done\"}"));

        ArgumentCaptor<AiChatRunStep> captor = ArgumentCaptor.forClass(AiChatRunStep.class);
        verify(steps, org.mockito.Mockito.atLeastOnce()).upsert(captor.capture());
        AiChatRunStep agentStart = captor.getAllValues().get(0);
        assertThat(agentStart.getParentStepId()).isEqualTo("outer-3");
        AiChatRunStep textFlush = captor.getAllValues().stream()
                .filter(s -> "内层结果".equals(s.getOutputData()))
                .findFirst().orElseThrow();
        // null = 本次不改动该列,外层父由 agent_start 那行保留
        assertThat(textFlush.getParentStepId()).isNull();
    }

    @Test
    void longStreamingAnswerCreatesBoundedRecoveryCheckpoints()
    {
        AiChatRunStepMapper steps = mock(AiChatRunStepMapper.class);
        AiChatRunMapper runs = mock(AiChatRunMapper.class);
        ChatRunProjectionService service = new ChatRunProjectionService(steps, runs);

        for (int seq = 1; seq <= ChatRunProjectionService.STREAM_CHECKPOINT_EVENTS; seq++)
        {
            service.project(event(seq,
                    "{\"type\":\"text\",\"stepId\":\"answer\",\"text\":\"字\"}"));
        }

        ArgumentCaptor<AiChatRunStep> captor = ArgumentCaptor.forClass(AiChatRunStep.class);
        verify(steps).upsert(captor.capture());
        assertThat(captor.getValue().getOutputData())
                .hasSize(ChatRunProjectionService.STREAM_CHECKPOINT_EVENTS);
        verify(runs).advanceSnapshotSeq("run-1",
                ChatRunProjectionService.STREAM_CHECKPOINT_EVENTS,
                ChatRunProjectionService.STREAM_CHECKPOINT_EVENTS);

        service.project(event(ChatRunProjectionService.STREAM_CHECKPOINT_EVENTS + 1,
                "{\"type\":\"text\",\"stepId\":\"answer\",\"text\":\"尾\"}"));
        verify(steps, times(1)).upsert(org.mockito.ArgumentMatchers.any());
    }

    /**
     * ui 事件不再投影进 run_step,避免特殊事件塞进时间线主瓶颈表。
     */
    @Test
    void uiEventIsNotProjectedAsRunStep()
    {
        AiChatRunStepMapper steps = mock(AiChatRunStepMapper.class);
        AiChatRunMapper runs = mock(AiChatRunMapper.class);
        ChatRunProjectionService service = new ChatRunProjectionService(steps, runs);

        service.project(event(1, "{\"type\":\"tool_end\",\"stepId\":\"call-9\",\"name\":\"searchKnowledge\","
                + "\"source\":\"builtin\",\"result\":\"[1]\",\"ok\":true,\"ms\":12}"));
        service.project(event(2, "{\"type\":\"ui\",\"name\":\"kb.references\",\"schemaVersion\":1,"
                + "\"eventId\":\"call-9:kb.references\",\"stepId\":\"call-9:kb.references\","
                + "\"parentStepId\":\"call-9\",\"payload\":{\"query\":\"请假\"}}"));

        ArgumentCaptor<AiChatRunStep> captor = ArgumentCaptor.forClass(AiChatRunStep.class);
        verify(steps, times(1)).upsert(captor.capture());
        AiChatRunStep tool = captor.getValue();
        assertThat(tool.getStepType()).isEqualTo("tool");
        assertThat(tool.getStepId()).isEqualTo("call-9");
        assertThat(tool.getName()).isEqualTo("searchKnowledge");
    }

    /** 热路径事务瘦身:text chunk 未到检查点阈值时零事务零 SQL;离散事件恰好一次事务。 */
    @Test
    void textChunksBelowThreshold_openNoTransaction()
    {
        AiChatRunStepMapper steps = mock(AiChatRunStepMapper.class);
        AiChatRunMapper runs = mock(AiChatRunMapper.class);
        int[] txOpened = {0};
        org.springframework.transaction.support.TransactionTemplate tt =
                new org.springframework.transaction.support.TransactionTemplate()
                {
                    @Override
                    public void executeWithoutResult(
                            java.util.function.Consumer<org.springframework.transaction.TransactionStatus> action)
                    {
                        txOpened[0]++;
                        action.accept(null);
                    }
                };
        ChatRunProjectionService service = new ChatRunProjectionService(steps, runs, null, 2048, tt);

        service.project(event(1, "{\"type\":\"text\",\"stepId\":\"answer\",\"text\":\"你\"}"));
        service.project(event(2, "{\"type\":\"text\",\"stepId\":\"answer\",\"text\":\"好\"}"));

        assertThat(txOpened[0]).isZero();
        verify(steps, never()).upsert(org.mockito.ArgumentMatchers.any());
        verify(runs, never()).advanceSnapshotSeq(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());

        service.project(event(3, "{\"type\":\"done\"}"));
        assertThat(txOpened[0]).isEqualTo(1);
    }

    private static ChatRunEventEnvelope event(long seq, String json)
    {
        return new ChatRunEventEnvelope("node-1", "run-1", "session-1", seq, json, seq);
    }
}
