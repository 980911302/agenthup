package com.ruoyi.system.kb.graph;

import java.util.ArrayList;
import java.util.List;

/**
 * 图实体(:Entity)。
 * <p>展示用 name；稳定身份用 entityKey（含 type/sense，同名异义可共存）。
 * candidateKey 仅用于召回，不是唯一约束。
 */
public class GraphEntity
{
    private Long kbId;
    private String name;
    private String type;
    private String description;
    private List<Long> sourceIds = new ArrayList<>();
    private List<String> filePaths = new ArrayList<>();
    /** 规范展示名 */
    private String canonicalName;
    /** 召回键 hash(kb|normName|normType) */
    private String candidateKey;
    /** 稳定身份 kb|normName|normType|sense */
    private String entityKey;

    public Long getKbId()
    {
        return kbId;
    }

    public void setKbId(Long kbId)
    {
        this.kbId = kbId;
    }

    public String getName()
    {
        return name;
    }

    public void setName(String name)
    {
        this.name = name;
    }

    public String getType()
    {
        return type;
    }

    public void setType(String type)
    {
        this.type = type;
    }

    public String getDescription()
    {
        return description;
    }

    public void setDescription(String description)
    {
        this.description = description;
    }

    public List<Long> getSourceIds()
    {
        return sourceIds;
    }

    public void setSourceIds(List<Long> sourceIds)
    {
        this.sourceIds = sourceIds != null ? sourceIds : new ArrayList<>();
    }

    public List<String> getFilePaths()
    {
        return filePaths;
    }

    public void setFilePaths(List<String> filePaths)
    {
        this.filePaths = filePaths != null ? filePaths : new ArrayList<>();
    }

    public String getCanonicalName()
    {
        return canonicalName;
    }

    public void setCanonicalName(String canonicalName)
    {
        this.canonicalName = canonicalName;
    }

    public String getCandidateKey()
    {
        return candidateKey;
    }

    public void setCandidateKey(String candidateKey)
    {
        this.candidateKey = candidateKey;
    }

    public String getEntityKey()
    {
        return entityKey;
    }

    public void setEntityKey(String entityKey)
    {
        this.entityKey = entityKey;
    }
}
