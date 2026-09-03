package com.ruoyi.system.domain;

import java.util.Date;

/**
 * AI 链路追踪 span 表 ai_trace_span
 * <p>
 * 一轮对话(ai_chat_run)内的调用树节点:turn / llm / tool_batch / tool / subagent。
 * 与 metering 表的区别:ai_llm_call 记「打了几次上游接口」,本表记「谁触发了谁、
 * 每步花了多久」——父子关系靠 parent_span_id 串成树,供会话瀑布图使用。
 *
 * @author ruoyi
 */
public class AiTraceSpan
{
    public static final String TYPE_TURN = "turn";
    public static final String TYPE_LLM = "llm";
    public static final String TYPE_TOOL_BATCH = "tool_batch";
    public static final String TYPE_TOOL = "tool";
    public static final String TYPE_SUBAGENT = "subagent";

    public static final String STATUS_STARTED = "started";
    public static final String STATUS_SUCCEEDED = "succeeded";
    public static final String STATUS_FAILED = "failed";

    private Long spanId;
    private String runId;
    private String sessionId;
    private Long parentSpanId;
    private String spanType;
    private Long agentId;
    private Long subAgentId;
    private Long modelId;
    private String modelName;
    private String toolName;
    private String toolCallId;
    private Integer callSeq;
    private Integer depth;
    private String status;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private Integer cacheHitTokens;
    private Integer cacheMissTokens;
    private String usageSource;
    private Long durationMs;
    private Date startedAt;
    private Date finishedAt;
    private Date createTime;

    public Long getSpanId() { return spanId; }
    public void setSpanId(Long spanId) { this.spanId = spanId; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public Long getParentSpanId() { return parentSpanId; }
    public void setParentSpanId(Long parentSpanId) { this.parentSpanId = parentSpanId; }
    public String getSpanType() { return spanType; }
    public void setSpanType(String spanType) { this.spanType = spanType; }
    public Long getAgentId() { return agentId; }
    public void setAgentId(Long agentId) { this.agentId = agentId; }
    public Long getSubAgentId() { return subAgentId; }
    public void setSubAgentId(Long subAgentId) { this.subAgentId = subAgentId; }
    public Long getModelId() { return modelId; }
    public void setModelId(Long modelId) { this.modelId = modelId; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }
    public String getToolCallId() { return toolCallId; }
    public void setToolCallId(String toolCallId) { this.toolCallId = toolCallId; }
    public Integer getCallSeq() { return callSeq; }
    public void setCallSeq(Integer callSeq) { this.callSeq = callSeq; }
    public Integer getDepth() { return depth; }
    public void setDepth(Integer depth) { this.depth = depth; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getPromptTokens() { return promptTokens; }
    public void setPromptTokens(Integer promptTokens) { this.promptTokens = promptTokens; }
    public Integer getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(Integer completionTokens) { this.completionTokens = completionTokens; }
    public Integer getTotalTokens() { return totalTokens; }
    public void setTotalTokens(Integer totalTokens) { this.totalTokens = totalTokens; }
    public Integer getCacheHitTokens() { return cacheHitTokens; }
    public void setCacheHitTokens(Integer cacheHitTokens) { this.cacheHitTokens = cacheHitTokens; }
    public Integer getCacheMissTokens() { return cacheMissTokens; }
    public void setCacheMissTokens(Integer cacheMissTokens) { this.cacheMissTokens = cacheMissTokens; }
    public String getUsageSource() { return usageSource; }
    public void setUsageSource(String usageSource) { this.usageSource = usageSource; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public Date getStartedAt() { return startedAt; }
    public void setStartedAt(Date startedAt) { this.startedAt = startedAt; }
    public Date getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Date finishedAt) { this.finishedAt = finishedAt; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
