package com.ruoyi.system.ai.context;

/**
 * 「一条工具结果进上下文时长什么样」的<b>唯一产出点</b>。
 *
 * <p>这个量原先在三个地方各算一遍,公式还不一致:
 * <ul>
 *   <li>本轮装配({@code RecordingToolCallback.capForModel})—— 行数 + 字符两道截断</li>
 *   <li>跨轮重建({@code DbChatMemory.resolveToolData})—— 只有字符截断</li>
 *   <li>tokens 列({@code ChatMessageRecorder.recordToolCall})—— 只有字符截断</li>
 * </ul>
 * 于是「行多字符少」的输出(ls -R / find / 大 CSV,正是加行数截断要治的那类)本轮被截到
 * 2000 行,下一轮重建时又自己长回全量:重建出的不是模型当时看到的那条消息,
 * 消息前缀也从这里断掉,上游 KV-cache 白维护。
 *
 * <p>所以规则收在这一处,三个调用方都从这里取。<b>以后再加截断维度(按 token、按结构……)
 * 只改这里</b>,计量与重建自动跟上 —— 不必再记得同步另外两个文件。
 *
 * <p>幂等:{@code cap(cap(x)) == cap(x)}。跨轮重建对已截断的文本再跑一次不会二次劣化,
 * 「重建结果 == 本轮所见」这条不变量才立得住。
 *
 * @author ruoyi
 */
public final class ToolResultText
{
    /** 与 {@code ai.chat.tool.max-result-chars} 的缺省值同源,供 @Value 占位符复用 */
    public static final int DEFAULT_MAX_CHARS = 30000;

    /** 与 {@code ai.chat.tool.max-result-lines} 的缺省值同源,供 @Value 占位符复用 */
    public static final int DEFAULT_MAX_LINES = 2000;

    private ToolResultText()
    {
    }

    /**
     * 按上限裁出「进上下文的正文」。
     *
     * <p>两道截断是<b>独立维度</b>:行数管信息密度,字符数管上下文成本。先行后字符 ——
     * 反过来会先把长行切碎,行数统计跟着失真。
     *
     * @param raw      工具返回原文
     * @param maxLines 行数上限,{@code <= 0} 表示不限
     * @param maxChars 字符上限,{@code <= 0} 表示不限
     */
    public static String cap(String raw, int maxLines, int maxChars)
    {
        if (raw == null)
        {
            return null;
        }
        String capped = maxLines > 0 ? truncateLines(raw, maxLines) : raw;
        return maxChars > 0 ? truncateMiddle(capped, maxChars) : capped;
    }

    /**
     * 字符维度中间截断,保留首尾。
     *
     * <p>省略提示写明确切字符数并给出下一步建议:模型看到「中间省略」才知道信息不全,
     * 否则它会把截断处当成真实结尾继续推理。
     */
    public static String truncateMiddle(String s, int max)
    {
        if (s == null || s.length() <= max)
        {
            return s;
        }
        String probe = "\n…[中间省略 " + s.length() + " 字符。如需被省略的部分，请缩小查询范围后重新调用本工具]…\n";
        int keep = max - probe.length();
        if (keep <= 0)
        {
            return s.substring(0, max);
        }
        int head = keep / 2;
        int tail = keep - head;
        String marker = "\n…[中间省略 " + (s.length() - keep) + " 字符。如需被省略的部分，请缩小查询范围后重新调用本工具]…\n";
        return s.substring(0, head) + marker + s.substring(s.length() - tail);
    }

    /**
     * 行数维度中间截断,保留首尾。
     *
     * <p>与 {@link #truncateMiddle} 的字符上限互补:{@code ls -R} / {@code find} / 大 CSV
     * 这类输出行数极多但字符数未必超,只卡字符会让它们整段灌进上下文。
     */
    public static String truncateLines(String s, int maxLines)
    {
        if (s == null || maxLines <= 0)
        {
            return s;
        }
        String[] lines = s.split("\\n", -1);
        if (lines.length <= maxLines)
        {
            return s;
        }
        // keep = maxLines - 1:省略说明本身也占一行,不预留的话截出来会比上限多一行
        int keep = maxLines - 1;
        int head = keep / 2;
        int tail = keep - head;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < head; i++)
        {
            sb.append(lines[i]).append('\n');
        }
        sb.append("…[中间省略 ").append(lines.length - keep)
                .append(" 行。如需被省略的部分，请缩小查询范围后重新调用本工具]…\n");
        for (int i = lines.length - tail; i < lines.length; i++)
        {
            sb.append(lines[i]);
            if (i < lines.length - 1)
            {
                sb.append('\n');
            }
        }
        return sb.toString();
    }
}
