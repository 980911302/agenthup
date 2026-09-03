package com.ruoyi.system.domain.vo;

import com.ruoyi.system.domain.AiChannel;

/**
 * 渠道视图(脱敏,用于列表/详情展示)
 * 
 * @author ruoyi
 */
public class AiChannelVo
{
    private Long channelId;
    private String channelName;
    private String channelCode;
    private String channelType;
    private String isCustom;
    private String baseUrl;
    /** 脱敏后的 apiKey,如 sk-a****xyz */
    private String apiKey;
    private String healthCheckUri;
    private String healthStatus;
    private java.util.Date healthCheckTime;
    private Integer healthFailCount;
    private String status;
    private String createBy;
    private java.util.Date createTime;
    private String updateBy;
    private java.util.Date updateTime;
    private String remark;

    public static AiChannelVo from(AiChannel c, String maskedKey)
    {
        AiChannelVo v = new AiChannelVo();
        v.channelId = c.getChannelId();
        v.channelName = c.getChannelName();
        v.channelCode = c.getChannelCode();
        v.channelType = c.getChannelType();
        v.isCustom = c.getIsCustom();
        v.baseUrl = c.getBaseUrl();
        v.apiKey = maskedKey;
        v.healthCheckUri = c.getHealthCheckUri();
        v.healthStatus = c.getHealthStatus();
        v.healthCheckTime = c.getHealthCheckTime();
        v.healthFailCount = c.getHealthFailCount();
        v.status = c.getStatus();
        v.createBy = c.getCreateBy();
        v.createTime = c.getCreateTime();
        v.updateBy = c.getUpdateBy();
        v.updateTime = c.getUpdateTime();
        v.remark = c.getRemark();
        return v;
    }

    public Long getChannelId() { return channelId; }
    public void setChannelId(Long channelId) { this.channelId = channelId; }
    public String getChannelName() { return channelName; }
    public void setChannelName(String channelName) { this.channelName = channelName; }
    public String getChannelCode() { return channelCode; }
    public void setChannelCode(String channelCode) { this.channelCode = channelCode; }
    public String getChannelType() { return channelType; }
    public void setChannelType(String channelType) { this.channelType = channelType; }
    public String getIsCustom() { return isCustom; }
    public void setIsCustom(String isCustom) { this.isCustom = isCustom; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getHealthCheckUri() { return healthCheckUri; }
    public void setHealthCheckUri(String healthCheckUri) { this.healthCheckUri = healthCheckUri; }
    public String getHealthStatus() { return healthStatus; }
    public void setHealthStatus(String healthStatus) { this.healthStatus = healthStatus; }
    public java.util.Date getHealthCheckTime() { return healthCheckTime; }
    public void setHealthCheckTime(java.util.Date healthCheckTime) { this.healthCheckTime = healthCheckTime; }
    public Integer getHealthFailCount() { return healthFailCount; }
    public void setHealthFailCount(Integer healthFailCount) { this.healthFailCount = healthFailCount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCreateBy() { return createBy; }
    public void setCreateBy(String createBy) { this.createBy = createBy; }
    public java.util.Date getCreateTime() { return createTime; }
    public void setCreateTime(java.util.Date createTime) { this.createTime = createTime; }
    public String getUpdateBy() { return updateBy; }
    public void setUpdateBy(String updateBy) { this.updateBy = updateBy; }
    public java.util.Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(java.util.Date updateTime) { this.updateTime = updateTime; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
