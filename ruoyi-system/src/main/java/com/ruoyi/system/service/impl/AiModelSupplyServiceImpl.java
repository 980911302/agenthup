package com.ruoyi.system.service.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.AiChannel;
import com.ruoyi.system.domain.AiModel;
import com.ruoyi.system.domain.AiModelChannel;
import com.ruoyi.system.domain.AiUpstreamModel;
import com.ruoyi.system.domain.vo.SupplyCandidateResult;
import com.ruoyi.system.domain.vo.SupplyCandidateVo;
import com.ruoyi.system.domain.vo.SupplyExcludedVo;
import com.ruoyi.system.mapper.AiChannelMapper;
import com.ruoyi.system.mapper.AiModelChannelMapper;
import com.ruoyi.system.mapper.AiModelMapper;
import com.ruoyi.system.mapper.AiUpstreamModelMapper;
import com.ruoyi.system.service.IAiModelChannelService;
import com.ruoyi.system.service.IAiModelSupplyService;

/**
 * 模型供应 服务层实现
 *
 * @author ruoyi
 */
@Service
public class AiModelSupplyServiceImpl implements IAiModelSupplyService
{
    @Autowired
    private AiModelMapper aiModelMapper;

    @Autowired
    private AiChannelMapper aiChannelMapper;

    @Autowired
    private AiModelChannelMapper aiModelChannelMapper;

    @Autowired
    private IAiModelChannelService aiModelChannelService;

    @Autowired
    private AiUpstreamModelMapper aiUpstreamModelMapper;

    @Override
    public List<AiModelChannel> listByModelId(Long modelId)
    {
        mustGetModel(modelId);
        AiModelChannel query = new AiModelChannel();
        query.setModelId(modelId);
        List<AiModelChannel> list = aiModelChannelMapper.selectAiModelChannelList(query);
        markUpstreamMissing(list);
        return list;
    }

    @Override
    public SupplyCandidateResult listCandidateChannels(Long modelId)
    {
        AiModel model = mustGetModel(modelId);
        String modelCode = model.getModelCode();
        SupplyCandidateResult result = new SupplyCandidateResult();

        AiModelChannel boundQuery = new AiModelChannel();
        boundQuery.setModelId(modelId);
        Set<Long> boundChannelIds = aiModelChannelMapper.selectAiModelChannelList(boundQuery).stream()
            .map(AiModelChannel::getChannelId)
            .collect(Collectors.toCollection(HashSet::new));

        AiChannel channelQuery = new AiChannel();
        channelQuery.setStatus("0");
        List<AiChannel> unbound = aiChannelMapper.selectAiChannelList(channelQuery).stream()
            .filter(c -> !boundChannelIds.contains(c.getChannelId()))
            .collect(Collectors.toList());
        if (unbound.isEmpty())
        {
            return result;
        }

        List<Long> offeringIds = aiUpstreamModelMapper.selectChannelIdsByUpstreamModelId(modelCode);
        Set<Long> offering = new HashSet<>(offeringIds == null ? List.of() : offeringIds);
        List<Long> channelIds = unbound.stream().map(AiChannel::getChannelId).collect(Collectors.toList());
        Map<Long, Long> counts = new HashMap<>();
        for (Map<String, Object> row : aiUpstreamModelMapper.countByChannelIds(channelIds))
        {
            Object channelId = row.get("channelId");
            Object count = row.get("cnt");
            if (channelId instanceof Number && count instanceof Number)
            {
                counts.put(((Number) channelId).longValue(), ((Number) count).longValue());
            }
        }

        List<SupplyCandidateVo> ok = new ArrayList<>();
        List<SupplyExcludedVo> excluded = new ArrayList<>();
        for (AiChannel channel : unbound)
        {
            if (offering.contains(channel.getChannelId()))
            {
                SupplyCandidateVo vo = new SupplyCandidateVo();
                vo.setChannelId(channel.getChannelId());
                vo.setChannelName(channel.getChannelName());
                vo.setChannelCode(channel.getChannelCode());
                vo.setChannelType(channel.getChannelType());
                vo.setHealthStatus(channel.getHealthStatus());
                ok.add(vo);
            }
            else
            {
                SupplyExcludedVo ex = new SupplyExcludedVo();
                ex.setChannelId(channel.getChannelId());
                ex.setChannelName(channel.getChannelName());
                ex.setChannelCode(channel.getChannelCode());
                if (counts.getOrDefault(channel.getChannelId(), 0L) == 0L)
                {
                    ex.setReasonType("not_synced");
                    ex.setReason("该渠道尚未同步模型清单,请先在渠道管理中同步或手动添加");
                }
                else
                {
                    ex.setReasonType("missing_model");
                    ex.setReason("该渠道的模型清单中没有 " + modelCode);
                }
                excluded.add(ex);
            }
        }
        ok.sort((a, b) -> String.valueOf(a.getChannelName()).compareToIgnoreCase(String.valueOf(b.getChannelName())));
        excluded.sort((a, b) -> String.valueOf(a.getChannelName()).compareToIgnoreCase(String.valueOf(b.getChannelName())));
        result.setCandidates(ok);
        result.setExcluded(excluded);
        return result;
    }

    @Override
    public int addSupply(AiModelChannel binding, String operator)
    {
        if (binding.getModelId() == null)
        {
            throw new ServiceException("模型不能为空");
        }
        if (binding.getChannelId() == null)
        {
            throw new ServiceException("渠道不能为空");
        }
        AiModel model = mustGetModel(binding.getModelId());
        AiChannel channel = aiChannelMapper.selectAiChannelById(binding.getChannelId());
        if (channel == null)
        {
            throw new ServiceException("渠道不存在或已删除");
        }
        if ("1".equals(channel.getStatus()))
        {
            throw new ServiceException("渠道[" + channel.getChannelName() + "]已停用,请先启用");
        }

        String modelName = blankToDefault(binding.getModelName(), null);
        if (modelName == null)
        {
            throw new ServiceException("请选择渠道模型");
        }
        assertChannelModelExists(channel.getChannelId(), modelName);

        AiModelChannel entity = new AiModelChannel();
        entity.setModelId(model.getModelId());
        entity.setChannelId(channel.getChannelId());
        entity.setModelName(modelName);
        entity.setInputPrice(binding.getInputPrice());
        entity.setOutputPrice(binding.getOutputPrice());
        entity.setWeight(binding.getWeight() == null ? 1 : binding.getWeight());
        entity.setRetryCount(binding.getRetryCount() == null ? 0 : binding.getRetryCount());
        entity.setStatus(blankToDefault(binding.getStatus(), "0"));
        entity.setRemark(binding.getRemark());
        return aiModelChannelService.saveBinding(entity, operator);
    }

    @Override
    public int updateSupply(AiModelChannel binding, String operator)
    {
        if (binding.getId() == null)
        {
            throw new ServiceException("供应记录不能为空");
        }
        AiModelChannel existing = aiModelChannelMapper.selectAiModelChannelById(binding.getId());
        if (existing == null)
        {
            throw new ServiceException("供应记录不存在或已删除");
        }
        AiModelChannel update = new AiModelChannel();
        String modelName = blankToDefault(binding.getModelName(), null);
        if (modelName == null)
        {
            throw new ServiceException("请选择渠道模型");
        }
        // 已经下架的旧绑定仍允许只改权重/价格；仅在切换调用标识时校验新选择。
        if (!modelName.equals(existing.getModelName()))
        {
            assertChannelModelExists(existing.getChannelId(), modelName);
        }
        update.setId(binding.getId());
        update.setModelName(modelName);
        update.setInputPrice(binding.getInputPrice());
        update.setOutputPrice(binding.getOutputPrice());
        update.setWeight(binding.getWeight());
        update.setRetryCount(binding.getRetryCount());
        update.setStatus(binding.getStatus());
        update.setRemark(binding.getRemark());
        update.setUpdateBy(operator);
        return aiModelChannelMapper.updateAiModelChannel(update);
    }

    @Override
    public int removeSupply(Long[] ids)
    {
        if (ids == null || ids.length == 0)
        {
            return 0;
        }
        return aiModelChannelMapper.deleteAiModelChannelByIds(ids);
    }

    /** 只标记不修改绑定,上游短暂异常不应破坏线上供应配置。 */
    private void markUpstreamMissing(List<AiModelChannel> supplies)
    {
        if (supplies == null || supplies.isEmpty()) return;
        List<Long> channelIds = supplies.stream().map(AiModelChannel::getChannelId)
                .filter(java.util.Objects::nonNull).distinct().collect(Collectors.toList());
        if (channelIds.isEmpty()) return;
        Map<Long, Set<String>> available = new HashMap<>();
        for (AiUpstreamModel model : aiUpstreamModelMapper.selectByChannelIds(channelIds))
        {
            available.computeIfAbsent(model.getChannelId(), key -> new HashSet<>()).add(model.getUpstreamModelId());
        }
        for (AiModelChannel supply : supplies)
        {
            Set<String> names = available.get(supply.getChannelId());
            supply.setUpstreamMissing(names == null || !names.contains(supply.getModelName()));
        }
    }

    private AiModel mustGetModel(Long modelId)
    {
        if (modelId == null)
        {
            throw new ServiceException("模型不能为空");
        }
        AiModel model = aiModelMapper.selectAiModelById(modelId);
        if (model == null)
        {
            throw new ServiceException("模型不存在或已删除");
        }
        return model;
    }

    private String blankToDefault(String value, String defaultValue)
    {
        return value == null || value.trim().isEmpty() ? defaultValue : value.trim();
    }

    /** 供应只能引用该渠道已维护的模型清单，不能再手填或实时探测上游。 */
    private void assertChannelModelExists(Long channelId, String modelName)
    {
        if (aiUpstreamModelMapper.selectByChannelAndModelId(channelId, modelName) == null)
        {
            throw new ServiceException("渠道模型[" + modelName + "]不存在,请先在渠道管理中同步或维护模型清单");
        }
    }

}
