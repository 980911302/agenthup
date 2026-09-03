package com.ruoyi.system.service;

import java.util.List;
import com.ruoyi.system.domain.AiModel;

/**
 * 模型 服务层
 * 
 * @author ruoyi
 */
public interface IAiModelService
{
    /**
     * 查询模型信息
     * 
     * @param modelId 模型ID
     * @return 模型信息
     */
    public AiModel selectAiModelById(Long modelId);

    /**
     * 按 modelCode 查询模型(agent-as-tool 装配时用,AiAgent.modelCode 是字符串而非 ID)
     *
     * @param modelCode 模型编码
     * @return 模型信息(查不到返回 null)
     */
    public AiModel selectByModelCode(String modelCode);

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

    /**
     * 修改模型
     * 
     * @param aiModel 模型信息
     * @return 结果
     */
    public int updateAiModel(AiModel aiModel);

    /**
     * 删除模型信息
     * 
     * @param modelId 模型ID
     * @return 结果
     */
    public int deleteAiModelById(Long modelId);

    /**
     * 批量删除模型信息
     * 
     * @param modelIds 需要删除的模型ID
     * @return 结果
     */
    public int deleteAiModelByIds(Long[] modelIds);
}
