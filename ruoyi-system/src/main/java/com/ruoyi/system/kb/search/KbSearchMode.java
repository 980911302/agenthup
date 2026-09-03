package com.ruoyi.system.kb.search;

/**
 * 知识检索模式。
 * <p>canonical：basic / local / hybrid / global / drift / auto；
 * 旧名 vector / graph / mix 作别名。
 * <p>{@link #auto} 仅在显式请求时启用，系统默认永远是 {@link #basic}。
 */
public enum KbSearchMode
{
    /** 纯向量叶子检索（别名 vector） */
    basic,
    /** 实体映射 + 图扩展 + 原文证据（别名 graph） */
    local,
    /** Basic + Local RRF 融合（别名 hybrid） */
    hybrid,
    /** 社区报告动态选择 + map（Global） */
    global,
    /** Local 起点 + 社区上下文 + 有限追问（DRIFT） */
    drift,
    /** 规则路由到其它模式；不得作为系统默认 */
    auto,
    /** 兼容旧名，等同 {@link #basic} */
    @Deprecated
    vector,
    /** 兼容旧名，等同 {@link #local} */
    @Deprecated
    graph,
    /** 兼容旧名，等同 {@link #hybrid} */
    @Deprecated
    mix;

    public static KbSearchMode of(String raw)
    {
        if (raw == null || raw.isBlank())
        {
            return basic;
        }
        String s = raw.trim().toLowerCase();
        return switch (s)
        {
            case "vector", "basic" -> basic;
            case "graph", "local" -> local;
            case "mix", "hybrid" -> hybrid;
            case "global", "community" -> global;
            case "drift" -> drift;
            case "auto" -> auto;
            default -> {
                try
                {
                    yield normalize(KbSearchMode.valueOf(s));
                }
                catch (Exception e)
                {
                    yield basic;
                }
            }
        };
    }

    /**
     * 归一别名。auto/drift/global 保持自身。
     */
    public static KbSearchMode normalize(KbSearchMode mode)
    {
        if (mode == null)
        {
            return basic;
        }
        return switch (mode)
        {
            case vector, basic -> basic;
            case graph, local -> local;
            case mix, hybrid -> hybrid;
            case global -> global;
            case drift -> drift;
            case auto -> auto;
        };
    }

    public boolean isBasic()
    {
        return normalize(this) == basic;
    }

    public boolean isLocal()
    {
        return normalize(this) == local;
    }

    public boolean isHybrid()
    {
        return normalize(this) == hybrid;
    }

    public boolean isGlobal()
    {
        return normalize(this) == global;
    }

    public boolean isDrift()
    {
        return normalize(this) == drift;
    }

    public boolean isAuto()
    {
        return normalize(this) == auto;
    }
}
