package com.ruoyi.system.domain;

import java.math.BigDecimal;
import jakarta.validation.constraints.NotNull;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * 模型渠道绑定表 ai_model_channel
 * 
 * @author ruoyi
 */
public class AiModelChannel extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 绑定ID */
    private Long id;

    /** 模型ID */
    @NotNull(message = "模型不能为空")
    private Long modelId;

    /** 渠道ID */
    @NotNull(message = "渠道不能为空")
    private Long channelId;

    /** 该渠道下的调用标识(API的model参数,一般同model_code,Azure等可不同) */
    private String modelName;

    /** 该渠道输入价格(元/千tokens) */
    private BigDecimal inputPrice;

    /** 该渠道输出价格(元/千tokens) */
    private BigDecimal outputPrice;

    /** 权重(路由时按权重分配流量) */
    private Integer weight;

    /** 失败重试次数(0=不重试,路由在该渠道调用失败时的重试上限) */
    private Integer retryCount;

    /** 绑定状态(0正常 1停用) */
    private String status;

    /** 渠道名称(关联查询) */
    private String channelName;

    /** 模型编码(关联查询) */
    private String modelCode;

    /** 模型展示名称(关联查询) */
    private String displayName;

    /** 瞬时字段:调用标识是否已不在所属渠道的上游模型清单中 */
    private Boolean upstreamMissing;

    public Long getId()
    {
        return id;
    }

    public void setId(Long id)
    {
        this.id = id;
    }

    public Long getModelId()
    {
        return modelId;
    }

    public void setModelId(Long modelId)
    {
        this.modelId = modelId;
    }

    public Long getChannelId()
    {
        return channelId;
    }

    public void setChannelId(Long channelId)
    {
        this.channelId = channelId;
    }

    public String getModelName()
    {
        return modelName;
    }

    public void setModelName(String modelName)
    {
        this.modelName = modelName;
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

    public String getStatus()
    {
        return status;
    }

    public void setStatus(String status)
    {
        this.status = status;
    }

    public String getChannelName()
    {
        return channelName;
    }

    public void setChannelName(String channelName)
    {
        this.channelName = channelName;
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

    public Boolean getUpstreamMissing()
    {
        return upstreamMissing;
    }

    public void setUpstreamMissing(Boolean upstreamMissing)
    {
        this.upstreamMissing = upstreamMissing;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("modelId", getModelId())
            .append("channelId", getChannelId())
            .append("modelName", getModelName())
            .append("inputPrice", getInputPrice())
            .append("outputPrice", getOutputPrice())
            .append("weight", getWeight())
            .append("retryCount", getRetryCount())
            .append("status", getStatus())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .append("remark", getRemark())
            .toString();
    }
}
