package com.ruoyi.system.kb.search;

import java.util.Map;

/**
 * 检索请求参数归一化（可单测）。
 * <p>非 MANAGE：强制 auto + debug=false，忽略 topK/minScore/mode/debug 覆盖。
 */
public final class KbSearchRequestNormalizer
{
    public static final int DEFAULT_TOP_K = 5;
    public static final double DEFAULT_MIN_SCORE = 0.3;

    private KbSearchRequestNormalizer() {}

    public record Params(int topK, double minScore, KbSearchMode mode, boolean debug)
    {
    }

    public static Params normalize(boolean canManage, Map<String, Object> body)
    {
        if (!canManage)
        {
            return new Params(DEFAULT_TOP_K, DEFAULT_MIN_SCORE, KbSearchMode.of("auto"), false);
        }
        int topK = clampInt(body == null ? null : body.get("topK"), DEFAULT_TOP_K, 1, 20);
        double minScore = clampDouble(body == null ? null : body.get("minScore"), DEFAULT_MIN_SCORE, 0.0, 1.0);
        String modeRaw = body != null && body.get("mode") != null
            ? String.valueOf(body.get("mode")) : "auto";
        boolean debug = body != null && body.get("debug") != null
            && Boolean.parseBoolean(String.valueOf(body.get("debug")));
        return new Params(topK, minScore, KbSearchMode.of(modeRaw), debug);
    }

    private static int clampInt(Object v, int def, int min, int max)
    {
        int n = def;
        if (v != null)
        {
            try
            {
                n = Integer.parseInt(String.valueOf(v));
            }
            catch (NumberFormatException ignored)
            {
            }
        }
        return Math.max(min, Math.min(max, n));
    }

    private static double clampDouble(Object v, double def, double min, double max)
    {
        double n = def;
        if (v != null)
        {
            try
            {
                n = Double.parseDouble(String.valueOf(v));
            }
            catch (NumberFormatException ignored)
            {
            }
        }
        return Math.max(min, Math.min(max, n));
    }
}
