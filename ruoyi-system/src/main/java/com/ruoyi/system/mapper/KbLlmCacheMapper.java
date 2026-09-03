package com.ruoyi.system.mapper;

import org.apache.ibatis.annotations.Param;
import com.ruoyi.system.domain.KbLlmCache;

/**
 * 知识库 LLM 缓存 数据层
 */
public interface KbLlmCacheMapper
{
    KbLlmCache selectByKey(String cacheKey);

    int insert(KbLlmCache row);

    int incrementHit(@Param("cacheKey") String cacheKey);
}
