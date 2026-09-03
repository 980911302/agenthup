package com.ruoyi.system.kb.search;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Auto Router：规则优先。
 * <p>未通过评测前 {@code auto} 不得作为系统默认；本类只在显式 mode=auto 时调用。
 * 可选 LLM 分类预留开关，默认关闭。
 */
public final class QueryRouter
{
    private static final Pattern GLOBAL = Pattern.compile(
        ".*(整体|全面|综述|概述|总结|汇总|有哪些方面|各方面|趋势|全貌|全局|总览|概览|对比各|分别有哪些).*");
    private static final Pattern ENTITY = Pattern.compile(
        ".*(是什么|是谁|哪个|哪些|定义|含义|介绍一下|什么是).*");
    private static final Pattern LOCAL_REL = Pattern.compile(
        ".*(关系|关联|影响|导致|依赖|如何实现|怎么做|步骤|流程|原理).*");

    private QueryRouter() {}

    public static final class Decision
    {
        public final KbSearchMode mode;
        public final double confidence;
        public final String reason;
        public final String rule;

        public Decision(KbSearchMode mode, double confidence, String reason, String rule)
        {
            this.mode = mode != null ? mode : KbSearchMode.basic;
            this.confidence = confidence;
            this.reason = reason;
            this.rule = rule;
        }

        public String toDebugJson()
        {
            return "{\"routedMode\":\"" + mode.name()
                + "\",\"confidence\":" + confidence
                + ",\"reason\":\"" + esc(reason)
                + "\",\"rule\":\"" + esc(rule) + "\"}";
        }

        private static String esc(String s)
        {
            if (s == null)
            {
                return "";
            }
            return s.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }

    /**
     * 规则路由。不含 LLM。
     */
    public static Decision route(String query)
    {
        if (query == null || query.isBlank())
        {
            return new Decision(KbSearchMode.basic, 0.5, "empty_query", "default");
        }
        String q = query.trim();
        String lower = q.toLowerCase(Locale.ROOT);

        // 1) 明显全局（find：子串命中即可）
        if (GLOBAL.matcher(q).find() || GLOBAL.matcher(lower).find())
        {
            return new Decision(KbSearchMode.global, 0.85, "global_keywords", "global_pattern");
        }
        // 2) 关系/流程 → hybrid（local+basic）
        if (LOCAL_REL.matcher(q).find())
        {
            return new Decision(KbSearchMode.hybrid, 0.8, "relation_or_howto", "local_rel_pattern");
        }
        // 3) 实体事实
        if (ENTITY.matcher(q).find())
        {
            // 短问偏 local；稍长偏 hybrid
            if (q.length() <= 24)
            {
                return new Decision(KbSearchMode.local, 0.75, "entity_fact_short", "entity_pattern");
            }
            return new Decision(KbSearchMode.hybrid, 0.7, "entity_fact_long", "entity_pattern");
        }
        // 4) 多实体并列 / 较长分析
        if (q.length() >= 40 || q.contains("以及") || q.contains("并且") || q.contains("和") && q.length() > 20)
        {
            return new Decision(KbSearchMode.hybrid, 0.65, "complex_query", "length_pattern");
        }
        // 5) 很短关键词 → basic
        if (q.length() <= 12)
        {
            return new Decision(KbSearchMode.basic, 0.7, "short_keyword", "length_pattern");
        }
        // 默认 hybrid 比 pure basic 更稳，但仍非 auto 默认入口
        return new Decision(KbSearchMode.hybrid, 0.55, "default_hybrid", "default");
    }
}
