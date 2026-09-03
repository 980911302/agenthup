package com.ruoyi.system.ai.memory.longterm;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.ruoyi.system.ai.memory.ChatMessageRecorder;
import com.ruoyi.system.domain.AiChatMessage;

/**
 * 空闲会话兜底提炼扫描(spec §8.2)。
 *
 * <p>压缩只在 {@code used > threshold} 时触发,<b>短会话可能一次都压不到</b>,所以兜底
 * 不是补充,是另一半主力:定期扫描「一段时间无新消息、且记忆提炼位点 &lt; 最新 messageId」
 * 的活跃会话,读历史 → 调 {@link MemoryExtractor} 提炼 → 推进提炼位点。
 *
 * <p>为什么不用 {@code @Scheduled}:启用 {@code @EnableScheduling} 是全局开关,且基础设施
 * 行为不该出现在若依定时任务界面里让人误停 —— 与 {@link com.ruoyi.system.ai.job.AiJobReconciler}
 * 同一取舍,照它写:{@code @PostConstruct} + 单线程 {@link ScheduledExecutorService} + daemon 线程。
 *
 * <p>提炼位点:存独立进度表 {@code ai_memory_extract_progress}(session_id 主键),不污染
 * {@code ai_chat_session} 主表(避免影响其它子代理)。压缩的边界由 SUMMARY 行的
 * {@code summary_to_id} 承载,兜底扫描没有压缩,只能另存位点 —— 极简表正好满足。
 *
 * <p>旁路属性:任何会话提炼失败都不影响其它会话,也不影响主对话;扫描循环抛异常也吞掉,
 * 否则 {@code scheduleWithFixedDelay} 会永久停止后续执行。
 */
@Component
public class IdleSessionExtractScheduler
{
    private static final Logger log = LoggerFactory.getLogger(IdleSessionExtractScheduler.class);

    /** 提炼的目标会话上下文(会话属性 + 位点)。 */
    public record ExtractTarget(String sessionId, Long agentId, Long userId,
                                long fromMessageId, long latestMessageId)
    {
    }

    /** 会话查询 + 位点持久化接口,抽出来便于单测(Mock 而非 Spring 上下文)。 */
    public interface ProgressStore
    {
        /** 返回待提炼的会话目标(空闲 + 位点未推进到最新);无则不返回。 */
        List<ExtractTarget> candidates();

        /** 推进某会话提炼位点(只进不退),并清零失败计数。 */
        void advance(String sessionId, Long agentId, Long userId, long extractToMessageId);

        /**
         * 记一次提炼失败:失败计数 +1,并按次数指数退避下次重试时间。
         *
         * <p>没有这个,LLM 稳定失败的会话会每轮扫描都被捞出来重试<b>到永远</b>;
         * 而候选是 {@code order by update_time asc limit N},这些卡住的老会话会永久
         * 占据候选名额前排,把新的待提炼会话挤出去 —— 队头阻塞。
         */
        void markFailure(String sessionId, Long agentId, Long userId);
    }

    @Autowired
    private ProgressStore progressStore;

    @Autowired
    private ChatMessageRecorder recorder;

    @Autowired
    private MemoryExtractor extractor;

    /** 兜底扫描开关;测试可关。 */
    @Value("${ai.memory.extract.idle-sweep-enabled:true}")
    private boolean idleSweepEnabled;

    /** 空闲多久触发兜底提炼(分钟)。 */
    @Value("${ai.memory.extract.idle-sweep-minutes:30}")
    private int idleSweepMinutes;

    /** 扫描周期(秒)。 */
    @Value("${ai.memory.extract.sweep-interval-seconds:300}")
    private long sweepIntervalSeconds;

    private ScheduledExecutorService scheduler;

    @PostConstruct
    public void start()
    {
        if (!idleSweepEnabled || progressStore == null)
        {
            log.info("空闲会话记忆提炼扫描未启用(idle-sweep-enabled={})", idleSweepEnabled);
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ai-memory-idle-extract");
            t.setDaemon(true);
            return t;
        });
        long interval = Math.max(sweepIntervalSeconds, 1);
        scheduler.scheduleWithFixedDelay(this::sweepSafely, interval, interval, TimeUnit.SECONDS);
        log.info("空闲会话记忆提炼扫描已启动,间隔 {}s,空闲阈值 {}min", interval, idleSweepMinutes);
    }

    @PreDestroy
    public void stop()
    {
        if (scheduler != null)
        {
            scheduler.shutdownNow();
        }
    }

    /** 必须吞掉所有异常:scheduleWithFixedDelay 里抛未捕获异常会让后续执行永久停止。 */
    private void sweepSafely()
    {
        try
        {
            sweep();
        }
        catch (Exception e)
        {
            log.error("空闲会话记忆提炼扫描失败,下轮重试", e);
        }
    }

    /**
     * 单次扫描:找待提炼会话,逐个提炼并推进位点。
     *
     * <p>公开且无副作用(只读查询 + 提炼)以便测试直接调用。
     */
    public void sweep()
    {
        if (progressStore == null)
        {
            return;
        }
        List<ExtractTarget> targets = progressStore.candidates();
        if (targets == null || targets.isEmpty())
        {
            return;
        }
        for (ExtractTarget t : targets)
        {
            try
            {
                extractOne(t);
            }
            catch (Exception e)
            {
                // 单个会话提炼失败不影响其它会话;位点不推进,下次重试
                log.warn("空闲会话提炼失败 session={}: {}", t.sessionId(), e.getMessage());
            }
        }
    }

    private void extractOne(ExtractTarget t)
    {
        long fromId = Math.max(t.fromMessageId(), 0L);
        String conversationId = conversationId(t.sessionId(), t.agentId());
        List<AiChatMessage> messages = recorder.selectVisible(conversationId, fromId);
        if (messages == null || messages.isEmpty())
        {
            return;
        }
        long latest = messages.get(messages.size() - 1).getMessageId();
        MemoryExtractor.ExtractResult result =
                extractor.extract(t.userId(), t.agentId(), t.sessionId(), messages, latest);
        // 只在「真正提炼过」(attempted=true)时推进位点:LLM 失败/超时(attempted=false)
        // 不推进,下次扫描重试 —— spec §8.4「提炼失败/超时:安静跳过,位点不推进,下次重试」。
        if (result.attempted() && latest > fromId)
        {
            progressStore.advance(t.sessionId(), t.agentId(), t.userId(), latest);
            log.debug("空闲会话提炼完成 session={} 提炼{}条 位点推进到 messageId={}",
                    t.sessionId(), result.persisted(), latest);
        }
        else if (!result.attempted())
        {
            // 「下次重试」必须带退避,否则稳定失败的会话每轮都被捞出来,还会靠
            // order by update_time asc 永久霸占候选名额,把新会话挤出去
            progressStore.markFailure(t.sessionId(), t.agentId(), t.userId());
            log.debug("空闲会话提炼跳过(未真正提炼) session={} 位点不推进,已记失败并退避",
                    t.sessionId());
        }
    }

    /** conversationId = sessionId:agentId(见 {@link com.ruoyi.system.ai.memory.ConversationIds})。 */
    static String conversationId(String sessionId, Long agentId)
    {
        return sessionId + ":" + agentId;
    }
}
