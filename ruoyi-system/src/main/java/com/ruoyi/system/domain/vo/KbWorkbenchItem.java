package com.ruoyi.system.domain.vo;

import java.util.Date;

/**
 * 知识资产工作台行（KB-PV2-02 聚合查询结果）。
 */
public class KbWorkbenchItem
{
    private Long kbId;
    private String kbName;
    private String description;
    private String status;
    private Long createUserId;
    private Long ownerUserId;
    private String visibilityCode;
    private Long deptId;
    private String createBy;
    private Date createTime;
    private Date updateTime;

    /** 文档总数 */
    private Integer docCount;
    /** 处理中文档数 */
    private Integer processingCount;
    /** 失败文档数 */
    private Integer failedCount;
    /** 已完成文档数 */
    private Integer readyCount;
    /** 最近文档活动时间 */
    private Date lastContentTime;
    /**
     * 业务健康态：READY / PROCESSING / ISSUE / EMPTY / DISABLED
     */
    private String health;
    /** 一行原因说明 */
    private String healthReason;
    /** 可见范围展示：PRIVATE / DEPT / ORG / UNKNOWN */
    private String visibility;

    /** 有效负责人展示（owner_user_id ?? create_user_id 批量回填） */
    private KbUserBriefVO owner;

    public Long getKbId() { return kbId; }
    public void setKbId(Long kbId) { this.kbId = kbId; }
    public String getKbName() { return kbName; }
    public void setKbName(String kbName) { this.kbName = kbName; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getCreateUserId() { return createUserId; }
    public void setCreateUserId(Long createUserId) { this.createUserId = createUserId; }
    public Long getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(Long ownerUserId) { this.ownerUserId = ownerUserId; }
    public String getVisibilityCode() { return visibilityCode; }
    public void setVisibilityCode(String visibilityCode) { this.visibilityCode = visibilityCode; }
    public Long getDeptId() { return deptId; }
    public void setDeptId(Long deptId) { this.deptId = deptId; }
    public String getCreateBy() { return createBy; }
    public void setCreateBy(String createBy) { this.createBy = createBy; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
    public Integer getDocCount() { return docCount; }
    public void setDocCount(Integer docCount) { this.docCount = docCount; }
    public Integer getProcessingCount() { return processingCount; }
    public void setProcessingCount(Integer processingCount) { this.processingCount = processingCount; }
    public Integer getFailedCount() { return failedCount; }
    public void setFailedCount(Integer failedCount) { this.failedCount = failedCount; }
    public Integer getReadyCount() { return readyCount; }
    public void setReadyCount(Integer readyCount) { this.readyCount = readyCount; }
    public Date getLastContentTime() { return lastContentTime; }
    public void setLastContentTime(Date lastContentTime) { this.lastContentTime = lastContentTime; }
    public String getHealth() { return health; }
    public void setHealth(String health) { this.health = health; }
    public String getHealthReason() { return healthReason; }
    public void setHealthReason(String healthReason) { this.healthReason = healthReason; }
    public String getVisibility() { return visibility; }
    public void setVisibility(String visibility) { this.visibility = visibility; }
    public KbUserBriefVO getOwner() { return owner; }
    public void setOwner(KbUserBriefVO owner) { this.owner = owner; }

    /** 有效负责人 ID：ownerUserId ?? createUserId */
    public Long effectiveOwnerId()
    {
        return ownerUserId != null ? ownerUserId : createUserId;
    }
}
