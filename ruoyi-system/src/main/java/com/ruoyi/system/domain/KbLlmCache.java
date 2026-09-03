package com.ruoyi.system.domain;

import java.util.Date;

/**
 * 知识库 LLM 抽取/摘要缓存 kb_llm_cache
 */
public class KbLlmCache
{
    private String cacheKey;
    private String cacheType;
    private String response;
    private String modelCode;
    private Integer hitCount;
    private Date createTime;

    public String getCacheKey()
    {
        return cacheKey;
    }

    public void setCacheKey(String cacheKey)
    {
        this.cacheKey = cacheKey;
    }

    public String getCacheType()
    {
        return cacheType;
    }

    public void setCacheType(String cacheType)
    {
        this.cacheType = cacheType;
    }

    public String getResponse()
    {
        return response;
    }

    public void setResponse(String response)
    {
        this.response = response;
    }

    public String getModelCode()
    {
        return modelCode;
    }

    public void setModelCode(String modelCode)
    {
        this.modelCode = modelCode;
    }

    public Integer getHitCount()
    {
        return hitCount;
    }

    public void setHitCount(Integer hitCount)
    {
        this.hitCount = hitCount;
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
