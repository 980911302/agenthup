package com.ruoyi.system.domain;

import java.io.Serializable;
import java.util.Date;

/** 一次 Run 的可恢复执行步骤投影。 */
public class AiChatRunStep implements Serializable
{
    private static final long serialVersionUID = 1L;

    private Long id;
    private String runId;
    private String sessionId;
    private String stepId;
    private String parentStepId;
    private String stepType;
    private String status;
    private Long sortNo;
    private String name;
    private String source;
    private String confirmId;
    private String inputData;
    private String outputData;
    private String outputDataPath;
    private String attachments;
    private String success;
    private Long durationMs;
    private Long lastEventSeq;
    private Long messageId;
    private Date startedTime;
    private Date finishedTime;
    private Date createTime;
    private Date updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getStepId() { return stepId; }
    public void setStepId(String stepId) { this.stepId = stepId; }
    public String getParentStepId() { return parentStepId; }
    public void setParentStepId(String parentStepId) { this.parentStepId = parentStepId; }
    public String getStepType() { return stepType; }
    public void setStepType(String stepType) { this.stepType = stepType; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getSortNo() { return sortNo; }
    public void setSortNo(Long sortNo) { this.sortNo = sortNo; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getConfirmId() { return confirmId; }
    public void setConfirmId(String confirmId) { this.confirmId = confirmId; }
    public String getInputData() { return inputData; }
    public void setInputData(String inputData) { this.inputData = inputData; }
    public String getOutputData() { return outputData; }
    public void setOutputData(String outputData) { this.outputData = outputData; }
    public String getOutputDataPath() { return outputDataPath; }
    public void setOutputDataPath(String outputDataPath) { this.outputDataPath = outputDataPath; }
    public String getAttachments() { return attachments; }
    public void setAttachments(String attachments) { this.attachments = attachments; }
    public String getSuccess() { return success; }
    public void setSuccess(String success) { this.success = success; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public Long getLastEventSeq() { return lastEventSeq; }
    public void setLastEventSeq(Long lastEventSeq) { this.lastEventSeq = lastEventSeq; }
    public Long getMessageId() { return messageId; }
    public void setMessageId(Long messageId) { this.messageId = messageId; }
    public Date getStartedTime() { return startedTime; }
    public void setStartedTime(Date startedTime) { this.startedTime = startedTime; }
    public Date getFinishedTime() { return finishedTime; }
    public void setFinishedTime(Date finishedTime) { this.finishedTime = finishedTime; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
