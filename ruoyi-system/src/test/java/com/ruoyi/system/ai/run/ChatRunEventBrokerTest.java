package com.ruoyi.system.ai.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.ruoyi.system.domain.AiChatRun;
import com.ruoyi.system.mapper.AiChatRunMapper;

/**
 * 事件总线结构锁:每个事件一次 INCR 拿 seq,seq 之后的 5 个写(EXPIRE/XADD/XTRIM/EXPIRE/PUBLISH)
 * 合并为一次 pipeline;Redis 全挂时本机投递仍存活。该类此前零测试。
 */
class ChatRunEventBrokerTest
{
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private ApplicationEventPublisher publisher;
    private AiChatRunMapper runMapper;
    private ChatRunInstance instance;
    private ChatRunProjectionService projection;
    private ChatRunEventBroker broker;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp()
    {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        publisher = mock(ApplicationEventPublisher.class);
        runMapper = mock(AiChatRunMapper.class);
        instance = mock(ChatRunInstance.class);
        when(instance.id()).thenReturn("inst-1");
        projection = mock(ChatRunProjectionService.class);
        broker = new ChatRunEventBroker(redis, publisher, runMapper, instance, projection);
    }

    @Test
    void publish_pipelinesWritesAfterIncr_andDeliversLocally()
    {
        when(valueOps.increment("ai:chat:run:r1:seq")).thenReturn(7L);
        when(projection.project(any())).thenReturn(false);
        String eventJson = "{\"type\":\"text\",\"text\":\"hi\"}";

        ChatRunEventEnvelope out = broker.publish("r1", "s1", eventJson);

        assertEquals(7L, out.getSeq(), "seq 必须来自 Redis INCR");
        // 一次 pipeline 承载全部 seq 之后的写
        verify(redis, times(1)).executePipelined(any(SessionCallback.class));
        // 不允许再出现零散往返:stream/expire/convertAndSend 都必须走 pipeline
        verify(redis, never()).opsForStream();
        verify(redis, never()).expire(anyString(), any(Duration.class));
        verify(redis, never()).convertAndSend(anyString(), anyString());

        ArgumentCaptor<ChatRunEventEnvelope> captor =
                ArgumentCaptor.forClass(ChatRunEventEnvelope.class);
        verify(publisher).publishEvent(captor.capture());
        ChatRunEventEnvelope delivered = captor.getValue();
        assertEquals(7L, delivered.getSeq());
        assertEquals(eventJson, delivered.getEventJson());
        assertEquals("r1", delivered.getRunId());
        assertEquals("s1", delivered.getSessionId());
        assertEquals("inst-1", delivered.getOriginInstanceId());
    }

    /** 终态事件触发投影缓冲与锁的清理,顺序仍在同一临界区内。 */
    @Test
    void publish_terminalEvent_releasesProjection()
    {
        when(valueOps.increment("ai:chat:run:r2:seq")).thenReturn(1L);
        when(projection.project(any())).thenReturn(true);

        broker.publish("r2", "s1", "{\"type\":\"done\"}");

        verify(projection).release("r2");
        verify(publisher).publishEvent(any(ChatRunEventEnvelope.class));
    }

    /** Redis 全挂:序号降级为 DB 高水位 +1,pipeline 失败不挡本机投递。 */
    @Test
    void publish_redisDown_degradesToFallbackSequenceAndLocalDeliveryOnly()
    {
        when(valueOps.increment("ai:chat:run:r3:seq"))
                .thenThrow(new RuntimeException("redis down"));
        when(redis.executePipelined(any(SessionCallback.class)))
                .thenThrow(new RuntimeException("redis down"));
        AiChatRun persisted = new AiChatRun();
        persisted.setLastEventSeq(5L);
        when(runMapper.selectAiChatRunById("r3")).thenReturn(persisted);
        when(projection.project(any())).thenReturn(false);

        ChatRunEventEnvelope out = broker.publish("r3", "s1", "{\"type\":\"text\",\"text\":\"x\"}");

        assertEquals(6L, out.getSeq(), "降级序号 = DB 高水位 + 1");
        verify(publisher).publishEvent(any(ChatRunEventEnvelope.class));
    }
}
