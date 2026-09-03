package com.ruoyi.system.service.impl;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.EncryptUtils;
import com.ruoyi.common.utils.bizcode.BizCodeGenerator;
import com.ruoyi.system.ai.AiChannelChangedEvent;
import com.ruoyi.system.domain.AiChannel;
import com.ruoyi.system.domain.vo.AiChannelVo;
import com.ruoyi.system.mapper.AiChannelMapper;
import com.ruoyi.system.mapper.AiModelChannelMapper;
import com.ruoyi.system.mapper.AiUpstreamModelMapper;
import com.ruoyi.system.service.IAiChannelService;

/**
 * 上游渠道 服务层实现
 * 
 * @author ruoyi
 */
@Service
public class AiChannelServiceImpl implements IAiChannelService
{
    private static final Logger log = LoggerFactory.getLogger(AiChannelServiceImpl.class);

    /** 连续失败达到该次数判定渠道异常 */
    private static final int FAIL_THRESHOLD = 3;

    @Autowired
    private AiChannelMapper aiChannelMapper;

    @Autowired
    private EncryptUtils encryptUtils;

    @Autowired
    private BizCodeGenerator bizCodeGenerator;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private AiModelChannelMapper aiModelChannelMapper;

    @Autowired
    private AiUpstreamModelMapper aiUpstreamModelMapper;

    /**
     * 查询渠道信息(密文,内部使用)
     */
    @Override
    public AiChannel selectAiChannelById(Long channelId)
    {
        return aiChannelMapper.selectAiChannelById(channelId);
    }

    /**
     * 查询渠道列表(密文,内部使用,如定时探活)
     */
    @Override
    public List<AiChannel> selectAiChannelList(AiChannel aiChannel)
    {
        return aiChannelMapper.selectAiChannelList(aiChannel);
    }

    /**
     * 查询渠道列表(脱敏,前端展示)
     */
    @Override
    public List<AiChannelVo> selectAiChannelListMasked(AiChannel aiChannel)
    {
        return aiChannelMapper.selectAiChannelList(aiChannel).stream()
            .map(c -> AiChannelVo.from(c, encryptUtils.mask(encryptUtils.decrypt(c.getApiKey()))))
            .collect(Collectors.toList());
    }

    /**
     * 查询渠道详情(脱敏)
     */
    @Override
    public AiChannelVo selectAiChannelVoById(Long channelId)
    {
        AiChannel c = aiChannelMapper.selectAiChannelById(channelId);
        if (c == null)
        {
            return null;
        }
        return AiChannelVo.from(c, encryptUtils.mask(encryptUtils.decrypt(c.getApiKey())));
    }

    /**
     * 新增渠道(apiKey 入库前加密,channelCode 为空时自动生成)
     */
    @Override
    public int insertAiChannel(AiChannel aiChannel)
    {
        // 兼容旧客户端不传该字段；数据库虽有默认值，但“复活”分支会直接执行 update。
        if (!"1".equals(aiChannel.getIsCustom()))
        {
            aiChannel.setIsCustom("0");
        }
        if (aiChannel.getChannelCode() == null || aiChannel.getChannelCode().isEmpty())
        {
            aiChannel.setChannelCode(bizCodeGenerator.next("CH"));
        }
        aiChannel.setApiKey(encryptUtils.encrypt(aiChannel.getApiKey()));
        aiChannel.setHealthStatus("0");
        aiChannel.setHealthFailCount(0);
        // 软删后同 code 重建:复活旧行,避免撞 uk_channel_code
        AiChannel existing = aiChannelMapper.selectAiChannelByCodeIncludeDeleted(aiChannel.getChannelCode());
        if (existing != null)
        {
            if (!"2".equals(existing.getDelFlag()))
            {
                throw new ServiceException("渠道编码已存在: " + aiChannel.getChannelCode());
            }
            aiChannel.setChannelId(existing.getChannelId());
            return aiChannelMapper.reactivateAiChannel(aiChannel);
        }
        return aiChannelMapper.insertAiChannel(aiChannel);
    }

    /**
     * 修改渠道(apiKey 入库前加密)
     *
     * <p>baseUrl / apiKey 在 ChatModel 构造时就被烧进了客户端实例，改库不会影响已建实例，
     * 所以这里必须广播失效 —— 否则改完地址仍旧打向旧上游，只能靠重启后端才生效。</p>
     */
    @Override
    public int updateAiChannel(AiChannel aiChannel)
    {
        if (aiChannel.getApiKey() != null && !aiChannel.getApiKey().isEmpty())
        {
            aiChannel.setApiKey(encryptUtils.encrypt(aiChannel.getApiKey()));
        }
        int rows = aiChannelMapper.updateAiChannel(aiChannel);
        if (rows > 0)
        {
            publishChannelChanged(aiChannel.getChannelId());
        }
        return rows;
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public int deleteAiChannelById(Long channelId)
    {
        int rows = aiChannelMapper.deleteAiChannelById(channelId);
        if (rows > 0)
        {
            // 物理删除后连带清理该渠道的模型绑定,避免悬空
            aiModelChannelMapper.deleteByChannelIds(java.util.Collections.singletonList(channelId));
            aiUpstreamModelMapper.deleteByChannelId(channelId);
            publishChannelChanged(channelId);
        }
        return rows;
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public int deleteAiChannelByIds(Long[] channelIds)
    {
        int rows = aiChannelMapper.deleteAiChannelByIds(channelIds);
        if (rows > 0 && channelIds != null)
        {
            // 物理删除后连带清理这批渠道的模型绑定
            aiModelChannelMapper.deleteByChannelIds(java.util.Arrays.asList(channelIds));
            aiUpstreamModelMapper.deleteByChannelIds(java.util.Arrays.asList(channelIds));
            for (Long channelId : channelIds)
            {
                publishChannelChanged(channelId);
            }
        }
        return rows;
    }

    /** 缓存失效属于尽力而为的旁路，广播失败不能让渠道本身的修改回滚。 */
    private void publishChannelChanged(Long channelId)
    {
        if (channelId == null) return;
        try
        {
            eventPublisher.publishEvent(new AiChannelChangedEvent(channelId));
        }
        catch (RuntimeException e)
        {
            log.warn("广播渠道变更事件失败，模型缓存可能仍持旧配置: channelId={}", channelId, e);
        }
    }

    /**
     * 手动触发健康检查:解密 apiKey 后请求 base_url + health_check_uri 探活,
     * 连续失败达到阈值(3次)置为异常,恢复成功自动回正
     */
    @Override
    public int checkHealth(Long channelId)
    {
        AiChannel channel = aiChannelMapper.selectAiChannelById(channelId);
        if (channel == null)
        {
            return 0;
        }
        // 探活时使用解密后的密钥
        AiChannel probe = new AiChannel();
        probe.setBaseUrl(channel.getBaseUrl());
        probe.setApiKey(encryptUtils.decrypt(channel.getApiKey()));
        probe.setHealthCheckUri(channel.getHealthCheckUri());
        probe.setChannelName(channel.getChannelName());

        boolean ok = doProbe(probe);
        AiChannel update = new AiChannel();
        update.setChannelId(channelId);
        update.setHealthCheckTime(new Date());
        if (ok)
        {
            update.setHealthStatus("1");
            update.setHealthFailCount(0);
        }
        else
        {
            int fail = (channel.getHealthFailCount() == null ? 0 : channel.getHealthFailCount()) + 1;
            update.setHealthFailCount(fail);
            update.setHealthStatus(fail >= FAIL_THRESHOLD ? "2" : "1");
        }
        aiChannelMapper.updateAiChannel(update);
        log.info("渠道[{}]健康检查完成: {}", channel.getChannelName(), ok ? "正常" : "异常");
        return ok ? 1 : 2;
    }

    /**
     * 探活请求
     */
    private boolean doProbe(AiChannel channel)
    {
        try
        {
            String base = channel.getBaseUrl();
            if (base == null || base.isEmpty())
            {
                return false;
            }
            if (!base.endsWith("/"))
            {
                base = base + "/";
            }
            String uri = channel.getHealthCheckUri();
            if (uri == null || uri.isEmpty())
            {
                uri = "/models";
            }
            if (!uri.startsWith("/"))
            {
                uri = "/" + uri;
            }
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + uri.substring(1)))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + (channel.getApiKey() == null ? "" : channel.getApiKey()))
                .GET()
                .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        }
        catch (Exception e)
        {
            log.debug("渠道[{}]探活失败: {}", channel.getChannelName(), e.getMessage());
            return false;
        }
    }
}
