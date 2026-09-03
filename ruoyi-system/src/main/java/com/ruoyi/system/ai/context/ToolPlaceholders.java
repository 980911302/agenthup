package com.ruoyi.system.ai.context;

/**
 * 工具往返被清理后的占位文本构造。
 *
 * <p>从 {@link ContextCleaner} 抽出来,因为跨轮重建({@code DbChatMemory.get})也要出同样的占位:
 * 轮内清了一对,标记落库,下一轮从库里重建时必须还原成**同一段文本**——两边各写一份,
 * 文本一旦有一个字不同,上游 KV-cache 的前缀就在这里断掉,清理反而变成缓存杀手。
 *
 * <p><b>占位要「可恢复」</b>:告诉模型「做过什么」且「如何拿回」,不能只写「已清理」。
 *
 * <p><b>全部方法幂等</b>:对已经是占位的文本再调一次返回原样。防止层层嵌套越缠越长,
 * 也防止内容一变就把缓存断点推到更早的位置。
 *
 * @author ruoyi
 */
public final class ToolPlaceholders
{
    /** 占位文本的前缀,同时用作「这对已经清过」的判据 */
    public static final String PLACEHOLDER_PREFIX = "[已精简] ";

    /** 已精简入参的标记,同上 */
    public static final String CLEARED_MARKER = "\"_cleared\":true";

    private ToolPlaceholders()
    {
    }

    /** 这段文本是不是本类造出来的占位。null 安全。 */
    public static boolean isPlaceholder(String text)
    {
        return text != null && text.startsWith(PLACEHOLDER_PREFIX);
    }

    /** 可恢复占位:告诉模型「做过」且「如何拿回」,不要只写「已清理」。 */
    public static String buildPlaceholder(String toolName, String args, String originalResult)
    {
        // 幂等兜底:已经是占位文本就原样返回。再包一层只会层层嵌套、越缠越长,
        // 且内容一变就把缓存前缀的断点推到更早的位置。
        if (isPlaceholder(originalResult))
        {
            return originalResult;
        }
        String pathHint = extractPathHint(args);
        String resultHint = summarizeResult(originalResult);
        StringBuilder sb = new StringBuilder();
        sb.append(PLACEHOLDER_PREFIX).append(toolName != null ? toolName : "tool");
        if (pathHint != null)
        {
            sb.append("(path=").append(pathHint).append(")");
        }
        sb.append(" → ").append(resultHint);
        sb.append("；完整内容见工作区文件本身，需要时用 read 重新读取。");
        return sb.toString();
    }

    /** 入参摘要:带 path 的保留 path(模型据此知道去哪儿拿回),其余保留极短预览。 */
    public static String summarizeArgs(String toolName, String args)
    {
        if (args == null || args.isBlank())
        {
            return "{}";
        }
        // 幂等兜底:带 path 的分支本身幂等,但无 path 的分支会把上次的整段 JSON 塞进 preview
        if (args.contains(CLEARED_MARKER))
        {
            return args;
        }
        String path = extractPathHint(args);
        if (path != null)
        {
            return "{\"path\":\"" + path + "\",\"_cleared\":true}";
        }
        String one = args.replaceAll("\\s+", " ");
        if (one.length() > 120)
        {
            one = one.substring(0, 120) + "…";
        }
        return "{\"_cleared\":true,\"preview\":" + quote(one) + "}";
    }

    /** 粗匹配 "path":"..." / "filePath":"...",取不到返回 null。 */
    static String extractPathHint(String args)
    {
        if (args == null)
        {
            return null;
        }
        int i = args.indexOf("\"path\"");
        if (i < 0)
        {
            i = args.indexOf("\"filePath\"");
        }
        if (i < 0)
        {
            return null;
        }
        int colon = args.indexOf(':', i);
        if (colon < 0)
        {
            return null;
        }
        int q1 = args.indexOf('"', colon + 1);
        if (q1 < 0)
        {
            return null;
        }
        int q2 = args.indexOf('"', q1 + 1);
        if (q2 < 0)
        {
            return null;
        }
        return args.substring(q1 + 1, q2);
    }

    private static String summarizeResult(String result)
    {
        if (result == null || result.isBlank())
        {
            return "已完成";
        }
        String one = result.replaceAll("\\s+", " ").trim();
        if (one.length() > 80)
        {
            one = one.substring(0, 80) + "…";
        }
        return one;
    }

    private static String quote(String s)
    {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
