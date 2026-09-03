package com.ruoyi.system.domain.vo;

/**
 * 未进入供应下拉的渠道及原因：未同步清单，或清单里没有当前模型。
 */
public class SupplyExcludedVo
{
    private Long channelId;
    private String channelName;
    private String channelCode;
    /** not_synced | missing_model */
    private String reasonType;
    private String reason;

    public Long getChannelId()
    {
        return channelId;
    }

    public void setChannelId(Long channelId)
    {
        this.channelId = channelId;
    }

    public String getChannelName()
    {
        return channelName;
    }

    public void setChannelName(String channelName)
    {
        this.channelName = channelName;
    }

    public String getChannelCode()
    {
        return channelCode;
    }

    public void setChannelCode(String channelCode)
    {
        this.channelCode = channelCode;
    }

    public String getReasonType()
    {
        return reasonType;
    }

    public void setReasonType(String reasonType)
    {
        this.reasonType = reasonType;
    }

    public String getReason()
    {
        return reason;
    }

    public void setReason(String reason)
    {
        this.reason = reason;
    }
}
