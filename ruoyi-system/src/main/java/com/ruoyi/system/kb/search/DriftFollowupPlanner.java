package com.ruoyi.system.kb.search;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import com.ruoyi.system.kb.vector.KbSearchHit;

/**
 * 规则化 DRIFT 追问生成（无 LLM，可测可预算）。
 * 从命中实体名 / 标题路径 / 关键词抽取候选子问题。
 */
public final class DriftFollowupPlanner
{
    private static final Pattern SPLIT = Pattern.compile("[\\s,，。；;、:：!?？\\-_/\\\\|（）()【】\\[\\]]+");

    private DriftFollowupPlanner() {}

    /**
     * @param rootQuery     原始问题
     * @param hits          当前证据
     * @param alreadyAsked  已问过的问题（含 root）
     * @param max           最多返回条数
     * @param dedupeThreshold 与已有问题 token 重叠超过此值则丢弃（0~1）
     */
    public static List<String> plan(String rootQuery, List<KbSearchHit> hits,
        Set<String> alreadyAsked, int max, double dedupeThreshold)
    {
        if (max <= 0)
        {
            return List.of();
        }
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        // 实体名
        if (hits != null)
        {
            for (KbSearchHit h : hits)
            {
                if (h == null)
                {
                    continue;
                }
                if (h.getEntityNames() != null)
                {
                    for (String e : h.getEntityNames())
                    {
                        if (e != null && e.length() >= 2 && e.length() <= 40)
                        {
                            candidates.add(e.trim() + " 是什么");
                            candidates.add(e.trim() + " 相关说明");
                        }
                    }
                }
                if (h.getHeadingPath() != null && h.getHeadingPath().length() >= 2)
                {
                    String path = h.getHeadingPath().trim();
                    // 取最后一段标题
                    String last = path;
                    int idx = Math.max(path.lastIndexOf('/'), path.lastIndexOf('>'));
                    if (idx >= 0 && idx + 1 < path.length())
                    {
                        last = path.substring(idx + 1).trim();
                    }
                    if (last.length() >= 2 && last.length() <= 40)
                    {
                        candidates.add(last + " 的具体内容");
                    }
                }
            }
        }
        // 从 root 拆词形成补充
        for (String tok : tokens(rootQuery))
        {
            if (tok.length() >= 2 && tok.length() <= 16)
            {
                candidates.add(tok + " 的定义与背景");
            }
            if (candidates.size() >= max * 4)
            {
                break;
            }
        }

        List<String> out = new ArrayList<>();
        double thr = dedupeThreshold > 0 ? dedupeThreshold : 0.75;
        for (String c : candidates)
        {
            if (out.size() >= max)
            {
                break;
            }
            if (c == null || c.isBlank())
            {
                continue;
            }
            String norm = normalize(c);
            if (alreadyAsked != null && alreadyAsked.contains(norm))
            {
                continue;
            }
            if (tooSimilar(c, rootQuery, thr))
            {
                continue;
            }
            if (alreadyAsked != null)
            {
                boolean dup = false;
                for (String a : alreadyAsked)
                {
                    if (tooSimilar(c, a, thr))
                    {
                        dup = true;
                        break;
                    }
                }
                if (dup)
                {
                    continue;
                }
            }
            // 与已入选互相去重
            boolean dupOut = false;
            for (String o : out)
            {
                if (tooSimilar(c, o, thr))
                {
                    dupOut = true;
                    break;
                }
            }
            if (dupOut)
            {
                continue;
            }
            out.add(c);
        }
        return out;
    }

    public static boolean tooSimilar(String a, String b, double threshold)
    {
        if (a == null || b == null)
        {
            return false;
        }
        String na = normalize(a);
        String nb = normalize(b);
        if (na.equals(nb))
        {
            return true;
        }
        // 去空白后完全一致（中文空格差异）
        String ca = na.replace(" ", "");
        String cb = nb.replace(" ", "");
        if (!ca.isEmpty() && ca.equals(cb))
        {
            return true;
        }
        if (ca.length() >= 4 && cb.length() >= 4 && (ca.contains(cb) || cb.contains(ca)))
        {
            return true;
        }
        Set<String> ta = tokens(na);
        Set<String> tb = tokens(nb);
        if (ta.isEmpty() || tb.isEmpty())
        {
            return false;
        }
        int inter = 0;
        for (String t : ta)
        {
            if (tb.contains(t))
            {
                inter++;
            }
        }
        double jaccard = (double) inter / (ta.size() + tb.size() - inter);
        return jaccard >= threshold;
    }

    public static String normalize(String q)
    {
        if (q == null)
        {
            return "";
        }
        return q.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    public static Set<String> tokens(String text)
    {
        Set<String> out = new LinkedHashSet<>();
        if (text == null)
        {
            return out;
        }
        for (String p : SPLIT.split(text.toLowerCase(Locale.ROOT)))
        {
            String t = p.trim();
            if (t.length() >= 2)
            {
                out.add(t);
            }
        }
        return out;
    }
}
