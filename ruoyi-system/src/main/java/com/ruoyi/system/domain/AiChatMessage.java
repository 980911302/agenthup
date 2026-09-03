package com.ruoyi.system.domain;

import java.util.Date;

/**
 * 对话消息表 ai_chat_message
 *
 * <p>单表双视图(§4.1):同一份数据两种读法——
 * <ul>
 *   <li>给 LLM 看:{@code where visible_to_llm = '0'}(再叠加压缩边界)</li>
 *   <li>给前端看:全量 {@code order by message_id}</li>
 * </ul>
 * {@code visible_to_llm} 是「本质属性」,写入即定、永不变更(§4.4)。
 *
 * @author ruoyi
 */
public class AiChatMessage
{
    private static final long serialVersionUID = 1L;

    /** 消息ID(自增,同时作为会话内顺序) */
    private Long messageId;

    /** 会话ID(关联 ai_chat_session) */
    private String sessionId;

    /** 归属运行ID；SUMMARY 等会话级派生消息可为空 */
    private String runId;

    /** 业务语义：USER_INPUT/ASSISTANT_TOOL_CALL/TOOL_RESULT/ASSISTANT_FINAL/REASONING/SUMMARY */
    private String messageKind;

    /** 对应 Run 步骤的稳定 ID */
    private String stepId;

    /** 父步骤 ID；用于子智能体内部步骤嵌套 */
    private String parentStepId;

    /** 产生该消息的智能体ID(旧路径未绑定 agent 时为空) */
    private Long agentId;

    /** LLM记忆键(= sessionId:agentId) */
    private String conversationId;

    /** 被调用的子智能体ID(agent-as-tool 时,配合 toolSource=agent) */
    private Long subAgentId;

    /** 消息类型:USER/ASSISTANT/SYSTEM/TOOL/THINKING/SUMMARY */
    private String messageType;

    /** 消息正文(SUMMARY 行存摘要文本) */
    private String content;

    /** 是否参与LLM上下文(0是 1否),本质属性,写入即定 */
    private String visibleToLlm;

    /** SUMMARY 行专用:本摘要覆盖了 message_id <= 此值的消息 */
    private Long summaryToId;

    /** 富媒体附件[{type,url,name,size}],图片等只存URL不存base64 */
    private String attachments;

    /** ASSISTANT行专用:本轮tool_calls的JSON数组[{id,type,name,arguments}] */
    private String toolCalls;

    /** TOOL 消息回指的调用ID */
    private String toolCallId;

    /** 工具名(子智能体调用时为 agentCode) */
    private String toolName;

    /** 工具入参 */
    private String toolArgs;

    /** 工具返回(超过内联上限时截断,全文见 toolResultPath) */
    private String toolResult;

    /** 非表字段:timeline 预览时的原文长度,供前端判断是否展开 */
    private Integer toolResultLength;

    /** 非表字段:timeline 预览时的入参原文长度 */
    private Integer toolArgsLength;

    /** 非表字段：是否还有可按需读取的完整工具结果。 */
    private Boolean hasFullToolResult;

    /** 工具返回超过内联上限(默认2KB)时的文件路径 */
    private String toolResultPath;

    /** 工具来源(builtin/mcp/agent) */
    private String toolSource;

    /** 工具执行耗时(毫秒) */
    private Long toolDurationMs;

    /** 工具是否成功(0成功 1失败) */
    private String toolSuccess;

    /** 是否已被上下文清理(0否 1是);为1时装配给LLM出占位文本,原文保留供审计 */
    private String pruned;

    /**
     * 该消息的 token 数(统一为 TokenEstimator 估算值,供上下文环图占比/预算)。
     * <p>真实用量在 ai_llm_call / ai_chat_session.total_tokens,不写本列。
     */
    private Integer tokens;

    /** 该消息对应的输入 token 归因(仅 ASSISTANT 有值,来自上游或估算) */
    private Integer promptTokens;

    /** 该消息对应的输出 token 归因(仅 ASSISTANT 有值,来自上游或估算) */
    private Integer completionTokens;

    /** 产生该消息的模型名 */
    private String modelName;

    /**
     * prompt_tokens/completion_tokens 的来源(0上游真实 1本地估算)。
     * <p><b>不描述 tokens 列</b> —— tokens 列永远是估算口径。
     */
    private String usageSource;

    /** 创建时间 */
    private Date createTime;

    public Long getMessageId() {
        return messageId;
    }

    public void setMessageId(Long messageId) {
        this.messageId = messageId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getMessageKind() { return messageKind; }
    public void setMessageKind(String messageKind) { this.messageKind = messageKind; }
    public String getStepId() { return stepId; }
    public void setStepId(String stepId) { this.stepId = stepId; }
    public String getParentStepId() { return parentStepId; }
    public void setParentStepId(String parentStepId) { this.parentStepId = parentStepId; }

    public Long getAgentId() {
        return agentId;
    }

    public void setAgentId(Long agentId) {
        this.agentId = agentId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public Long getSubAgentId() {
        return subAgentId;
    }

    public void setSubAgentId(Long subAgentId) {
        this.subAgentId = subAgentId;
    }

    public String getMessageType() {
        return messageType;
    }

    public void setMessageType(String messageType) {
        this.messageType = messageType;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getVisibleToLlm() {
        return visibleToLlm;
    }

    public void setVisibleToLlm(String visibleToLlm) {
        this.visibleToLlm = visibleToLlm;
    }

    public Long getSummaryToId() {
        return summaryToId;
    }

    public void setSummaryToId(Long summaryToId) {
        this.summaryToId = summaryToId;
    }

    public String getAttachments() {
        return attachments;
    }

    public void setAttachments(String attachments) {
        this.attachments = attachments;
    }

    public String getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(String toolCalls) {
        this.toolCalls = toolCalls;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public void setToolCallId(String toolCallId) {
        this.toolCallId = toolCallId;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String getToolArgs() {
        return toolArgs;
    }

    public void setToolArgs(String toolArgs) {
        this.toolArgs = toolArgs;
    }

    public String getToolResult() {
        return toolResult;
    }

    public void setToolResult(String toolResult) {
        this.toolResult = toolResult;
    }

    public Integer getToolResultLength() {
        return toolResultLength;
    }

    public void setToolResultLength(Integer toolResultLength) {
        this.toolResultLength = toolResultLength;
    }

    public Integer getToolArgsLength() {
        return toolArgsLength;
    }

    public void setToolArgsLength(Integer toolArgsLength) {
        this.toolArgsLength = toolArgsLength;
    }

    public Boolean getHasFullToolResult() {
        return hasFullToolResult;
    }

    public void setHasFullToolResult(Boolean hasFullToolResult) {
        this.hasFullToolResult = hasFullToolResult;
    }

    public String getToolResultPath() {
        return toolResultPath;
    }

    public void setToolResultPath(String toolResultPath) {
        this.toolResultPath = toolResultPath;
    }

    public String getToolSource() {
        return toolSource;
    }

    public void setToolSource(String toolSource) {
        this.toolSource = toolSource;
    }

    public Long getToolDurationMs() {
        return toolDurationMs;
    }

    public void setToolDurationMs(Long toolDurationMs) {
        this.toolDurationMs = toolDurationMs;
    }

    public String getToolSuccess() {
        return toolSuccess;
    }

    public void setToolSuccess(String toolSuccess) {
        this.toolSuccess = toolSuccess;
    }

    public String getPruned() {
        return pruned;
    }

    public void setPruned(String pruned) {
        this.pruned = pruned;
    }

    public Integer getTokens() {
        return tokens;
    }

    public void setTokens(Integer tokens) {
        this.tokens = tokens;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(Integer promptTokens) {
        this.promptTokens = promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(Integer completionTokens) {
        this.completionTokens = completionTokens;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getUsageSource() {
        return usageSource;
    }

    public void setUsageSource(String usageSource) {
        this.usageSource = usageSource;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }
}
