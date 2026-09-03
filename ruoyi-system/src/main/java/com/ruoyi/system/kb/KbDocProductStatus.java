package com.ruoyi.system.kb;

/**
 * 文档面向业务的产品状态（列表/工作台展示用）。
 * <p>内部 pipeline 状态仍见 {@link KbDocStatus} / parse_step。
 */
public final class KbDocProductStatus
{
    public static final String READY = "READY";
    public static final String PROCESSING = "PROCESSING";
    public static final String FAILED = "FAILED";
    public static final String QUEUED = "QUEUED";

    private KbDocProductStatus() {}

    public static String fromParseStatus(String parseStatus)
    {
        if (parseStatus == null || parseStatus.isEmpty())
        {
            return QUEUED;
        }
        return switch (parseStatus)
        {
            case "COMPLETED" -> READY;
            case "FAILED" -> FAILED;
            case "PENDING" -> QUEUED;
            default -> PROCESSING;
        };
    }

    public static String label(String productStatus)
    {
        if (productStatus == null)
        {
            return "—";
        }
        return switch (productStatus)
        {
            case READY -> "可用";
            case PROCESSING -> "处理中";
            case FAILED -> "失败";
            case QUEUED -> "排队中";
            default -> productStatus;
        };
    }

    public static boolean isBusy(String parseStatus)
    {
        String p = fromParseStatus(parseStatus);
        return PROCESSING.equals(p) || QUEUED.equals(p);
    }
}
