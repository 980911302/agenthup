package com.ruoyi.system.service.impl;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import com.ruoyi.system.ai.agent.ModelInputModalities;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.bizcode.BizCodeGenerator;
import com.ruoyi.system.ai.AiConfigChangedEvent;
import com.ruoyi.system.domain.AiModel;
import com.ruoyi.system.mapper.AiLlmCallMapper;
import com.ruoyi.system.mapper.AiModelChannelMapper;
import com.ruoyi.system.mapper.AiModelMapper;
import com.ruoyi.system.service.IAiModelService;

/**
 * 模型 服务层实现
 *
 * @author ruoyi
 */
@Service
public class AiModelServiceImpl implements IAiModelService
{
    @Autowired
    private AiModelMapper aiModelMapper;

    @Autowired
    private AiModelChannelMapper aiModelChannelMapper;

    @Autowired
    private AiLlmCallMapper aiLlmCallMapper;

    @Autowired
    private BizCodeGenerator bizCodeGenerator;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    /**
     * 查询模型信息
     * 
     * @param modelId 模型ID
     * @return 模型信息
     */
    @Override
    public AiModel selectAiModelById(Long modelId)
    {
        return aiModelMapper.selectAiModelById(modelId);
    }

    /**
     * 按 modelCode 查询模型(AiAgent.modelCode 是字符串,装配时需转成 modelId)
     * 委托已有的 mapper 方法 selectAiModelByModelCode
     */
    @Override
    public AiModel selectByModelCode(String modelCode)
    {
        return aiModelMapper.selectAiModelByModelCode(modelCode);
    }

    /**
     * 查询模型列表
     * 
     * @param aiModel 模型信息
     * @return 模型集合
     */
    @Override
    public List<AiModel> selectAiModelList(AiModel aiModel)
    {
        return aiModelMapper.selectAiModelList(aiModel);
    }

    /**
     * 新增模型(modelCode 为空时自动生成)
     */
    @Override
    public int insertAiModel(AiModel aiModel)
    {
        if (aiModel.getModelCode() == null || aiModel.getModelCode().isEmpty())
        {
            aiModel.setModelCode(bizCodeGenerator.next("MDL"));
        }
        syncLegacyVisionFlag(aiModel);
        // 软删后同 code 重建:复活旧行,避免撞 uk_model_code
        AiModel existing = aiModelMapper.selectAiModelByCodeIncludeDeleted(aiModel.getModelCode());
        if (existing != null)
        {
            if (!"2".equals(existing.getDelFlag()))
            {
                throw new ServiceException("模型编码已存在: " + aiModel.getModelCode());
            }
            aiModel.setModelId(existing.getModelId());
            int rows = aiModelMapper.reactivateAiModel(aiModel);
            // 旧版软删模型时可能留下的残留绑定(列表 join 过滤后看不见),
            // 复活前清干净,否则再接入会误报「已接入该渠道」。
            if (rows > 0)
            {
                aiModelChannelMapper.deleteByModelIds(
                    java.util.Collections.singletonList(existing.getModelId()));
            }
            publishModelChanged();
            return rows;
        }
        int rows = aiModelMapper.insertAiModel(aiModel);
        publishModelChanged();
        return rows;
    }

    /**
     * 修改模型
     *
     * @param aiModel 模型信息
     * @return 结果
     */
    @Override
    public int updateAiModel(AiModel aiModel)
    {
        syncLegacyVisionFlag(aiModel);
        int rows = aiModelMapper.updateAiModel(aiModel);
        publishModelChanged();
        return rows;
    }

    /**
     * 删除模型对象
     *
     * @param modelId 模型ID
     * @return 结果
     */
    @Override
    @org.springframework.transaction.annotation.Transactional
    public int deleteAiModelById(Long modelId)
    {
        int rows = aiModelMapper.deleteAiModelById(modelId);
        if (rows > 0)
        {
            // 物理删除后连带清理该模型的渠道绑定与调用统计,避免悬空/残留
            aiModelChannelMapper.deleteByModelIds(java.util.Collections.singletonList(modelId));
            aiLlmCallMapper.deleteByModelIds(java.util.Collections.singletonList(modelId));
        }
        publishModelChanged();
        return rows;
    }

    /**
     * 批量删除模型信息
     * 
     * @param modelIds 需要删除的模型ID
     * @return 结果
     */
    @Override
    @org.springframework.transaction.annotation.Transactional
    public int deleteAiModelByIds(Long[] modelIds)
    {
        int rows = aiModelMapper.deleteAiModelByIds(modelIds);
        if (rows > 0 && modelIds != null)
        {
            // 连带清理这批模型的渠道绑定与调用统计
            aiModelChannelMapper.deleteByModelIds(java.util.Arrays.asList(modelIds));
            aiLlmCallMapper.deleteByModelIds(java.util.Arrays.asList(modelIds));
        }
        publishModelChanged();
        return rows;
    }

    /** 缓存按 modelCode 键控且 code 本身可能被改,MODEL 事件一律整表失效,无需 id */
    /**
     * 由 input_modalities 反推旧的 vision_enabled 列。
     *
     * <p>判定一律以 input_modalities 为准,这里只是让迁移期内直接读 vision_enabled 的地方
     * (存量报表、外部查询)不至于看到互相矛盾的两列。inputModalities 为 null 表示调用方
     * 没打算改这个字段,此时不动旧列,免得把别处刚写好的值覆盖掉。
     */
    private void syncLegacyVisionFlag(AiModel aiModel)
    {
        if (aiModel == null || aiModel.getInputModalities() == null)
        {
            return;
        }
        aiModel.setVisionEnabled(
            ModelInputModalities.parse(aiModel.getInputModalities()).imageEnabled() ? "1" : "0");
    }

    private void publishModelChanged()
    {
        try
        {
            eventPublisher.publishEvent(new AiConfigChangedEvent(AiConfigChangedEvent.Kind.MODEL, null));
        }
        catch (RuntimeException e)
        {
            org.slf4j.LoggerFactory.getLogger(AiModelServiceImpl.class)
                    .warn("广播模型变更事件失败,装配缓存可能仍持旧配置", e);
        }
    }
}
