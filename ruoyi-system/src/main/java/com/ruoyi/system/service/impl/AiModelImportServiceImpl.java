package com.ruoyi.system.service.impl;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.EncryptUtils;
import com.ruoyi.system.ai.ModelRecommender;
import com.ruoyi.system.ai.agent.ModelInputModalities;
import com.ruoyi.system.ai.UpstreamModelClient;
import com.ruoyi.system.domain.AiChannel;
import com.ruoyi.system.domain.AiModel;
import com.ruoyi.system.domain.AiModelChannel;
import com.ruoyi.system.domain.AiUpstreamModel;
import com.ruoyi.system.domain.dto.AiModelImportDto;
import com.ruoyi.system.domain.vo.UpstreamModelVo;
import com.ruoyi.system.mapper.AiChannelMapper;
import com.ruoyi.system.mapper.AiModelChannelMapper;
import com.ruoyi.system.mapper.AiModelMapper;
import com.ruoyi.system.mapper.AiUpstreamModelMapper;
import com.ruoyi.system.service.IAiModelChannelService;
import com.ruoyi.system.service.IAiModelImportService;

/**
 * 模型导入 服务层实现
 *
 * @author ruoyi
 */
@Service
public class AiModelImportServiceImpl implements IAiModelImportService
{
    /** 导入状态:未导入 */
    public static final String STATUS_NOT_IMPORTED = "NOT_IMPORTED";

    /** 导入状态:平台已建模型(可新增供应) */
    public static final String STATUS_MODEL_EXISTS = "MODEL_EXISTS";

    /** 导入状态:本渠道已接入(无需操作) */
    public static final String STATUS_CHANNEL_BOUND = "CHANNEL_BOUND";

    @Autowired
    private AiChannelMapper aiChannelMapper;

    @Autowired
    private AiModelMapper aiModelMapper;

    @Autowired
    private AiModelChannelMapper aiModelChannelMapper;

    @Autowired
    private IAiModelChannelService aiModelChannelService;

    @Autowired
    private EncryptUtils encryptUtils;

    @Autowired
    private UpstreamModelClient upstreamModelClient;

    @Autowired
    private AiUpstreamModelMapper aiUpstreamModelMapper;

    /**
     * 从落库清单查询上游模型,合并平台内 modelCode / 绑定关系得出导入状态。
     * 打开弹窗不再依赖网络,清单的新鲜度由渠道同步或手动维护负责。
     */
    @Override
    public List<UpstreamModelVo> listUpstreamModels(Long channelId)
    {
        mustGetChannel(channelId);
        AiUpstreamModel upstreamQuery = new AiUpstreamModel();
        upstreamQuery.setChannelId(channelId);
        List<AiUpstreamModel> upstreamModels = aiUpstreamModelMapper.selectList(upstreamQuery);

        // 平台已有模型:modelCode -> modelId
        Map<String, Long> existingModels = aiModelMapper.selectAiModelList(new AiModel()).stream()
            .collect(Collectors.toMap(AiModel::getModelCode, AiModel::getModelId, (a, b) -> a));
        // 本渠道已供应的模型 ID
        AiModelChannel query = new AiModelChannel();
        query.setChannelId(channelId);
        Set<Long> boundModelIds = new HashSet<>(aiModelChannelMapper.selectAiModelChannelList(query).stream()
            .map(AiModelChannel::getModelId)
            .collect(Collectors.toList()));

        Map<String, UpstreamModelVo> dedup = new HashMap<>();
        for (AiUpstreamModel m : upstreamModels)
        {
            UpstreamModelVo vo = dedup.computeIfAbsent(m.getUpstreamModelId(), id -> {
                UpstreamModelVo v = new UpstreamModelVo();
                v.setUpstreamModelId(id);
                v.setDisplayName(m.getDisplayName());
                v.setRecommendType(ModelRecommender.recommendType(id));
                v.setCapabilities(ModelRecommender.recommendCapabilities(id, v.getRecommendType()));
                v.setOwnedBy(m.getOwnedBy());
                v.setInputModalities(m.getInputModalities());
                return v;
            });
            Long modelId = existingModels.get(m.getUpstreamModelId());
            if (modelId == null)
            {
                vo.setImportStatus(STATUS_NOT_IMPORTED);
            }
            else
            {
                vo.setExistingModelId(modelId);
                vo.setImportStatus(boundModelIds.contains(modelId) ? STATUS_CHANNEL_BOUND : STATUS_MODEL_EXISTS);
            }
        }
        // 未导入的排前面,其次已建模型,已接入垫底
        return dedup.values().stream()
            .sorted((a, b) -> {
                int cmp = Integer.compare(statusOrder(a.getImportStatus()), statusOrder(b.getImportStatus()));
                return cmp != 0 ? cmp : a.getUpstreamModelId().compareToIgnoreCase(b.getUpstreamModelId());
            })
            .collect(Collectors.toList());
    }

    /**
     * 导入:模型不存在则建模型,随后建渠道绑定(已存在绑定则拒绝)
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public String importModel(AiModelImportDto dto, String operator)
    {
        AiChannel channel = mustGetChannel(dto.getChannelId());
        if ("1".equals(channel.getStatus()))
        {
            throw new ServiceException("渠道[" + channel.getChannelName() + "]已停用,请先启用");
        }
        String modelCode = dto.getModelCode() == null ? "" : dto.getModelCode().trim();
        if (modelCode.isEmpty())
        {
            throw new ServiceException("模型编码不能为空");
        }

        // 含软删:删了再导入同 code 时复活,避免撞 uk_model_code
        AiModel existing = aiModelMapper.selectAiModelByCodeIncludeDeleted(modelCode);
        AiModel model;
        boolean newModel;
        if (existing == null)
        {
            model = buildModel(dto, modelCode, operator);
            aiModelMapper.insertAiModel(model);
            newModel = true;
        }
        else if ("2".equals(existing.getDelFlag()))
        {
            model = buildModel(dto, modelCode, operator);
            model.setModelId(existing.getModelId());
            aiModelMapper.reactivateAiModel(model);
            // 历史软删模型可能残留渠道绑定(列表看不到),重导前先清掉,交给 saveBinding 重建
            aiModelChannelMapper.deleteByModelIds(
                java.util.Collections.singletonList(model.getModelId()));
            newModel = true;
        }
        else
        {
            model = existing;
            newModel = false;
        }

        // 绑定判重 + 软删除复活:统一走 saveBinding,避免删除后重新添加撞唯一约束
        AiModelChannel binding = new AiModelChannel();
        binding.setModelId(model.getModelId());
        binding.setChannelId(channel.getChannelId());
        // modelName 缺省用 modelCode(与绑定模块约定一致,Azure 等特殊场景可后续改)
        binding.setModelName(dto.getModelName() == null || dto.getModelName().trim().isEmpty()
            ? modelCode : dto.getModelName().trim());
        binding.setInputPrice(dto.getInputPrice());
        binding.setOutputPrice(dto.getOutputPrice());
        binding.setWeight(dto.getWeight() == null ? 1 : dto.getWeight());
        binding.setRetryCount(dto.getRetryCount() == null ? 0 : dto.getRetryCount());
        binding.setStatus("0");
        aiModelChannelService.saveBinding(binding, operator);

        return newModel ? "导入成功,已创建模型并接入渠道" : "模型已存在,已新增渠道供应";
    }

    /**
     * 由导入参数构建新模型(校验必填,应用缺省值)
     */
    private AiModel buildModel(AiModelImportDto dto, String modelCode, String operator)
    {
        if (dto.getModelType() == null || dto.getModelType().trim().isEmpty())
        {
            throw new ServiceException("模型类型不能为空");
        }
        if (dto.getContextWindow() == null || dto.getContextWindow() <= 0)
        {
            throw new ServiceException("上下文长度不能为空");
        }
        AiModel model = new AiModel();
        model.setModelCode(modelCode);
        model.setDisplayName(dto.getDisplayName() == null || dto.getDisplayName().trim().isEmpty()
            ? modelCode : dto.getDisplayName().trim());
        model.setModelType(dto.getModelType().trim());
        model.setContextWindow(dto.getContextWindow());
        model.setMaxOutputTokens(dto.getMaxOutputTokens());
        model.setReasoningEnabled(dto.getReasoningEnabled() == null || dto.getReasoningEnabled().isEmpty()
            ? "0" : dto.getReasoningEnabled());
        // 缺省按纯文本:模型收不下的媒体会被上游 400 打回,宁可少发
        ModelInputModalities modalities = ModelInputModalities.parse(dto.getInputModalities());
        model.setInputModalities(modalities.asStored());
        // 同步写旧列,让迁移期内直接读 vision_enabled 的地方(存量报表/外部查询)保持一致
        model.setVisionEnabled(modalities.imageEnabled() ? "1" : "0");
        model.setSort(dto.getSort() == null ? 0 : dto.getSort());
        model.setStatus("0");
        model.setRemark(dto.getRemark());
        model.setCreateBy(operator);
        return model;
    }

    private AiChannel mustGetChannel(Long channelId)
    {
        if (channelId == null)
        {
            throw new ServiceException("渠道不能为空");
        }
        AiChannel channel = aiChannelMapper.selectAiChannelById(channelId);
        if (channel == null)
        {
            throw new ServiceException("渠道不存在或已删除");
        }
        return channel;
    }

    private int statusOrder(String importStatus)
    {
        if (STATUS_NOT_IMPORTED.equals(importStatus))
        {
            return 0;
        }
        if (STATUS_MODEL_EXISTS.equals(importStatus))
        {
            return 1;
        }
        return 2;
    }
}
