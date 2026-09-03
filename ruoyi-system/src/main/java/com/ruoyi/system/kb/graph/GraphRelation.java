package com.ruoyi.system.kb.graph;

import java.util.ArrayList;
import java.util.List;

/**
 * 图关系(:Entity)-[:RELATED]-&gt;(:Entity)。
 * <p>relationKey = source|predicate|target；对称谓词端点已规范化。
 */
public class GraphRelation
{
    private Long kbId;
    private String sourceName;
    private String targetName;
    /** 稳定端点 key（优先于 name 匹配图节点） */
    private String sourceEntityKey;
    private String targetEntityKey;
    private String keywords;
    /** 规范化谓词（小写/NFKC） */
    private String predicate;
    private String relationKey;
    private String description;
    private double weight = 1.0;
    private List<Long> sourceIds = new ArrayList<>();

    public Long getKbId()
    {
        return kbId;
    }

    public void setKbId(Long kbId)
    {
        this.kbId = kbId;
    }

    public String getSourceName()
    {
        return sourceName;
    }

    public void setSourceName(String sourceName)
    {
        this.sourceName = sourceName;
    }

    public String getTargetName()
    {
        return targetName;
    }

    public void setTargetName(String targetName)
    {
        this.targetName = targetName;
    }

    public String getSourceEntityKey()
    {
        return sourceEntityKey;
    }

    public void setSourceEntityKey(String sourceEntityKey)
    {
        this.sourceEntityKey = sourceEntityKey;
    }

    public String getTargetEntityKey()
    {
        return targetEntityKey;
    }

    public void setTargetEntityKey(String targetEntityKey)
    {
        this.targetEntityKey = targetEntityKey;
    }

    public String getKeywords()
    {
        return keywords;
    }

    public void setKeywords(String keywords)
    {
        this.keywords = keywords;
    }

    public String getPredicate()
    {
        return predicate;
    }

    public void setPredicate(String predicate)
    {
        this.predicate = predicate;
    }

    public String getRelationKey()
    {
        return relationKey;
    }

    public void setRelationKey(String relationKey)
    {
        this.relationKey = relationKey;
    }

    public String getDescription()
    {
        return description;
    }

    public void setDescription(String description)
    {
        this.description = description;
    }

    public double getWeight()
    {
        return weight;
    }

    public void setWeight(double weight)
    {
        this.weight = weight;
    }

    public List<Long> getSourceIds()
    {
        return sourceIds;
    }

    public void setSourceIds(List<Long> sourceIds)
    {
        this.sourceIds = sourceIds != null ? sourceIds : new ArrayList<>();
    }
}
