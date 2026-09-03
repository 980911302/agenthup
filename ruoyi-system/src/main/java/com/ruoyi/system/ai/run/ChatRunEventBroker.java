package com.ruoyi.system.ai.run;

import com.alibaba.fastjson2.JSON;
import com.ruoyi.system.ai.event.ChatEventSink;
import com.ruoyi.system.domain.AiChatRun;
import com.ruoyi.system.mapper.AiChatRunMapper;
import com.ruoyi.ai.runtime.event.LegacyRunEventAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 对话运行事件总线。
 *
 * <p>Redis Stream 保存短期可回放事件；Redis Pub/Sub 将实时事件广播到其他应用实例；
 * Spring 进程内事件负责投递给本实例上的 WebSocket 订阅者。</p>
 */
@Component
public class ChatRunEventBroker
{
    private static final Logger log = LoggerFactory.getLogger(ChatRunEventBroker.class);

    public static final String CHANNEL = "ai:chat:events";
    private static final String STREAM_PREFIX = "ai:chat:run:";
    private static final Duration RETENTION = Duration.ofHours(24);
    private static final long MAX_STREAM_LENGTH = 5000;

    private final StringRedisTemplate redisTemplate;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final AiChatRunMapper runMapper;
    private final ChatRunInstance instance;
    private final ChatRunProjectionService projectionService;
    private final ConcurrentHashMap<String, AtomicLong> fallbackSequences = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> runLocks = new ConcurrentHashMap<>();

    public ChatRunEventBroker(StringRedisTemplate redisTemplate,
                              ApplicationEventPublisher applicationEventPublisher,
                              AiChatRunMapper runMapper,
                              ChatRunInstance instance,
                              ChatRunProjectionService projectionService)
    {
        this.redisTemplate = redisTemplate;
        this.applicationEventPublisher = applicationEventPublisher;
        this.runMapper = runMapper;
        this.instance = instance;
        this.projectionService = projectionService;
    }

    public ChatEventSink sink(String runId, String sessionId)
    {
        return eventJson -> publish(runId, sessionId, eventJson);
    }

    public ChatRunEventEnvelope publish(String runId, String sessionId, String eventJson)
    {
        Object lock = runLocks.computeIfAbsent(runId, ignored -> new Object());
        synchronized (lock)
        {
            long seq = nextSequence(runId);
            long timestamp = System.currentTimeMillis();
            String standardEventJson = LegacyRunEventAdapter.toStandardJson(
                    runId, seq, timestamp, eventJson);
            ChatRunEventEnvelope envelope = new ChatRunEventEnvelope(
                    instance.id(), runId, sessionId, seq, eventJson, standardEventJson, timestamp);
            String payload = JSON.toJSONString(envelope);

            persistAndBroadcast(runId, payload);
            boolean terminal = project(envelope);

            // 单个 Run 在同一临界区内按 seq 投递，杜绝并发工具事件高序号先到、低序号被前端丢弃。
            publishLocally(envelope);
            if (terminal)
            {
                projectionService.release(runId);
                fallbackSequences.remove(runId);
                runLocks.remove(runId, lock);
            }
            return envelope;
        }
    }

    /** 返回 seq 大于 afterSeq 的保留事件，按 seq 升序。 */
    public List<ChatRunEventEnvelope> replay(String runId, long afterSeq)
    {
        try
        {
            List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                    .range(streamKey(runId), Range.unbounded());
            if (records == null || records.isEmpty())
            {
                return List.of();
            }
            List<ChatRunEventEnvelope> result = new ArrayList<>();
            for (MapRecord<String, Object, Object> record : records)
            {
                Object value = record.getValue().get("payload");
                if (value == null)
                {
                    continue;
                }
                ChatRunEventEnvelope envelope = JSON.parseObject(String.valueOf(value), ChatRunEventEnvelope.class);
                if (envelope != null && envelope.getSeq() > afterSeq)
                {
                    result.add(envelope);
                }
            }
            result.sort(java.util.Comparator.comparingLong(ChatRunEventEnvelope::getSeq));
            return result;
        }
        catch (RuntimeException e)
        {
            log.warn("回放运行事件失败: runId={}, afterSeq={}", runId, afterSeq, e);
            return List.of();
        }
    }

    /** 接收其他实例发布的事件并转入本实例事件总线。 */
    public void acceptRemote(String payload)
    {
        try
        {
            ChatRunEventEnvelope envelope = JSON.parseObject(payload, ChatRunEventEnvelope.class);
            if (envelope == null || instance.id().equals(envelope.getOriginInstanceId()))
            {
                return;
            }
            publishLocally(envelope);
        }
        catch (RuntimeException e)
        {
            log.warn("忽略无法解析的对话运行广播", e);
        }
    }

    private long nextSequence(String runId)
    {
        try
        {
            Long seq = redisTemplate.opsForValue().increment(sequenceKey(runId));
            if (seq != null)
            {
                long effective = fallbackSequences.computeIfAbsent(runId, key -> new AtomicLong())
                        .accumulateAndGet(seq, Math::max);
                // Redis 短暂故障期间本机降级序号可能走得更远；恢复后把 Redis 追到同一高水位。
                if (effective > seq)
                {
                    redisTemplate.opsForValue().set(sequenceKey(runId), String.valueOf(effective), RETENTION);
                }
                return effective;
            }
        }
        catch (RuntimeException e)
        {
            log.warn("Redis 事件序号生成失败，降级为本实例序号: runId={}", runId, e);
        }
        return fallbackSequences.computeIfAbsent(runId, key -> new AtomicLong(loadPersistedSequence(key)))
                .incrementAndGet();
    }

    /**
     * WebSocket 是运行事件的观察者，观察者故障不能反向打断模型执行。
     * Spring 默认同步分发应用事件，因此这里必须隔离监听器异常。
     */
    private void publishLocally(ChatRunEventEnvelope envelope)
    {
        try
        {
            applicationEventPublisher.publishEvent(envelope);
        }
        catch (RuntimeException e)
        {
            log.warn("本机运行事件投递失败，运行将继续: runId={}, seq={}",
                    envelope.getRunId(), envelope.getSeq(), e);
        }
    }

    private long loadPersistedSequence(String runId)
    {
        try
        {
            AiChatRun run = runMapper.selectAiChatRunById(runId);
            return run != null && run.getLastEventSeq() != null ? run.getLastEventSeq() : 0L;
        }
        catch (RuntimeException e)
        {
            log.warn("读取运行事件高水位失败，从本机 0 开始降级计数: runId={}", runId, e);
            return 0L;
        }
    }

    /**
     * 持久化回放日志并广播,seq 之后的 5 步合并为一次 pipeline:每事件的 Redis 往返
     * 从 6 次降到 2 次(INCR 必须先行,其返回值编进 XADD/PUBLISH 的 payload)。
     * 失败语义与原 persistForReplay/convertAndSend 各自 catch 一致:warn 后本机投递继续。
     */
    private void persistAndBroadcast(String runId, String payload)
    {
        try
        {
            String streamKey = streamKey(runId);
            MapRecord<String, String, String> record = StreamRecords.newRecord()
                    .ofMap(Map.of("payload", payload))
                    .withStreamKey(streamKey);
            redisTemplate.executePipelined(new SessionCallback<Void>()
            {
                @Override
                @SuppressWarnings({"unchecked", "rawtypes"})
                public Void execute(RedisOperations operations)
                {
                    operations.expire(sequenceKey(runId), RETENTION);
                    operations.opsForStream().add(record);
                    operations.opsForStream().trim(streamKey, MAX_STREAM_LENGTH, true);
                    operations.expire(streamKey, RETENTION);
                    operations.convertAndSend(CHANNEL, payload);
                    return null;
                }
            });
        }
        catch (RuntimeException e)
        {
            log.warn("写入 Redis Stream / 广播运行事件失败，已保留本机投递: runId={}", runId, e);
        }
    }

    private boolean project(ChatRunEventEnvelope envelope)
    {
        try
        {
            return projectionService.project(envelope);
        }
        catch (RuntimeException e)
        {
            log.warn("更新运行事件高水位失败: runId={}, seq={}", envelope.getRunId(), envelope.getSeq(), e);
            return false;
        }
    }

    private String streamKey(String runId)
    {
        return STREAM_PREFIX + runId + ":events";
    }

    private String sequenceKey(String runId)
    {
        return STREAM_PREFIX + runId + ":seq";
    }
}
