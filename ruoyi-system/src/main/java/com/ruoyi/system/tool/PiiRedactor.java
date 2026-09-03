package com.ruoyi.system.tool;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 落库/日志用的 PII 脱敏。
 *
 * <p><b>不作用于发给模型的内容</b> —— 模型需要真实数据才能干活;
 * 切分点是「存储 vs 传输」,见 docs/ai/ai-borrowed-capabilities.md §1.2。
 */
public final class PiiRedactor
{
    private PiiRedactor() {}

    /** 中国大陆手机号 */
    private static final Pattern PHONE = Pattern.compile(
            "(?<![0-9])(1[3-9]\\d)(\\d{4})(\\d{4})(?![0-9])");

    /** 18 位身份证(末位可为 X) */
    private static final Pattern ID_CARD = Pattern.compile(
            "(?<![0-9A-Za-z])([1-9]\\d{5})((?:19|20)\\d{2})(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])(\\d{3}[0-9Xx])(?![0-9A-Za-z])");

    /** 邮箱 */
    private static final Pattern EMAIL = Pattern.compile(
            "([A-Za-z0-9._%+-]{1,64})@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})");

    /** sk- 开头的 API key / Bearer token */
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(\\bsk-[A-Za-z0-9_\\-]{8,}\\b|\\bBearer\\s+[A-Za-z0-9._\\-+=/]{8,})");

    /**
     * 对落库或日志文本脱敏;null 原样返回。
     */
    public static String forStorage(String text)
    {
        if (text == null || text.isEmpty())
        {
            return text;
        }
        String s = SECRET.matcher(text).replaceAll("[REDACTED]");
        s = maskIdCards(s);
        s = PHONE.matcher(s).replaceAll("$1****$3");
        s = maskEmails(s);
        return s;
    }

    private static String maskIdCards(String text)
    {
        Matcher m = ID_CARD.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find())
        {
            String full = m.group();
            String masked = full.substring(0, 4) + "**********" + full.substring(full.length() - 4);
            m.appendReplacement(sb, Matcher.quoteReplacement(masked));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String maskEmails(String text)
    {
        Matcher m = EMAIL.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find())
        {
            String local = m.group(1);
            String domain = m.group(2);
            String maskedLocal = local.length() <= 1 ? "*" : local.charAt(0) + "***";
            m.appendReplacement(sb, Matcher.quoteReplacement(maskedLocal + "@" + domain));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
