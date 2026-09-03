package com.ruoyi.system.kb.search;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 社区报告相关性打分（无 LLM）：词重叠 + 子串命中。
 * <p>可选叠加向量分（0~1）取 max。
 */
public final class CommunityRelevanceScorer
{
    private static final Pattern SPLIT = Pattern.compile("[\\s,，。；;、:：!?？\\-_/\\\\|]+");

    private CommunityRelevanceScorer() {}

    /**
     * @param query       用户问题
     * @param title       报告标题
     * @param summary     报告摘要
     * @param vectorScore 可选向量分（null 忽略）
     * @return 0~1
     */
    public static double score(String query, String title, String summary, Double vectorScore)
    {
        double lexical = lexicalScore(query, join(title, summary));
        if (vectorScore == null)
        {
            return clamp(lexical);
        }
        double v = clamp(vectorScore);
        // 向量与词面取 max，避免词面漏召回时完全丢弃向量信号
        return Math.max(lexical, v);
    }

    public static double lexicalScore(String query, String text)
    {
        if (query == null || query.isBlank() || text == null || text.isBlank())
        {
            return 0.0;
        }
        String q = query.toLowerCase(Locale.ROOT).trim();
        String t = text.toLowerCase(Locale.ROOT);
        if (t.contains(q) && q.length() >= 2)
        {
            return 1.0;
        }
        Set<String> qTokens = tokens(q);
        if (qTokens.isEmpty())
        {
            return 0.0;
        }
        int hit = 0;
        for (String tok : qTokens)
        {
            if (tok.length() >= 2 && t.contains(tok))
            {
                hit++;
            }
        }
        double overlap = (double) hit / qTokens.size();
        // 中文无空格时 tokens 可能少，再加 bigram 兜底
        if (qTokens.size() <= 2 && q.length() >= 4)
        {
            int bgHit = 0;
            int bgTotal = 0;
            for (int i = 0; i + 2 <= q.length() && bgTotal < 8; i++)
            {
                String bg = q.substring(i, i + 2);
                if (bg.isBlank())
                {
                    continue;
                }
                bgTotal++;
                if (t.contains(bg))
                {
                    bgHit++;
                }
            }
            if (bgTotal > 0)
            {
                overlap = Math.max(overlap, (double) bgHit / bgTotal);
            }
        }
        return clamp(overlap);
    }

    public static Set<String> tokens(String text)
    {
        Set<String> out = new HashSet<>();
        if (text == null)
        {
            return out;
        }
        for (String p : SPLIT.split(text))
        {
            String s = p.trim().toLowerCase(Locale.ROOT);
            if (s.length() >= 2)
            {
                out.add(s);
            }
        }
        if (out.isEmpty())
        {
            String compact = text.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
            for (int i = 0; i + 2 <= compact.length() && out.size() < 12; i++)
            {
                out.add(compact.substring(i, Math.min(i + 3, compact.length())));
            }
        }
        return out;
    }

    private static String join(String a, String b)
    {
        StringBuilder sb = new StringBuilder();
        if (a != null)
        {
            sb.append(a).append(' ');
        }
        if (b != null)
        {
            sb.append(b);
        }
        return sb.toString();
    }

    private static double clamp(double v)
    {
        if (Double.isNaN(v) || v < 0)
        {
            return 0.0;
        }
        return Math.min(1.0, v);
    }

    /** 从已排序 token 列表构建（测试辅助） */
    public static double overlapRatio(List<String> queryTokens, String text)
    {
        if (queryTokens == null || queryTokens.isEmpty() || text == null)
        {
            return 0.0;
        }
        String t = text.toLowerCase(Locale.ROOT);
        int hit = 0;
        for (String tok : queryTokens)
        {
            if (tok != null && tok.length() >= 2 && t.contains(tok.toLowerCase(Locale.ROOT)))
            {
                hit++;
            }
        }
        return (double) hit / queryTokens.size();
    }
}
