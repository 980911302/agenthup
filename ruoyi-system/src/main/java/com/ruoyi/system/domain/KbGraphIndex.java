package com.ruoyi.system.domain;

import java.util.Date;

/** 知识库图索引/社区任务状态 kb_graph_index */
public class KbGraphIndex
{
    private Long kbId;
    private String graphVersion;
    /** 上一 active 版本，用于回滚（KB-GR-13） */
    private String previousGraphVersion;
    private String status;
    private String step;
    private Integer entityCount;
    private Integer relationCount;
    private Integer communityCount;
    private Integer levelCount;
    private String extractorVersion;
    private String communityVersion;
    private String reportVersion;
    private String gdsAvailable;
    private String gdsVersion;
    private Date dirtyAt;
    private Date startedAt;
    private Date finishedAt;
    private String errorType;
    private String errorMsg;

    public Long getKbId() { return kbId; }
    public void setKbId(Long kbId) { this.kbId = kbId; }
    public String getGraphVersion() { return graphVersion; }
    public void setGraphVersion(String graphVersion) { this.graphVersion = graphVersion; }
    public String getPreviousGraphVersion() { return previousGraphVersion; }
    public void setPreviousGraphVersion(String previousGraphVersion) { this.previousGraphVersion = previousGraphVersion; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getStep() { return step; }
    public void setStep(String step) { this.step = step; }
    public Integer getEntityCount() { return entityCount; }
    public void setEntityCount(Integer entityCount) { this.entityCount = entityCount; }
    public Integer getRelationCount() { return relationCount; }
    public void setRelationCount(Integer relationCount) { this.relationCount = relationCount; }
    public Integer getCommunityCount() { return communityCount; }
    public void setCommunityCount(Integer communityCount) { this.communityCount = communityCount; }
    public Integer getLevelCount() { return levelCount; }
    public void setLevelCount(Integer levelCount) { this.levelCount = levelCount; }
    public String getExtractorVersion() { return extractorVersion; }
    public void setExtractorVersion(String extractorVersion) { this.extractorVersion = extractorVersion; }
    public String getCommunityVersion() { return communityVersion; }
    public void setCommunityVersion(String communityVersion) { this.communityVersion = communityVersion; }
    public String getReportVersion() { return reportVersion; }
    public void setReportVersion(String reportVersion) { this.reportVersion = reportVersion; }
    public String getGdsAvailable() { return gdsAvailable; }
    public void setGdsAvailable(String gdsAvailable) { this.gdsAvailable = gdsAvailable; }
    public String getGdsVersion() { return gdsVersion; }
    public void setGdsVersion(String gdsVersion) { this.gdsVersion = gdsVersion; }
    public Date getDirtyAt() { return dirtyAt; }
    public void setDirtyAt(Date dirtyAt) { this.dirtyAt = dirtyAt; }
    public Date getStartedAt() { return startedAt; }
    public void setStartedAt(Date startedAt) { this.startedAt = startedAt; }
    public Date getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Date finishedAt) { this.finishedAt = finishedAt; }
    public String getErrorType() { return errorType; }
    public void setErrorType(String errorType) { this.errorType = errorType; }
    public String getErrorMsg() { return errorMsg; }
    public void setErrorMsg(String errorMsg) { this.errorMsg = errorMsg; }
}
