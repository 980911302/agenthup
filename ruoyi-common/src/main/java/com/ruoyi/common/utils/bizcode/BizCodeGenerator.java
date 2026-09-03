package com.ruoyi.common.utils.bizcode;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.common.exception.ServiceException;

/**
 * 业务编码生成器
 *
 * <p>规则:{prefix}{yyyyMMdd}{4 位流水}
 * <ul>
 *   <li>例:2026-08-02 当天第 1 个渠道 → {@code CH202608020001}</li>
 *   <li>前缀区分模块:CH(渠道)/ MDL(模型)/ SKL(技能)/ MCP(MCP 服务)/ AGT(智能体)</li>
 *   <li>每天 0001-9999,容量 9999/天/模块</li>
 *   <li>基于 Redis INCR 原子自增,key 格式 {@code ai:code:{prefix}:{yyyyMMdd}}</li>
 *   <li>首次 INCR 时设置 26 小时过期(覆盖跨时区场景)</li>
 * </ul>
 *
 * <p>前后端约定:
 * <ul>
 *   <li>前端打开"新增"表单时调 {@code GET /ai/{module}/genCode} 预览编码(只读回显)</li>
 *   <li>提交时仍由后端生成,若前端未传则用 {@link #next(String)} 自填,避免脏写</li>
 * </ul>
 *
 * @author mavis
 */
@Component
public class BizCodeGenerator
{
    private static final Logger log = LoggerFactory.getLogger(BizCodeGenerator.class);

    /** 日期格式 */
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 单日最大流水(4 位) */
    private static final int MAX_SEQ = 9999;

    /** key 前缀 */
    private static final String KEY_PREFIX = "ai:code:";

    /** 过期秒数:26 小时(覆盖跨时区) */
    private static final long EXPIRE_SECONDS = 26 * 3600;

    @Autowired
    private RedisCache redisCache;

    /**
     * 生成下一个业务编码
     *
     * @param prefix 业务前缀,如 {@code "CH"}/{@code "MDL"}/{@code "SKL"}/{@code "MCP"}/{@code "AGT"}
     * @return 完整编码,如 {@code "CH202608020001"}
     * @throws ServiceException 当日流水超出 9999 或 Redis 不可用
     */
    public String next(String prefix)
    {
        if (prefix == null || prefix.isEmpty())
        {
            throw new ServiceException("业务编码前缀不能为空");
        }
        String today = LocalDate.now().format(DATE_FMT);
        String key = KEY_PREFIX + prefix + ":" + today;
        try
        {
            Long seq = redisCache.redisTemplate.opsForValue().increment(key);
            if (seq == null)
            {
                throw new ServiceException("业务编码生成失败:Redis 自增返回空");
            }
            if (seq == 1L)
            {
                // 第一次自增时设置过期,避免历史 key 无限累积
                redisCache.expire(key, EXPIRE_SECONDS);
            }
            if (seq > MAX_SEQ)
            {
                log.error("业务编码[{}]当日流水已达上限: seq={}", prefix, seq);
                throw new ServiceException("业务编码[" + prefix + "]当日流水已达上限(" + MAX_SEQ + "),请次日再试");
            }
            return prefix + today + String.format("%04d", seq);
        }
        catch (ServiceException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            log.error("业务编码生成异常: prefix={}, key={}", prefix, key, e);
            throw new ServiceException("业务编码生成失败,请稍后重试");
        }
    }
}
