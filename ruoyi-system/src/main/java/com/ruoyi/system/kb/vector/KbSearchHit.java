package com.ruoyi.system.kb.vector;

import java.util.ArrayList;
import java.util.List;

/**
 * 检索命中。出处字段是刚性要求。
 * <p>KB-GR-10 扩展：channel / rank / 分通道分数与实体溯源。
 */
public class KbSearchHit
{
    private Long chunkId;
    private Long kbId;
    private Long docId;
    private String docName;
    private String headingPath;
    private Integer chunkIndex;
    private String content;
    private double score;

    /** basic / local / hybrid / community / drift */
    private String channel;
    /** 本通道内排名（从 1 起） */
    private Integer rankByChannel;
    /** hybrid 时 basic 路分数（可选） */
    private Double basicScore;
    /** hybrid 时 local 路分数（可选） */
    private Double localScore;
    /** 关联实体名（Local 溯源） */
    private List<String> entityNames = new ArrayList<>();
    /** 关联社区 id（Global 溯源） */
    private List<Long> communityIds = new ArrayList<>();
    /** 父块 id（扩展后可填） */
    private Long parentChunkId;
    /** 调试轨迹 JSON（Global visited/pruned/selected 等） */
    private String debugTrace;

    public Long getChunkId()
    {
        return chunkId;
    }

    public void setChunkId(Long chunkId)
    {
        this.chunkId = chunkId;
    }

    public Long getKbId()
    {
        return kbId;
    }

    public void setKbId(Long kbId)
    {
        this.kbId = kbId;
    }

    public Long getDocId()
    {
        return docId;
    }

    public void setDocId(Long docId)
    {
        this.docId = docId;
    }

    public String getDocName()
    {
        return docName;
    }

    public void setDocName(String docName)
    {
        this.docName = docName;
    }

    public String getHeadingPath()
    {
        return headingPath;
    }

    public void setHeadingPath(String headingPath)
    {
        this.headingPath = headingPath;
    }

    public Integer getChunkIndex()
    {
        return chunkIndex;
    }

    public void setChunkIndex(Integer chunkIndex)
    {
        this.chunkIndex = chunkIndex;
    }

    public String getContent()
    {
        return content;
    }

    public void setContent(String content)
    {
        this.content = content;
    }

    public double getScore()
    {
        return score;
    }

    public void setScore(double score)
    {
        this.score = score;
    }

    public String getChannel()
    {
        return channel;
    }

    public void setChannel(String channel)
    {
        this.channel = channel;
    }

    public Integer getRankByChannel()
    {
        return rankByChannel;
    }

    public void setRankByChannel(Integer rankByChannel)
    {
        this.rankByChannel = rankByChannel;
    }

    public Double getBasicScore()
    {
        return basicScore;
    }

    public void setBasicScore(Double basicScore)
    {
        this.basicScore = basicScore;
    }

    public Double getLocalScore()
    {
        return localScore;
    }

    public void setLocalScore(Double localScore)
    {
        this.localScore = localScore;
    }

    public List<String> getEntityNames()
    {
        return entityNames;
    }

    public void setEntityNames(List<String> entityNames)
    {
        this.entityNames = entityNames != null ? entityNames : new ArrayList<>();
    }

    public List<Long> getCommunityIds()
    {
        return communityIds;
    }

    public void setCommunityIds(List<Long> communityIds)
    {
        this.communityIds = communityIds != null ? communityIds : new ArrayList<>();
    }

    public Long getParentChunkId()
    {
        return parentChunkId;
    }

    public void setParentChunkId(Long parentChunkId)
    {
        this.parentChunkId = parentChunkId;
    }

    public String getDebugTrace()
    {
        return debugTrace;
    }

    public void setDebugTrace(String debugTrace)
    {
        this.debugTrace = debugTrace;
    }
}

