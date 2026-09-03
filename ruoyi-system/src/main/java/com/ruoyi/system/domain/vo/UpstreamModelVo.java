package com.ruoyi.system.domain.vo;

import java.util.List;

/**
 * 上游模型导入列表 VO
 *
 * 对应导入弹窗表格的一行:上游模型 + 平台侧导入状态。
 * importStatus: NOT_IMPORTED(未导入) / MODEL_EXISTS(已建模型,可新增供应) / CHANNEL_BOUND(本渠道已接入)
 *
 * @author ruoyi
 */
public class UpstreamModelVo
{
    /** 上游模型 ID(API 的 model 参数) */
    private String upstreamModelId;

    /** 上游展示名 */
    private String displayName;

    /** 推荐模型类型(按名称推断,可改) */
    private String recommendType;

    /** 推荐能力标签(按名称推断,展示用) */
    private List<String> capabilities;

    /** 归属方(上游 owned_by) */
    private String ownedBy;

    /** 上游声明的输入模态,逗号分隔;为空表示上游没给,前端回退按名字推测并提示核对 */
    private String inputModalities;

    /** 导入状态:NOT_IMPORTED / MODEL_EXISTS / CHANNEL_BOUND */
    private String importStatus;

    /** 平台内已存在的模型 ID(importStatus != NOT_IMPORTED 时有值) */
    private Long existingModelId;

    public String getUpstreamModelId()
    {
        return upstreamModelId;
    }

    public void setUpstreamModelId(String upstreamModelId)
    {
        this.upstreamModelId = upstreamModelId;
    }

    public String getDisplayName()
    {
        return displayName;
    }

    public void setDisplayName(String displayName)
    {
        this.displayName = displayName;
    }

    public String getRecommendType()
    {
        return recommendType;
    }

    public void setRecommendType(String recommendType)
    {
        this.recommendType = recommendType;
    }

    public List<String> getCapabilities()
    {
        return capabilities;
    }

    public void setCapabilities(List<String> capabilities)
    {
        this.capabilities = capabilities;
    }

    public String getOwnedBy()
    {
        return ownedBy;
    }

    public void setOwnedBy(String ownedBy)
    {
        this.ownedBy = ownedBy;
    }

    public String getImportStatus()
    {
        return importStatus;
    }

    public void setImportStatus(String importStatus)
    {
        this.importStatus = importStatus;
    }

    public Long getExistingModelId()
    {
        return existingModelId;
    }

    public void setExistingModelId(Long existingModelId)
    {
        this.existingModelId = existingModelId;
    }

    public String getInputModalities()
    {
        return inputModalities;
    }

    public void setInputModalities(String inputModalities)
    {
        this.inputModalities = inputModalities;
    }

}
