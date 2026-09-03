package com.ruoyi.system.mapper;

import java.util.List;
import com.ruoyi.system.domain.AiModel;

/**
 * 模型表 数据层
 * 
 * @author ruoyi
 */
public interface AiModelMapper
{
    /**
     * 查询模型信息
     * 
     * @param modelId 模型ID
     * @return 模型信息
     */
    public AiModel selectAiModelById(Long modelId);

    /**
     * 按模型编码精确查询(导入判重用)
     *
     * @param modelCode 模型编码
     * @return 模型信息
     */
    public AiModel selectAiModelByModelCode(String modelCode);

    /** 含软删,删除后重加时复活避免撞 uk_model_code */
    public AiModel selectAiModelByCodeIncludeDeleted(String modelCode);

    /**
     * 查询模型列表
     *
     * @param aiModel 模型信息
     * @return 模型集合
     */
    public List<AiModel> selectAiModelList(AiModel aiModel);

    /**
     * 新增模型
     * 
     * @param aiModel 模型信息
     * @return 结果
     */
    public int insertAiModel(AiModel aiModel);

    public int reactivateAiModel(AiModel aiModel);

    /**
     * 修改模型
     * 
     * @param aiModel 模型信息
     * @return 结果
     */
    public int updateAiModel(AiModel aiModel);

    /**
     * 删除模型
     * 
     * @param modelId 模型ID
     * @return 结果
     */
    public int deleteAiModelById(Long modelId);

    /**
     * 批量删除模型
     * 
     * @param modelIds 需要删除的模型ID
     * @return 结果
     */
    public int deleteAiModelByIds(Long[] modelIds);
}
