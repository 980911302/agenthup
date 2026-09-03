package com.ruoyi.system.ai.run;

import java.io.Serializable;

/** Redis 与进程内总线共用的运行事件信封。 */
public class ChatRunEventEnvelope implements Serializable
{
    private static final long serialVersionUID = 1L;

    private String originInstanceId;
    private String runId;
    private String sessionId;
    private long seq;
    private String eventJson;
    /** 标准 v1 事件；eventJson 在迁移期继续服务旧客户端。 */
    private String standardEventJson;
    private long timestamp;

    public ChatRunEventEnvelope() { }

    public ChatRunEventEnvelope(String originInstanceId, String runId, String sessionId,
                                long seq, String eventJson, long timestamp)
    {
        this(originInstanceId, runId, sessionId, seq, eventJson, null, timestamp);
    }

    public ChatRunEventEnvelope(String originInstanceId, String runId, String sessionId,
                                long seq, String eventJson, String standardEventJson, long timestamp)
    {
        this.originInstanceId = originInstanceId;
        this.runId = runId;
        this.sessionId = sessionId;
        this.seq = seq;
        this.eventJson = eventJson;
        this.standardEventJson = standardEventJson;
        this.timestamp = timestamp;
    }

    public String getOriginInstanceId() { return originInstanceId; }
    public void setOriginInstanceId(String originInstanceId) { this.originInstanceId = originInstanceId; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public long getSeq() { return seq; }
    public void setSeq(long seq) { this.seq = seq; }
    public String getEventJson() { return eventJson; }
    public void setEventJson(String eventJson) { this.eventJson = eventJson; }
    public String getStandardEventJson() { return standardEventJson; }
    public void setStandardEventJson(String standardEventJson) { this.standardEventJson = standardEventJson; }
    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
}
