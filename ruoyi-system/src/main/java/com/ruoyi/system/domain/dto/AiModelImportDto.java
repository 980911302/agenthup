package com.ruoyi.system.domain.dto;

import java.math.BigDecimal;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 模型导入请求 DTO
 *
 * 两种场景复用:
 *   - 导入:modelCode 在平台不存在 → 创建 ai_model + ai_model_channel(模型字段必填)
 *   - 新增供应:modelCode 已存在 → 仅创建 ai_model_channel(模型字段忽略)
 *
 * @author ruoyi
 */
public class AiModelImportDto
{
    /** 渠道 ID */
    @NotNull(message = "渠道不能为空")
    private Long channelId;

    /** 模型编码(= 上游模型 ID) */
    @NotBlank(message = "模型编码不能为空")
    private String modelCode;

    /** 展示名称(新增模型时必填,缺省取 modelCode) */
    private String displayName;

    /** 模型类型(新增模型时必填) */
    private String modelType;

    /** 上下文长度(新增模型时必填) */
    private Integer contextWindow;

    /** 最大输出 tokens */
    private Integer maxOutputTokens;

    /** 是否推理(0否 1是,默认 0) */
    private String reasoningEnabled;

    /** 支持的输入模态,逗号分隔:image / file / video / audio。空表示纯文本模型 */
    private String inputModalities;

    /** 排序 */
    private Integer sort;

    /** 该渠道下的调用标识(缺省取 modelCode) */
    private String modelName;

    /** 路由权重(默认 1) */
    private Integer weight;

    /** 失败重试次数(默认 0) */
    private Integer retryCount;

    /** 输入价格(元/千 tokens) */
    private BigDecimal inputPrice;

    /** 输出价格(元/千 tokens) */
    private BigDecimal outputPrice;

    /** 备注 */
    private String remark;

    public Long getChannelId()
    {
        return channelId;
    }

    public void setChannelId(Long channelId)
    {
        this.channelId = channelId;
    }

    public String getModelCode()
    {
        return modelCode;
    }

    public void setModelCode(String modelCode)
    {
        this.modelCode = modelCode;
    }

    public String getDisplayName()
    {
        return displayName;
    }

    public void setDisplayName(String displayName)
    {
        this.displayName = displayName;
    }

    public String getModelType()
    {
        return modelType;
    }

    public void setModelType(String modelType)
    {
        this.modelType = modelType;
    }

    public Integer getContextWindow()
    {
        return contextWindow;
    }

    public void setContextWindow(Integer contextWindow)
    {
        this.contextWindow = contextWindow;
    }

    public Integer getMaxOutputTokens()
    {
        return maxOutputTokens;
    }

    public void setMaxOutputTokens(Integer maxOutputTokens)
    {
        this.maxOutputTokens = maxOutputTokens;
    }

    public String getReasoningEnabled()
    {
        return reasoningEnabled;
    }

    public void setReasoningEnabled(String reasoningEnabled)
    {
        this.reasoningEnabled = reasoningEnabled;
    }

    public String getInputModalities()
    {
        return inputModalities;
    }

    public void setInputModalities(String inputModalities)
    {
        this.inputModalities = inputModalities;
    }

    public Integer getSort()
    {
        return sort;
    }

    public void setSort(Integer sort)
    {
        this.sort = sort;
    }

    public String getModelName()
    {
        return modelName;
    }

    public void setModelName(String modelName)
    {
        this.modelName = modelName;
    }

    public Integer getWeight()
    {
        return weight;
    }

    public void setWeight(Integer weight)
    {
        this.weight = weight;
    }

    public Integer getRetryCount()
    {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount)
    {
        this.retryCount = retryCount;
    }

    public BigDecimal getInputPrice()
    {
        return inputPrice;
    }

    public void setInputPrice(BigDecimal inputPrice)
    {
        this.inputPrice = inputPrice;
    }

    public BigDecimal getOutputPrice()
    {
        return outputPrice;
    }

    public void setOutputPrice(BigDecimal outputPrice)
    {
        this.outputPrice = outputPrice;
    }

    public String getRemark()
    {
        return remark;
    }

    public void setRemark(String remark)
    {
        this.remark = remark;
    }
}
