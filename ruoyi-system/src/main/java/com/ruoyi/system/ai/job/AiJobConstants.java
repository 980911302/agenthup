package com.ruoyi.system.ai.job;

/**
 * AI 定时任务常量
 *
 * @author ruoyi
 */
public final class AiJobConstants
{
    private AiJobConstants()
    {
    }

    /** Quartz Job/Trigger 分组,与 sys_job 区分开便于排查 */
    public static final String JOB_GROUP = "AI_AGENT";

    /** JobDataMap 中仅放 jobId,避免实体随 trigger 常驻导致改 prompt 不生效 */
    public static final String JOB_DATA_JOB_ID = "jobId";

    /** 重试序号(仅重派发时写入 JobDataMap;正常 cron 触发默认 0) */
    public static final String JOB_DATA_RETRY_NO = "retryNo";

    /** 状态:正常 */
    public static final String STATUS_NORMAL = "0";

    /** 状态:暂停 */
    public static final String STATUS_PAUSE = "1";

    /** 状态:已完成 */
    public static final String STATUS_COMPLETED = "2";

    /** 触发类型:cron 周期 */
    public static final String TRIGGER_CRON = "cron";

    /** 触发类型:一次性 */
    public static final String TRIGGER_ONCE = "once";

    /** 会话模式:每次新建 */
    public static final String SESSION_MODE_NEW = "new";

    /** 会话模式:固定会话追加 */
    public static final String SESSION_MODE_FIXED = "fixed";

    /** 会话类型:定时任务产生 */
    public static final String SESSION_TYPE_JOB = "job";

    /** 日志:已派发(run 仍在异步执行) */
    public static final String LOG_DISPATCHED = "DISPATCHED";

    /** 日志:跳过 */
    public static final String LOG_SKIPPED = "SKIPPED";

    /** 日志:成功 */
    public static final String LOG_SUCCEEDED = "SUCCEEDED";

    /** 日志:失败 */
    public static final String LOG_FAILED = "FAILED";

    /** 日志:取消 */
    public static final String LOG_CANCELLED = "CANCELLED";

    /** 日志:中断 */
    public static final String LOG_INTERRUPTED = "INTERRUPTED";

    /** 日志:超时(对账兜底) */
    public static final String LOG_TIMEOUT = "TIMEOUT";

    /** 配置:单用户启用任务上限 */
    public static final String CONFIG_MAX_PER_USER = "ai.job.maxPerUser";

    /** 配置:cron 最小触发间隔(分钟) */
    public static final String CONFIG_MIN_INTERVAL_MINUTES = "ai.job.minIntervalMinutes";

    /** 配置:日志保留天数 */
    public static final String CONFIG_LOG_RETAIN_DAYS = "ai.job.logRetainDays";

    /** 默认:单用户任务上限 */
    public static final int DEFAULT_MAX_PER_USER = 20;

    /** 默认:最小间隔分钟 */
    public static final int DEFAULT_MIN_INTERVAL_MINUTES = 5;

    /** 默认:日志保留天数 */
    public static final int DEFAULT_LOG_RETAIN_DAYS = 90;

    /** prompt 上限,与 ChatRunService.MAX_MESSAGE_CHARS 对齐 */
    public static final int MAX_PROMPT_CHARS = 100_000;

    /** 结果摘要截断长度 */
    public static final int RESULT_SUMMARY_MAX = 500;

    /** 对账宽限秒数:刚创建的 DISPATCHED 先不扫 */
    public static final int RECONCILE_GRACE_SECONDS = 30;

    /** 对账每轮条数上限 */
    public static final int RECONCILE_BATCH_SIZE = 200;
}
