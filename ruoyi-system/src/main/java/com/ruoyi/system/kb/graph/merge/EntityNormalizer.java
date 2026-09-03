package com.ruoyi.system.kb.graph.merge;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 层 1：确定性实体名清洗（NFKC、空白、全半角、大小写、首尾标点）。
 */
public final class EntityNormalizer
{
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");
    private static final Pattern EDGE_PUNCT = Pattern.compile("^[\\p{Punct}\\p{IsPunctuation}「」『』【】（）()\\[\\]\"'“”‘’]+|[\\p{Punct}\\p{IsPunctuation}「」『』【】（）()\\[\\]\"'“”‘’]+$");

    private EntityNormalizer()
    {
    }

    public static String normalizeName(String raw)
    {
        if (raw == null)
        {
            return "";
        }
        String s = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        s = fullWidthToHalf(s);
        s = s.trim();
        s = MULTI_SPACE.matcher(s).replaceAll(" ");
        // 反复剥首尾标点
        String prev;
        do
        {
            prev = s;
            s = EDGE_PUNCT.matcher(s).replaceAll("").trim();
        }
        while (!s.equals(prev));
        // 大小写折叠仅用于键；展示名保留原始
        return s.toLowerCase(Locale.ROOT);
    }

    public static String normalizeType(String raw)
    {
        if (raw == null || raw.isBlank())
        {
            return "";
        }
        String s = Normalizer.normalize(raw.trim(), Normalizer.Form.NFKC);
        s = fullWidthToHalf(s);
        return s.toLowerCase(Locale.ROOT);
    }

    public static String normalizePredicate(String raw)
    {
        if (raw == null || raw.isBlank())
        {
            return "related";
        }
        String s = Normalizer.normalize(raw.trim(), Normalizer.Form.NFKC);
        s = fullWidthToHalf(s);
        s = MULTI_SPACE.matcher(s).replaceAll(" ");
        return s.toLowerCase(Locale.ROOT);
    }

    /** 全角 ASCII → 半角 */
    static String fullWidthToHalf(String s)
    {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);
            if (c == 0x3000)
            {
                sb.append(' ');
            }
            else if (c >= 0xFF01 && c <= 0xFF5E)
            {
                sb.append((char) (c - 0xFEE0));
            }
            else
            {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
