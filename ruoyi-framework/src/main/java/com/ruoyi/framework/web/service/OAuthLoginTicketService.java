package com.ruoyi.framework.web.service;

import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.ruoyi.common.constant.CacheConstants;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.utils.uuid.IdUtils;

/**
 * 将 OAuth 回调与 SPA 令牌交付解耦，避免把 JWT 置于回调 URL 中。
 */
@Component
public class OAuthLoginTicketService
{
    private static final int TICKET_EXPIRE_SECONDS = 60;

    @Autowired
    private RedisCache redisCache;

    public String create(String token)
    {
        String ticket = IdUtils.fastSimpleUUID();
        redisCache.setCacheObject(getKey(ticket), token, TICKET_EXPIRE_SECONDS, TimeUnit.SECONDS);
        return ticket;
    }

    /**
     * Redis GETDEL 保证票据只能兑换一次。
     */
    public String exchange(String ticket)
    {
        if (StringUtils.isEmpty(ticket))
        {
            throw new ServiceException("登录票据不能为空");
        }
        Object token = redisCache.redisTemplate.opsForValue().getAndDelete(getKey(ticket));
        if (!(token instanceof String) || StringUtils.isEmpty((String) token))
        {
            throw new ServiceException("登录票据无效或已过期");
        }
        return (String) token;
    }

    private String getKey(String ticket)
    {
        return CacheConstants.OAUTH_LOGIN_TICKET_KEY + ticket;
    }
}
