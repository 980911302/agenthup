package com.ruoyi.system.kb.graph.community;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 单社区报告草稿（生成管线中间态）。
 */
public class CommunityReportDraft
{
    private Long kbId;
    private String graphVersion;
    private int level;
    private long communityId;
    private String title;
    private String summary;
    private String fullContent;
    private String findingsJson;
    private String contentHash;
    private String modelCode;
    private String promptVersion;
    private String status = "READY";
    private boolean incomplete;
    private String skipReason;
    private final List<Long> sourceChunkIds = new ArrayList<>();
    private final Set<String> entityNames = new LinkedHashSet<>();

    public Long getKbId() { return kbId; }
    public void setKbId(Long kbId) { this.kbId = kbId; }
    public String getGraphVersion() { return graphVersion; }
    public void setGraphVersion(String graphVersion) { this.graphVersion = graphVersion; }
    public int getLevel() { return level; }
    public void setLevel(int level) { this.level = level; }
    public long getCommunityId() { return communityId; }
    public void setCommunityId(long communityId) { this.communityId = communityId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getFullContent() { return fullContent; }
    public void setFullContent(String fullContent) { this.fullContent = fullContent; }
    public String getFindingsJson() { return findingsJson; }
    public void setFindingsJson(String findingsJson) { this.findingsJson = findingsJson; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public String getModelCode() { return modelCode; }
    public void setModelCode(String modelCode) { this.modelCode = modelCode; }
    public String getPromptVersion() { return promptVersion; }
    public void setPromptVersion(String promptVersion) { this.promptVersion = promptVersion; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public boolean isIncomplete() { return incomplete; }
    public void setIncomplete(boolean incomplete) { this.incomplete = incomplete; }
    public String getSkipReason() { return skipReason; }
    public void setSkipReason(String skipReason) { this.skipReason = skipReason; }
    public List<Long> getSourceChunkIds() { return sourceChunkIds; }
    public Set<String> getEntityNames() { return entityNames; }
}
