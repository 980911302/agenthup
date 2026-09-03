package com.ruoyi.system.domain;

import java.io.Serializable;
import java.util.Date;

/** 会话特殊事件(UI 产物),生命周期跟会话不跟 run。 */
public class AiChatSpecialEvent implements Serializable
{
    private static final long serialVersionUID = 1L;

    private Long id;
    private String sessionId;
    private String runId;
    private Long messageId;
    private Long agentId;
    private String ownerAgentCode;
    private String name;
    private Integer schemaVersion;
    private String eventId;
    private String payload;
    private Integer version;
    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public Long getMessageId() { return messageId; }
    public void setMessageId(Long messageId) { this.messageId = messageId; }
    public Long getAgentId() { return agentId; }
    public void setAgentId(Long agentId) { this.agentId = agentId; }
    public String getOwnerAgentCode() { return ownerAgentCode; }
    public void setOwnerAgentCode(String ownerAgentCode) { this.ownerAgentCode = ownerAgentCode; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Integer getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(Integer schemaVersion) { this.schemaVersion = schemaVersion; }
    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
