package com.ruoyi.system.ai.run;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.ruoyi.common.exception.ServiceException;

/**
 * 按 userId 限制 {@code POST /ai/chat/run} / {@code chat.run.create} 的创建速率。
 * <p>REST 与 WebSocket 共用 {@link ChatRunService#create},在这里闸一次即可。
 * Redis 故障时放行,避免把对话入口打挂。
 */
@Component
public class ChatRunRateLimiter
{
    private static final Logger log = LoggerFactory.getLogger(ChatRunRateLimiter.class);
    static final String CHAT_KEY_PREFIX = "ai:chat:run:rate:";
    static final String JOB_KEY_PREFIX = "ai:job:run:rate:";

    private final StringRedisTemplate redis;

    @Value("${ai.chat.run.create-rate-limit:20}")
    int limit;

    @Value("${ai.chat.run.job-create-rate-limit:30}")
    int jobLimit;

    @Value("${ai.chat.run.create-rate-window-seconds:60}")
    int windowSeconds;

    public ChatRunRateLimiter(StringRedisTemplate redis)
    {
        this.redis = redis;
    }

    public void check(Long userId)
    {
        check(userId, false);
    }

    /**
     * @param job true 时走定时任务独立配额,避免 Job 把交互对话挤掉
     */
    public void check(Long userId, boolean job)
    {
        int cap = job ? jobLimit : limit;
        if (userId == null || cap <= 0)
        {
            return;
        }
        String key = (job ? JOB_KEY_PREFIX : CHAT_KEY_PREFIX) + userId;
        try
        {
            Long n = redis.opsForValue().increment(key);
            if (n == null)
            {
                return;
            }
            Long ttl = redis.getExpire(key);
            if (ttl == null || ttl < 0)
            {
                Boolean expired = redis.expire(key, Duration.ofSeconds(Math.max(1, windowSeconds)));
                if (!Boolean.TRUE.equals(expired))
                {
                    redis.delete(key);
                    log.warn("对话创建限流未能设置 TTL,已删除计数: userId={}", userId);
                    return;
                }
            }
            if (n > cap)
            {
                throw new ServiceException(job
                        ? "定时任务创建过于频繁，请稍候再试"
                        : "对话创建过于频繁，请稍候再试");
            }
        }
        catch (ServiceException e)
        {
            throw e;
        }
        catch (RuntimeException e)
        {
            log.warn("对话创建限流检查失败,放行: userId={}: {}", userId, e.getMessage());
        }
    }
}
