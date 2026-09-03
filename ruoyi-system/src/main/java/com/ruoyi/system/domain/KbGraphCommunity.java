package com.ruoyi.system.domain;

/** 层级社区节点 kb_graph_community */
public class KbGraphCommunity
{
    private Long kbId;
    private String graphVersion;
    private Integer level;
    private Long communityId;
    private Long parentCommunityId;
    private Integer rank;
    private Integer entityCount;
    private Integer relationCount;
    private Integer sourceChunkCount;
    private String contentHash;

    public Long getKbId() { return kbId; }
    public void setKbId(Long kbId) { this.kbId = kbId; }
    public String getGraphVersion() { return graphVersion; }
    public void setGraphVersion(String graphVersion) { this.graphVersion = graphVersion; }
    public Integer getLevel() { return level; }
    public void setLevel(Integer level) { this.level = level; }
    public Long getCommunityId() { return communityId; }
    public void setCommunityId(Long communityId) { this.communityId = communityId; }
    public Long getParentCommunityId() { return parentCommunityId; }
    public void setParentCommunityId(Long parentCommunityId) { this.parentCommunityId = parentCommunityId; }
    public Integer getRank() { return rank; }
    public void setRank(Integer rank) { this.rank = rank; }
    public Integer getEntityCount() { return entityCount; }
    public void setEntityCount(Integer entityCount) { this.entityCount = entityCount; }
    public Integer getRelationCount() { return relationCount; }
    public void setRelationCount(Integer relationCount) { this.relationCount = relationCount; }
    public Integer getSourceChunkCount() { return sourceChunkCount; }
    public void setSourceChunkCount(Integer sourceChunkCount) { this.sourceChunkCount = sourceChunkCount; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
}
