package com.ruoyi.system.ai.run;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.ruoyi.common.exception.ServiceException;

class ChatRunRateLimiterTest
{
    @Test
    void exceedingLimit_throws()
    {
        ChatRunRateLimiter limiter = limiter(21L);
        assertThrows(ServiceException.class, () -> limiter.check(8L));
    }

    @Test
    void underLimit_passes()
    {
        ChatRunRateLimiter limiter = limiter(3L);
        assertDoesNotThrow(() -> limiter.check(8L));
    }

    @Test
    void nullUser_isIgnored()
    {
        ChatRunRateLimiter limiter = limiter(99L);
        assertDoesNotThrow(() -> limiter.check(null));
    }

    @Test
    void missingTtl_isRepairedBeforeCounting()
    {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(anyString())).thenReturn(2L);
        when(redis.getExpire(anyString())).thenReturn(-1L);
        when(redis.expire(anyString(), any(Duration.class))).thenReturn(true);
        ChatRunRateLimiter limiter = new ChatRunRateLimiter(redis);
        limiter.limit = 20;
        limiter.windowSeconds = 60;
        assertDoesNotThrow(() -> limiter.check(8L));
    }

    @Test
    void expireFailure_deletesKeyAndDoesNotLockUser()
    {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(anyString())).thenReturn(1L);
        when(redis.getExpire(anyString())).thenReturn(-1L);
        when(redis.expire(anyString(), any(Duration.class))).thenReturn(false);
        ChatRunRateLimiter limiter = new ChatRunRateLimiter(redis);
        limiter.limit = 20;
        limiter.windowSeconds = 60;
        assertDoesNotThrow(() -> limiter.check(8L));
        org.mockito.Mockito.verify(redis).delete(ChatRunRateLimiter.CHAT_KEY_PREFIX + "8");
    }

    @Test
    void jobAndChatUseSeparateKeys()
    {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        java.util.List<String> keys = new java.util.ArrayList<>();
        when(values.increment(anyString())).thenAnswer(inv -> {
            keys.add(inv.getArgument(0));
            return 1L;
        });
        when(redis.getExpire(anyString())).thenReturn(60L);
        ChatRunRateLimiter limiter = new ChatRunRateLimiter(redis);
        limiter.limit = 20;
        limiter.jobLimit = 30;
        limiter.windowSeconds = 60;
        limiter.check(8L, false);
        limiter.check(8L, true);
        assertEquals(List.of(ChatRunRateLimiter.CHAT_KEY_PREFIX + "8",
                ChatRunRateLimiter.JOB_KEY_PREFIX + "8"), keys);
    }

    @Test
    void fillingChatQuota_doesNotBlockJob()
    {
        ChatRunRateLimiter limiter = limiter(21L);
        limiter.jobLimit = 30;
        assertThrows(ServiceException.class, () -> limiter.check(8L, false));
        assertDoesNotThrow(() -> limiter.check(8L, true));
    }

    private static ChatRunRateLimiter limiter(long redisCount)
    {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        AtomicLong n = new AtomicLong(redisCount);
        when(values.increment(anyString())).thenAnswer(inv -> n.get());
        when(redis.getExpire(anyString())).thenReturn(60L);
        when(redis.expire(anyString(), any(Duration.class))).thenReturn(true);
        ChatRunRateLimiter limiter = new ChatRunRateLimiter(redis);
        limiter.limit = 20;
        limiter.windowSeconds = 60;
        return limiter;
    }
}
