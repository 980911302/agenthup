package com.ruoyi.system.domain;

import java.util.Date;

/**
 * LLM 调用明细表 ai_llm_call
 * <p>
 * 粒度：一次真实的模型 API 调用 = 一行。
 * 与 ai_chat_message 的区别：message 是「对话里的一条消息」，本表是「打了几次上游接口」。
 *
 * @author ruoyi
 */
public class AiLlmCall
{
    private Long callId;
    private String sessionId;
    private Long agentId;
    private String conversationId;
    private Long messageId;
    private Long modelId;
    private String modelName;
    private Integer callSeq;
    private Integer depth;
    private String finishReason;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    /**
     * 上游报的缓存命中(按 128 token 块量化,本行绝对值)。
     * 可能大于本行差值记账的 prompt_tokens;展示用 {@code CacheTokens.effectiveHit}。
     */
    private Integer cacheHitTokens;
    /** 输入中未命中上游缓存的 token 数(DeepSeek: prompt_cache_miss_tokens) */
    private Integer cacheMissTokens;
    /** 0上游真实 1本地估算 */
    private String usageSource;
    private Long durationMs;
    private String responseId;
    private Date createTime;

    public Long getCallId()
    {
        return callId;
    }

    public void setCallId(Long callId)
    {
        this.callId = callId;
    }

    public String getSessionId()
    {
        return sessionId;
    }

    public void setSessionId(String sessionId)
    {
        this.sessionId = sessionId;
    }

    public Long getAgentId()
    {
        return agentId;
    }

    public void setAgentId(Long agentId)
    {
        this.agentId = agentId;
    }

    public String getConversationId()
    {
        return conversationId;
    }

    public void setConversationId(String conversationId)
    {
        this.conversationId = conversationId;
    }

    public Long getMessageId()
    {
        return messageId;
    }

    public void setMessageId(Long messageId)
    {
        this.messageId = messageId;
    }

    public Long getModelId()
    {
        return modelId;
    }

    public void setModelId(Long modelId)
    {
        this.modelId = modelId;
    }

    public String getModelName()
    {
        return modelName;
    }

    public void setModelName(String modelName)
    {
        this.modelName = modelName;
    }

    public Integer getCallSeq()
    {
        return callSeq;
    }

    public void setCallSeq(Integer callSeq)
    {
        this.callSeq = callSeq;
    }

    public Integer getDepth()
    {
        return depth;
    }

    public void setDepth(Integer depth)
    {
        this.depth = depth;
    }

    public String getFinishReason()
    {
        return finishReason;
    }

    public void setFinishReason(String finishReason)
    {
        this.finishReason = finishReason;
    }

    public Integer getPromptTokens()
    {
        return promptTokens;
    }

    public void setPromptTokens(Integer promptTokens)
    {
        this.promptTokens = promptTokens;
    }

    public Integer getCompletionTokens()
    {
        return completionTokens;
    }

    public void setCompletionTokens(Integer completionTokens)
    {
        this.completionTokens = completionTokens;
    }

    public Integer getTotalTokens()
    {
        return totalTokens;
    }

    public void setTotalTokens(Integer totalTokens)
    {
        this.totalTokens = totalTokens;
    }

    public Integer getCacheHitTokens()
    {
        return cacheHitTokens;
    }

    public void setCacheHitTokens(Integer cacheHitTokens)
    {
        this.cacheHitTokens = cacheHitTokens;
    }

    public Integer getCacheMissTokens()
    {
        return cacheMissTokens;
    }

    public void setCacheMissTokens(Integer cacheMissTokens)
    {
        this.cacheMissTokens = cacheMissTokens;
    }

    public String getUsageSource()
    {
        return usageSource;
    }

    public void setUsageSource(String usageSource)
    {
        this.usageSource = usageSource;
    }

    public Long getDurationMs()
    {
        return durationMs;
    }

    public void setDurationMs(Long durationMs)
    {
        this.durationMs = durationMs;
    }

    public String getResponseId()
    {
        return responseId;
    }

    public void setResponseId(String responseId)
    {
        this.responseId = responseId;
    }

    public Date getCreateTime()
    {
        return createTime;
    }

    public void setCreateTime(Date createTime)
    {
        this.createTime = createTime;
    }
}
