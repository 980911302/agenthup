package com.ruoyi.system.domain;

import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * 上游渠道表 ai_channel
 * 
 * @author ruoyi
 */
public class AiChannel extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 渠道ID */
    private Long channelId;

    /** 渠道名称 */
    private String channelName;

    /** 渠道编码(程序引用,唯一) */
    private String channelCode;

    /** 渠道类型(OPENAI/ANTHROPIC/GEMINI/OLLAMA) */
    private String channelType;

    /** 是否自定义渠道(0否 1是)。自定义=手动维护模型清单;非自定义=从上游 /models 同步 */
    private String isCustom;

    /** API基础地址 */
    private String baseUrl;

    /** API密钥(AES加密后存储,展示时脱敏) */
    private String apiKey;

    /** 健康检查路径(默认 /models,可空) */
    private String healthCheckUri;

    /** 健康状态(0未知 1正常 2异常) */
    private String healthStatus;

    /** 最近健康检查时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date healthCheckTime;

    /** 连续失败次数(熔断判定用) */
    private Integer healthFailCount;

    /** 渠道状态(0正常 1停用) */
    private String status;

    /** 删除标志(0存在 2删除) */
    private String delFlag;

    public Long getChannelId()
    {
        return channelId;
    }

    public void setChannelId(Long channelId)
    {
        this.channelId = channelId;
    }

    @NotBlank(message = "渠道名称不能为空")
    @Size(min = 0, max = 100, message = "渠道名称不能超过100个字符")
    public String getChannelName()
    {
        return channelName;
    }

    public void setChannelName(String channelName)
    {
        this.channelName = channelName;
    }

    @NotBlank(message = "渠道编码不能为空")
    @Size(min = 0, max = 50, message = "渠道编码不能超过50个字符")
    public String getChannelCode()
    {
        return channelCode;
    }

    public void setChannelCode(String channelCode)
    {
        this.channelCode = channelCode;
    }

    @NotBlank(message = "渠道类型不能为空")
    public String getChannelType()
    {
        return channelType;
    }

    public void setChannelType(String channelType)
    {
        this.channelType = channelType;
    }

    public String getIsCustom()
    {
        return isCustom;
    }

    public void setIsCustom(String isCustom)
    {
        this.isCustom = isCustom;
    }

    @NotBlank(message = "API基础地址不能为空")
    public String getBaseUrl()
    {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl)
    {
        this.baseUrl = baseUrl;
    }

    public String getApiKey()
    {
        return apiKey;
    }

    public void setApiKey(String apiKey)
    {
        this.apiKey = apiKey;
    }

    public String getHealthCheckUri()
    {
        return healthCheckUri;
    }

    public void setHealthCheckUri(String healthCheckUri)
    {
        this.healthCheckUri = healthCheckUri;
    }

    public String getHealthStatus()
    {
        return healthStatus;
    }

    public void setHealthStatus(String healthStatus)
    {
        this.healthStatus = healthStatus;
    }

    public Date getHealthCheckTime()
    {
        return healthCheckTime;
    }

    public void setHealthCheckTime(Date healthCheckTime)
    {
        this.healthCheckTime = healthCheckTime;
    }

    public Integer getHealthFailCount()
    {
        return healthFailCount;
    }

    public void setHealthFailCount(Integer healthFailCount)
    {
        this.healthFailCount = healthFailCount;
    }

    public String getStatus()
    {
        return status;
    }

    public void setStatus(String status)
    {
        this.status = status;
    }
    public String getDelFlag()
    {
        return delFlag;
    }

    public void setDelFlag(String delFlag)
    {
        this.delFlag = delFlag;
    }


    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("channelId", getChannelId())
            .append("channelName", getChannelName())
            .append("channelCode", getChannelCode())
            .append("channelType", getChannelType())
            .append("isCustom", getIsCustom())
            .append("baseUrl", getBaseUrl())
            .append("apiKey", getApiKey())
            .append("healthCheckUri", getHealthCheckUri())
            .append("healthStatus", getHealthStatus())
            .append("healthCheckTime", getHealthCheckTime())
            .append("healthFailCount", getHealthFailCount())
            .append("status", getStatus())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .append("remark", getRemark())
            .toString();
    }
}
