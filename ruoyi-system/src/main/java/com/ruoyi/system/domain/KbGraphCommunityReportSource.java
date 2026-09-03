package com.ruoyi.system.domain;

/** 社区报告出处 kb_graph_community_report_source */
public class KbGraphCommunityReportSource
{
    private Long reportId;
    private Long chunkId;
    private Integer evidenceRank;

    public Long getReportId() { return reportId; }
    public void setReportId(Long reportId) { this.reportId = reportId; }
    public Long getChunkId() { return chunkId; }
    public void setChunkId(Long chunkId) { this.chunkId = chunkId; }
    public Integer getEvidenceRank() { return evidenceRank; }
    public void setEvidenceRank(Integer evidenceRank) { this.evidenceRank = evidenceRank; }
}
