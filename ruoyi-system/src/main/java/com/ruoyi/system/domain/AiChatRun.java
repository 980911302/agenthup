package com.ruoyi.system.domain;

import java.io.Serializable;
import java.util.Date;

/**
 * AI 对话运行事实表 ai_chat_run。
 *
 * <p>浏览器连接只是该运行的订阅者，不拥有运行生命周期。</p>
 */
public class AiChatRun implements Serializable
{
    private static final long serialVersionUID = 1L;

    private String runId;
    private String sessionId;
    private Long agentId;
    /** 本轮实际使用的聊天模型快照；空表示沿用智能体默认模型。 */
    private Long selectedModelId;
    private String selectedModelCode;
    /** 本轮生效技能 ID JSON（默认智能体技能与 @ 技能合并后的快照）。 */
    private String effectiveSkillIds;
    /**
     * 本轮被跳过的技能 ID（已删除/已停用/无权使用）。
     *
     * <p><b>不落库</b>：没有对应表列，也不在 mapper 的 columns 里，只是从
     * {@code ChatRunService.create} 捎给接口层做提示用。重新生成一个旧轮次时，
     * 当时的技能可能已经不在了，这些 ID 用来告诉用户「少用了哪几个」。
     */
    private java.util.List<Long> skippedSkillIds;
    private Long userId;
    private String clientRequestId;
    private String activeKey;
    private String status;
    private String inputText;
    private String attachments;
    private Long requestMessageId;
    private Long responseMessageId;
    private Long lastEventSeq;
    private Long snapshotSeq;
    private String cancelRequested;
    private String workerId;
    private String errorCode;
    private String errorMessage;
    private Date startedTime;
    private Date heartbeatTime;
    private Date finishedTime;
    private Date createTime;
    private Date updateTime;

    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public Long getAgentId() { return agentId; }
    public void setAgentId(Long agentId) { this.agentId = agentId; }
    public Long getSelectedModelId() { return selectedModelId; }
    public void setSelectedModelId(Long selectedModelId) { this.selectedModelId = selectedModelId; }
    public String getSelectedModelCode() { return selectedModelCode; }
    public void setSelectedModelCode(String selectedModelCode) { this.selectedModelCode = selectedModelCode; }
    public String getEffectiveSkillIds() { return effectiveSkillIds; }
    public void setEffectiveSkillIds(String effectiveSkillIds) { this.effectiveSkillIds = effectiveSkillIds; }
    public java.util.List<Long> getSkippedSkillIds() { return skippedSkillIds; }
    public void setSkippedSkillIds(java.util.List<Long> skippedSkillIds) { this.skippedSkillIds = skippedSkillIds; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getClientRequestId() { return clientRequestId; }
    public void setClientRequestId(String clientRequestId) { this.clientRequestId = clientRequestId; }
    public String getActiveKey() { return activeKey; }
    public void setActiveKey(String activeKey) { this.activeKey = activeKey; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getInputText() { return inputText; }
    public void setInputText(String inputText) { this.inputText = inputText; }
    public String getAttachments() { return attachments; }
    public void setAttachments(String attachments) { this.attachments = attachments; }
    public Long getRequestMessageId() { return requestMessageId; }
    public void setRequestMessageId(Long requestMessageId) { this.requestMessageId = requestMessageId; }
    public Long getResponseMessageId() { return responseMessageId; }
    public void setResponseMessageId(Long responseMessageId) { this.responseMessageId = responseMessageId; }
    public Long getLastEventSeq() { return lastEventSeq; }
    public void setLastEventSeq(Long lastEventSeq) { this.lastEventSeq = lastEventSeq; }
    public Long getSnapshotSeq() { return snapshotSeq; }
    public void setSnapshotSeq(Long snapshotSeq) { this.snapshotSeq = snapshotSeq; }
    public String getCancelRequested() { return cancelRequested; }
    public void setCancelRequested(String cancelRequested) { this.cancelRequested = cancelRequested; }
    public String getWorkerId() { return workerId; }
    public void setWorkerId(String workerId) { this.workerId = workerId; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Date getStartedTime() { return startedTime; }
    public void setStartedTime(Date startedTime) { this.startedTime = startedTime; }
    public Date getHeartbeatTime() { return heartbeatTime; }
    public void setHeartbeatTime(Date heartbeatTime) { this.heartbeatTime = heartbeatTime; }
    public Date getFinishedTime() { return finishedTime; }
    public void setFinishedTime(Date finishedTime) { this.finishedTime = finishedTime; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
