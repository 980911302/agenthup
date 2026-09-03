package com.ruoyi.system.kb.graph.community;

import java.util.ArrayList;
import java.util.List;
import com.ruoyi.system.domain.KbGraphCommunity;
import com.ruoyi.system.domain.KbGraphEntityCommunity;

/** 社区检测输出 */
public class CommunityDetectionResult
{
    private String graphVersion;
    private String algorithm;
    private String gdsVersion;
    private boolean available;
    private String skipReason;
    private int levelCount;
    private int entityCount;
    private int relationCount;
    private List<KbGraphCommunity> communities = new ArrayList<>();
    private List<KbGraphEntityCommunity> memberships = new ArrayList<>();

    public String getGraphVersion() { return graphVersion; }
    public void setGraphVersion(String graphVersion) { this.graphVersion = graphVersion; }
    public String getAlgorithm() { return algorithm; }
    public void setAlgorithm(String algorithm) { this.algorithm = algorithm; }
    public String getGdsVersion() { return gdsVersion; }
    public void setGdsVersion(String gdsVersion) { this.gdsVersion = gdsVersion; }
    public boolean isAvailable() { return available; }
    public void setAvailable(boolean available) { this.available = available; }
    public String getSkipReason() { return skipReason; }
    public void setSkipReason(String skipReason) { this.skipReason = skipReason; }
    public int getLevelCount() { return levelCount; }
    public void setLevelCount(int levelCount) { this.levelCount = levelCount; }
    public int getEntityCount() { return entityCount; }
    public void setEntityCount(int entityCount) { this.entityCount = entityCount; }
    public int getRelationCount() { return relationCount; }
    public void setRelationCount(int relationCount) { this.relationCount = relationCount; }
    public List<KbGraphCommunity> getCommunities() { return communities; }
    public void setCommunities(List<KbGraphCommunity> communities)
    {
        this.communities = communities != null ? communities : new ArrayList<>();
    }
    public List<KbGraphEntityCommunity> getMemberships() { return memberships; }
    public void setMemberships(List<KbGraphEntityCommunity> memberships)
    {
        this.memberships = memberships != null ? memberships : new ArrayList<>();
    }
}
