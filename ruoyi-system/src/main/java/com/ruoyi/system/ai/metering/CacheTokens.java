package com.ruoyi.system.ai.metering;

/**
 * 缓存命中与 prompt 的展示口径。
 * <p>上游按 128 token 块报 cached_tokens,落库保留原值;
 * prompt 列是工具循环里的差值,单行 cache_hit 可能大于 prompt。统计/展示用本方法归一。
 */
public final class CacheTokens
{
    private CacheTokens() { }

    /** 展示用命中数:不超过本行 prompt。null 当 0。 */
    public static int effectiveHit(Integer cacheHitTokens, Integer promptTokens)
    {
        int hit = cacheHitTokens == null ? 0 : Math.max(0, cacheHitTokens);
        int prompt = promptTokens == null ? 0 : Math.max(0, promptTokens);
        return Math.min(hit, prompt);
    }

    /** 聚合展示用:不超过同期 prompt 总量。 */
    public static long effectiveHit(long cacheHitTokens, long promptTokens)
    {
        long hit = Math.max(0L, cacheHitTokens);
        long prompt = Math.max(0L, promptTokens);
        return Math.min(hit, prompt);
    }
}
