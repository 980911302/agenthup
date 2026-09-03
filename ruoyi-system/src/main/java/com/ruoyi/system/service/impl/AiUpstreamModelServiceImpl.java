package com.ruoyi.system.service.impl;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.EncryptUtils;
import com.ruoyi.system.ai.UpstreamModelClient;
import com.ruoyi.system.domain.AiChannel;
import com.ruoyi.system.domain.AiUpstreamModel;
import com.ruoyi.system.mapper.AiChannelMapper;
import com.ruoyi.system.mapper.AiUpstreamModelMapper;
import com.ruoyi.system.service.IAiUpstreamModelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 上游模型清单服务。两类渠道必须在后端守门,避免同步覆盖用户刚手工维护的数据。
 */
@Service
public class AiUpstreamModelServiceImpl implements IAiUpstreamModelService
{
    private static final Logger log = LoggerFactory.getLogger(AiUpstreamModelServiceImpl.class);

    @Autowired
    private AiUpstreamModelMapper mapper;
    @Autowired
    private AiChannelMapper aiChannelMapper;
    @Autowired
    private UpstreamModelClient upstreamModelClient;
    @Autowired
    private EncryptUtils encryptUtils;

    @Override
    public List<AiUpstreamModel> list(AiUpstreamModel query)
    {
        return mapper.selectList(query == null ? new AiUpstreamModel() : query);
    }

    /** 先拉取并校验非空,再在事务中覆盖,上游故障时绝不清空已有清单。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int syncFromUpstream(Long channelId, String operator)
    {
        AiChannel channel = mustGetChannel(channelId);
        if (isCustom(channel))
        {
            throw new ServiceException("渠道[" + channel.getChannelName() + "]是自定义渠道,不支持同步,请手动维护模型清单");
        }
        List<UpstreamModelClient.UpstreamModel> fetched = upstreamModelClient.fetchModels(
                channel, encryptUtils.decrypt(channel.getApiKey()));
        if (fetched == null || fetched.isEmpty())
        {
            throw new ServiceException("上游返回的模型列表为空,已保留原有清单。请检查渠道地址与密钥后重试");
        }
        Map<String, AiUpstreamModel> dedup = new LinkedHashMap<>();
        Date now = new Date();
        for (UpstreamModelClient.UpstreamModel upstream : fetched)
        {
            if (upstream == null || upstream.getId() == null || upstream.getId().trim().isEmpty())
            {
                continue;
            }
            String id = upstream.getId().trim();
            dedup.computeIfAbsent(id, key -> toSyncedModel(channelId, operator, now, key, upstream));
        }
        if (dedup.isEmpty())
        {
            throw new ServiceException("上游返回的模型列表为空,已保留原有清单");
        }
        mapper.deleteByChannelId(channelId);
        List<AiUpstreamModel> rows = new ArrayList<>(dedup.values());
        mapper.insertBatch(rows);
        log.info("渠道[{}]同步上游模型完成,共 {} 个", channel.getChannelName(), rows.size());
        return rows.size();
    }

    @Override
    public int addManual(AiUpstreamModel model, String operator)
    {
        if (model == null || model.getChannelId() == null)
        {
            throw new ServiceException("渠道不能为空");
        }
        AiChannel channel = mustGetChannel(model.getChannelId());
        if (!isCustom(channel))
        {
            throw new ServiceException("渠道[" + channel.getChannelName() + "]不是自定义渠道,模型清单由同步管理,请使用「同步模型」");
        }
        String id = model.getUpstreamModelId() == null ? "" : model.getUpstreamModelId().trim();
        if (id.isEmpty())
        {
            throw new ServiceException("模型标识不能为空");
        }
        if (mapper.selectByChannelAndModelId(model.getChannelId(), id) != null)
        {
            throw new ServiceException("该渠道下模型[" + id + "]已存在");
        }
        model.setUpstreamModelId(id);
        model.setDisplayName(blank(model.getDisplayName()) ? id : model.getDisplayName().trim());
        model.setSource(AiUpstreamModel.SOURCE_MANUAL);
        model.setCreateBy(operator);
        model.setCreateTime(new Date());
        return mapper.insertOne(model);
    }

    @Override
    public int update(AiUpstreamModel model, String operator)
    {
        if (model == null || model.getId() == null)
        {
            throw new ServiceException("主键不能为空");
        }
        model.setUpdateBy(operator);
        model.setUpdateTime(new Date());
        return mapper.updateOne(model);
    }

    @Override
    public int deleteByIds(Long[] ids)
    {
        if (ids == null || ids.length == 0)
        {
            return 0;
        }
        List<AiUpstreamModel> rows = mapper.selectByIds(ids);
        if (rows != null)
        {
            for (AiUpstreamModel row : rows)
            {
                assertManualChannel(row.getChannelId());
            }
        }
        return mapper.deleteByIds(ids);
    }

    @Override
    public int deleteByChannelId(Long channelId)
    {
        return channelId == null ? 0 : mapper.deleteByChannelId(channelId);
    }

    private AiUpstreamModel toSyncedModel(Long channelId, String operator, Date now, String id,
                                           UpstreamModelClient.UpstreamModel upstream)
    {
        AiUpstreamModel model = new AiUpstreamModel();
        model.setChannelId(channelId);
        model.setUpstreamModelId(id);
        model.setDisplayName(blank(upstream.getDisplayName()) ? id : upstream.getDisplayName().trim());
        model.setOwnedBy(upstream.getOwnedBy());
        // 上游给了就存真值,没给存 null —— 导入侧据此决定用真值还是回退推测
        model.setInputModalities(upstream.getInputModalities());
        model.setSource(AiUpstreamModel.SOURCE_SYNC);
        model.setCreateBy(operator);
        model.setCreateTime(now);
        return model;
    }

    private AiChannel mustGetChannel(Long channelId)
    {
        if (channelId == null) throw new ServiceException("渠道不能为空");
        AiChannel channel = aiChannelMapper.selectAiChannelById(channelId);
        if (channel == null) throw new ServiceException("渠道不存在或已删除");
        return channel;
    }

    private void assertManualChannel(Long channelId)
    {
        AiChannel channel = mustGetChannel(channelId);
        if (!isCustom(channel))
        {
            throw new ServiceException("渠道[" + channel.getChannelName()
                    + "]不是自定义渠道,模型清单由同步管理,不能手动修改或删除");
        }
    }

    private static boolean isCustom(AiChannel channel) { return "1".equals(channel.getIsCustom()); }
    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }
}
