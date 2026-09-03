package com.ruoyi.system.ai.run;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.system.ai.ContextFileStore;
import com.ruoyi.system.ai.session.SessionIds;
import com.ruoyi.system.domain.AiChatRunStep;
import com.ruoyi.system.mapper.AiChatRunMapper;
import com.ruoyi.system.mapper.AiChatRunStepMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把短期事件日志投影为可长期恢复的步骤快照。
 * 文本 chunk 先在内存聚合，按事件数/字数/时间形成有界检查点，并在离散事件或终态强制落库；
 * 既避免逐 token 写数据库，也避免运行中恢复需要回放无上限的 token 队列。
 */
@Service
public class ChatRunProjectionService
{
    /**
     * 流式输出也必须周期性形成可恢复快照。否则一轮长回答在结束前 snapshotSeq 一直不动，
     * 页面重进时只能回放几千条 token 事件，容易把 WebSocket 出站队列打满并进入重连循环。
     */
    static final int STREAM_CHECKPOINT_EVENTS = 128;
    static final int STREAM_CHECKPOINT_CHARS = 64 * 1024;
    static final long STREAM_CHECKPOINT_INTERVAL_MS = 1_000L;

    private final AiChatRunStepMapper stepMapper;
    private final AiChatRunMapper runMapper;
    private final ContextFileStore contextFileStore;
    private final int inlineLimit;
    private final TransactionTemplate transactionTemplate;
    private final ConcurrentHashMap<String, RunBuffer> buffers = new ConcurrentHashMap<>();

    public ChatRunProjectionService(AiChatRunStepMapper stepMapper, AiChatRunMapper runMapper)
    {
        this(stepMapper, runMapper, null, 2048);
    }

    public ChatRunProjectionService(AiChatRunStepMapper stepMapper, AiChatRunMapper runMapper,
                                    ContextFileStore contextFileStore, int inlineLimit)
    {
        this(stepMapper, runMapper, contextFileStore, inlineLimit, null);
    }

    @Autowired
    public ChatRunProjectionService(AiChatRunStepMapper stepMapper, AiChatRunMapper runMapper,
                                    @Autowired(required = false) ContextFileStore contextFileStore,
                                    @Value("${ai.chat.inline-limit:2048}") int inlineLimit,
                                    TransactionTemplate transactionTemplate)
    {
        this.stepMapper = stepMapper;
        this.runMapper = runMapper;
        this.contextFileStore = contextFileStore;
        this.inlineLimit = inlineLimit > 0 ? inlineLimit : 2048;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 事务只包住真正落库的路径:text/reasoning 未到检查点阈值时纯内存聚合,
     * 不借连接、不开事务(此前 @Transactional 让每个 chunk 白付一次 begin/commit)。
     * 缓冲并发安全不变:调用仍整体处于 broker 的 per-run 临界区内。
     */
    public boolean project(ChatRunEventEnvelope envelope)
    {
        JSONObject event = JSON.parseObject(envelope.getEventJson());
        if (event == null)
        {
            return false;
        }
        String type = event.getString("type");
        if ("text".equals(type) || "reasoning".equals(type))
        {
            RunBuffer buffer = appendChunk(envelope, event, type);
            if (!buffer.shouldCheckpoint(envelope.getTimestamp()))
            {
                return false;
            }
            long seq = envelope.getSeq();
            runInTransaction(() -> {
                flushChunks(envelope.getRunId(), envelope.getSessionId());
                runMapper.advanceSnapshotSeq(envelope.getRunId(), seq, seq);
            });
            buffer.checkpointed(envelope.getTimestamp());
            return false;
        }

        long seq = envelope.getSeq();
        runInTransaction(() -> {
            flushChunks(envelope.getRunId(), envelope.getSessionId());
            applyDiscrete(envelope, event, type);
            runMapper.advanceSnapshotSeq(envelope.getRunId(), seq, seq);
        });
        RunBuffer buffer = buffers.get(envelope.getRunId());
        if (buffer != null)
        {
            buffer.checkpointed(envelope.getTimestamp());
        }
        return isTerminal(type);
    }

    /** template 为 null(旧构造器/单测)时直跑,行为与无事务的逐条 auto-commit 一致。 */
    private void runInTransaction(Runnable action)
    {
        if (transactionTemplate != null)
        {
            transactionTemplate.executeWithoutResult(status -> action.run());
        }
        else
        {
            action.run();
        }
    }

    public void release(String runId)
    {
        buffers.remove(runId);
    }

    private RunBuffer appendChunk(ChatRunEventEnvelope envelope, JSONObject event, String type)
    {
        String owner = firstNonBlank(event.getString("parentStepId"), event.getString("owner"));
        String stepId = event.getString("stepId");
        if (stepId == null || stepId.isBlank())
        {
            stepId = "text".equals(type)
                    ? (owner == null ? "answer" : owner)
                    : "reasoning:" + (owner == null ? "main" : owner);
        }
        final String stableStepId = stepId;
        RunBuffer runBuffer = buffers.computeIfAbsent(envelope.getRunId(),
                ignored -> new RunBuffer(envelope.getTimestamp()));
        // 主回答在前端/持久化模型中统一叫 content；text 只是传输层的 chunk 事件名。
        // 若把 text 原样落库，运行中恢复会跳过这一步，只显示检查点之后的半截回答。
        String projectionType = "text".equals(type)
                ? (owner != null ? "agent" : "content") : type;
        ChunkBuffer buffer = runBuffer.chunks.computeIfAbsent(stableStepId,
                ignored -> new ChunkBuffer(stableStepId, owner, projectionType, envelope.getSeq()));
        String text = event.getString("text") == null ? "" : event.getString("text");
        buffer.output.append(text);
        buffer.lastSeq = envelope.getSeq();
        runBuffer.uncheckpointedEvents++;
        runBuffer.uncheckpointedChars += text.length();
        return runBuffer;
    }

    private void flushChunks(String runId, String sessionId)
    {
        RunBuffer runBuffer = buffers.get(runId);
        if (runBuffer == null)
        {
            return;
        }
        for (ChunkBuffer buffer : runBuffer.chunks.values())
        {
            AiChatRunStep step = base(runId, sessionId, buffer.stepId, buffer.parentStepId,
                    buffer.type, "STREAMING", buffer.firstSeq, buffer.lastSeq);
            step.setOutputData(buffer.output.toString());
            upsertStep(step);
        }
    }

    private void applyDiscrete(ChatRunEventEnvelope envelope, JSONObject event, String type)
    {
        long seq = envelope.getSeq();
        String runId = envelope.getRunId();
        String sessionId = envelope.getSessionId();
        String parent = firstNonBlank(event.getString("parentStepId"), event.getString("owner"));
        String stepId = firstNonBlank(event.getString("stepId"), event.getString("toolCallId"), event.getString("invId"));

        if ("tool_start".equals(type) || "tool_confirm_required".equals(type) || "tool_end".equals(type))
        {
            if (stepId == null) stepId = "tool:" + seq;
            String status = "tool_end".equals(type)
                    ? (Boolean.TRUE.equals(event.getBoolean("ok")) ? "SUCCEEDED" : "FAILED")
                    : ("tool_confirm_required".equals(type) ? "WAITING" : "RUNNING");
            AiChatRunStep step = base(runId, sessionId, stepId, parent, "tool", status, seq, seq);
            step.setName(event.getString("name"));
            step.setSource(event.getString("source"));
            step.setConfirmId(event.getString("confirmId"));
            step.setInputData(event.getString("args"));
            step.setOutputData(event.getString("result"));
            step.setAttachments(jsonString(event.get("attachments")));
            step.setDurationMs(event.getLong("ms"));
            if ("tool_end".equals(type))
            {
                step.setSuccess(Boolean.TRUE.equals(event.getBoolean("ok")) ? "0" : "1");
                step.setFinishedTime(new Date());
            }
            else step.setStartedTime(new Date());
            upsertStep(step);
            return;
        }

        if ("agent_start".equals(type) || "agent_end".equals(type))
        {
            if (stepId == null) stepId = "agent:" + seq;
            String status = "agent_end".equals(type)
                    ? (Boolean.TRUE.equals(event.getBoolean("ok")) ? "SUCCEEDED" : "FAILED") : "RUNNING";
            AiChatRunStep step = base(runId, sessionId, stepId, parent, "agent", status, seq, seq);
            step.setName(event.getString("name"));
            step.setSource(event.getString("agentCode"));
            step.setOutputData(event.getString("result"));
            step.setDurationMs(event.getLong("ms"));
            if ("agent_end".equals(type))
            {
                step.setSuccess(Boolean.TRUE.equals(event.getBoolean("ok")) ? "0" : "1");
                step.setFinishedTime(new Date());
            }
            else step.setStartedTime(new Date());
            upsertStep(step);
            return;
        }

        if (type != null && type.startsWith("context_"))
        {
            AiChatRunStep step = base(runId, sessionId, "context:" + seq, parent,
                    "context", "SUCCEEDED", seq, seq);
            step.setOutputData(event.toJSONString());
            step.setFinishedTime(new Date());
            upsertStep(step);
            return;
        }

        if ("ui".equals(type))
        {
            return;
        }

        if (isTerminal(type))
        {
            String status = "done".equals(type) ? "SUCCEEDED"
                    : ("cancelled".equals(type) ? "CANCELLED" : "FAILED");
            stepMapper.markOpenTerminal(runId, status, seq);
        }
    }

    /**
     * 构造一行步骤投影。这里是本类唯一构造 {@link AiChatRunStep} 的地方,
     * 「节点不能是自己的父」这条树不变量就落在这。
     *
     * <p>事件里的 owner 表示「这段输出归属哪个步骤」,子智能体自己的 chunk 归属的就是它本身,
     * 而两处取父都会回退到 owner({@code firstNonBlank(parentStepId, owner)}),
     * 于是自指会从 owner 这条路重新钻进来 —— 光在事件构造侧挡不住,必须在落库侧再收一次口。
     *
     * <p>置 null 而非保留:upsert 用 {@code coalesce(values(parent_step_id), parent_step_id)}
     * 更新该列,null 表示「本次不改动」,agent_start 写好的真实父(嵌套子智能体的上一层)
     * 不会被自己的输出 chunk 覆盖掉。
     */
    private static AiChatRunStep base(String runId, String sessionId, String stepId,
                                      String parentStepId, String type, String status,
                                      long sortNo, long lastSeq)
    {
        AiChatRunStep step = new AiChatRunStep();
        step.setRunId(runId);
        step.setSessionId(sessionId);
        step.setStepId(stepId);
        step.setParentStepId(parentStepId != null && parentStepId.equals(stepId) ? null : parentStepId);
        step.setStepType(type);
        step.setStatus(status);
        step.setSortNo(sortNo);
        step.setLastEventSeq(lastSeq);
        return step;
    }

    private static boolean isTerminal(String type)
    {
        return "done".equals(type) || "error".equals(type)
                || "cancelled".equals(type) || "interrupted".equals(type);
    }

    private static String firstNonBlank(String... values)
    {
        for (String value : values)
        {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    /**
     * 超长 output_data 溢出到文件后再 upsert。
     * 快照恢复只读预览,不回读文件。
     */
    private void upsertStep(AiChatRunStep step)
    {
        String data = step.getOutputData();
        // 只溢出 tool：content/reasoning 是断线恢复的正文，截断会让现场只剩预览。
        if ("tool".equals(step.getStepType())
                && data != null && data.length() > inlineLimit && contextFileStore != null
                && SessionIds.isWellFormed(step.getSessionId()))
        {
            String path = contextFileStore.saveRunStepOutput(step.getSessionId(), step.getStepId(), data);
            if (path != null)
            {
                step.setOutputDataPath(path);
                step.setOutputData(data.substring(0, inlineLimit));
            }
        }
        stepMapper.upsert(step);
    }

    private static String jsonString(Object value)
    {
        if (value == null) return null;
        return value instanceof String text ? text : JSON.toJSONString(value);
    }

    private static final class ChunkBuffer
    {
        private final String stepId;
        private final String parentStepId;
        private final String type;
        private final long firstSeq;
        private final StringBuilder output = new StringBuilder();
        private long lastSeq;

        private ChunkBuffer(String stepId, String parentStepId, String type, long firstSeq)
        {
            this.stepId = stepId;
            this.parentStepId = parentStepId;
            this.type = type;
            this.firstSeq = firstSeq;
            this.lastSeq = firstSeq;
        }
    }

    private static final class RunBuffer
    {
        private final LinkedHashMap<String, ChunkBuffer> chunks = new LinkedHashMap<>();
        private long lastCheckpointAt;
        private int uncheckpointedEvents;
        private int uncheckpointedChars;

        private RunBuffer(long firstEventAt)
        {
            this.lastCheckpointAt = firstEventAt;
        }

        private boolean shouldCheckpoint(long eventAt)
        {
            return uncheckpointedEvents >= STREAM_CHECKPOINT_EVENTS
                    || uncheckpointedChars >= STREAM_CHECKPOINT_CHARS
                    || eventAt - lastCheckpointAt >= STREAM_CHECKPOINT_INTERVAL_MS;
        }

        private void checkpointed(long eventAt)
        {
            lastCheckpointAt = Math.max(lastCheckpointAt, eventAt);
            uncheckpointedEvents = 0;
            uncheckpointedChars = 0;
        }
    }
}
