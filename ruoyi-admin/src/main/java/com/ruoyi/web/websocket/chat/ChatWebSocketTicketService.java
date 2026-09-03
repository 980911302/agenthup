package com.ruoyi.web.websocket.chat;

import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/** 生成并原子消费短时一次性 WebSocket 票据，避免把长期 JWT 放进 URL。 */
@Service
public class ChatWebSocketTicketService
{
    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketTicketService.class);
    private static final String KEY_PREFIX = "ai:chat:ws-ticket:";
    public static final long TTL_SECONDS = 60;

    private final StringRedisTemplate redisTemplate;

    public ChatWebSocketTicketService(StringRedisTemplate redisTemplate)
    {
        this.redisTemplate = redisTemplate;
    }

    public String issue(Long userId, String username, boolean admin)
    {
        String ticket = UUID.randomUUID().toString().replace("-", "");
        ChatWebSocketIdentity identity = new ChatWebSocketIdentity(
                userId, username, admin, System.currentTimeMillis() + TTL_SECONDS * 1000L);
        try
        {
            redisTemplate.opsForValue().set(KEY_PREFIX + ticket,
                    JSON.toJSONString(identity), Duration.ofSeconds(TTL_SECONDS));
            return ticket;
        }
        catch (RuntimeException e)
        {
            log.error("生成 WebSocket 一次性票据失败", e);
            throw new ServiceException("实时连接暂不可用，请稍后重试");
        }
    }

    public ChatWebSocketIdentity consume(String ticket)
    {
        if (ticket == null || ticket.length() != 32)
        {
            return null;
        }
        try
        {
            String payload = redisTemplate.opsForValue().getAndDelete(KEY_PREFIX + ticket);
            if (payload == null) return null;
            ChatWebSocketIdentity identity = JSON.parseObject(payload, ChatWebSocketIdentity.class);
            if (identity == null || identity.getUserId() == null
                    || identity.getExpiresAt() < System.currentTimeMillis())
            {
                return null;
            }
            return identity;
        }
        catch (RuntimeException e)
        {
            log.warn("消费 WebSocket 票据失败", e);
            return null;
        }
    }
}

