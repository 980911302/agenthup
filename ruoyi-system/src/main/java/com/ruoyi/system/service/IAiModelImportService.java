package com.ruoyi.system.service;

import java.util.List;
import com.ruoyi.system.domain.dto.AiModelImportDto;
import com.ruoyi.system.domain.vo.UpstreamModelVo;

/**
 * 模型导入 服务层
 *
 * @author ruoyi
 */
public interface IAiModelImportService
{
    /**
     * 实时查询上游渠道的模型列表,并标注平台内导入状态
     *
     * @param channelId 渠道ID
     * @return 上游模型列表(带推荐类型/能力/导入状态)
     */
    public List<UpstreamModelVo> listUpstreamModels(Long channelId);

    /**
     * 导入模型:modelCode 不存在则创建模型 + 渠道绑定,已存在则仅新增渠道供应
     *
     * @param dto      导入参数
     * @param operator 操作人
     * @return 结果提示语
     */
    public String importModel(AiModelImportDto dto, String operator);
}
