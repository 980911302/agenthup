package com.ruoyi.system.kb.chunker;

import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tokenizer.TokenCountEstimator;

/**
 * 分块 token 计数。使用 Spring AI 内置的 JTokkit(CL100K)实现；异常时才退回字符估算。
 */
public final class TokenCounter
{
    private static final TokenCountEstimator ESTIMATOR = new JTokkitTokenCountEstimator();

    private TokenCounter() {}

    public static int estimate(String text)
    {
        if (text == null || text.isEmpty())
        {
            return 0;
        }
        try
        {
            return Math.max(1, ESTIMATOR.estimate(text));
        }
        catch (RuntimeException ignored)
        {
            return fallbackEstimate(text);
        }
    }

    /**
     * 返回从 {@code start} 开始、不超过 {@code maxTokens} 的最远字符边界。
     * 使用二分查找，避免真实 tokenizer 接入后逐字符回退造成平方级开销。
     */
    static int maxEndWithin(String text, int start, int maxTokens)
    {
        if (text == null || start >= text.length() || maxTokens <= 0)
        {
            return Math.max(0, Math.min(start, text == null ? 0 : text.length()));
        }
        if (estimate(text.substring(start)) <= maxTokens)
        {
            return text.length();
        }

        int low = start + 1;
        int high = text.length();
        int best = start;
        while (low <= high)
        {
            int mid = low + (high - low) / 2;
            if (estimate(text.substring(start, mid)) <= maxTokens)
            {
                best = mid;
                low = mid + 1;
            }
            else
            {
                high = mid - 1;
            }
        }

        if (best > start && best < text.length() && Character.isHighSurrogate(text.charAt(best - 1)))
        {
            best--;
        }
        if (best <= start)
        {
            best = text.offsetByCodePoints(start, 1);
        }
        while (best > start && estimate(text.substring(start, best)) > maxTokens)
        {
            best = text.offsetByCodePoints(best, -1);
        }
        return best;
    }

    /** 取不超过指定 token 数的最长尾部文本。 */
    static String suffixWithin(String text, int maxTokens)
    {
        if (text == null || text.isEmpty() || maxTokens <= 0)
        {
            return "";
        }
        if (estimate(text) <= maxTokens)
        {
            return text;
        }

        int low = 0;
        int high = text.length();
        int best = text.length();
        while (low <= high)
        {
            int mid = low + (high - low) / 2;
            if (estimate(text.substring(mid)) <= maxTokens)
            {
                best = mid;
                high = mid - 1;
            }
            else
            {
                low = mid + 1;
            }
        }
        if (best < text.length() && Character.isLowSurrogate(text.charAt(best)))
        {
            best++;
        }
        String suffix = text.substring(Math.min(best, text.length()));
        while (!suffix.isEmpty() && estimate(suffix) > maxTokens)
        {
            int next = suffix.offsetByCodePoints(0, 1);
            suffix = suffix.substring(next);
        }
        return suffix;
    }

    private static int fallbackEstimate(String text)
    {
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++)
        {
            char c = text.charAt(i);
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN
                || (c >= 0x3000 && c <= 0x303F)
                || (c >= 0xFF00 && c <= 0xFFEF))
            {
                cjk++;
            }
            else if (!Character.isWhitespace(c))
            {
                other++;
            }
        }
        return Math.max(1, (int) Math.ceil(cjk / 1.5) + (int) Math.ceil(other / 4.0));
    }
}
