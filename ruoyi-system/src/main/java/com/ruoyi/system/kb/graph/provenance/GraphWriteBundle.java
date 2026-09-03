package com.ruoyi.system.kb.graph.provenance;

import java.util.ArrayList;
import java.util.List;
import com.ruoyi.system.kb.graph.GraphEntity;
import com.ruoyi.system.kb.graph.GraphRelation;

/**
 * 单次文档图提交包：证据 + 兼容聚合视图。
 */
public class GraphWriteBundle
{
    private Long kbId;
    private Long docId;
    private Long generation;
    private Long runId;
    private List<GraphTextUnit> textUnits = new ArrayList<>();
    private List<GraphEntity> entities = new ArrayList<>();
    private List<GraphRelation> relations = new ArrayList<>();
    private List<GraphRelationEvidence> evidences = new ArrayList<>();

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

    public Long getGeneration()
    {
        return generation;
    }

    public void setGeneration(Long generation)
    {
        this.generation = generation;
    }

    public Long getRunId()
    {
        return runId;
    }

    public void setRunId(Long runId)
    {
        this.runId = runId;
    }

    public List<GraphTextUnit> getTextUnits()
    {
        return textUnits;
    }

    public void setTextUnits(List<GraphTextUnit> textUnits)
    {
        this.textUnits = textUnits != null ? textUnits : new ArrayList<>();
    }

    public List<GraphEntity> getEntities()
    {
        return entities;
    }

    public void setEntities(List<GraphEntity> entities)
    {
        this.entities = entities != null ? entities : new ArrayList<>();
    }

    public List<GraphRelation> getRelations()
    {
        return relations;
    }

    public void setRelations(List<GraphRelation> relations)
    {
        this.relations = relations != null ? relations : new ArrayList<>();
    }

    public List<GraphRelationEvidence> getEvidences()
    {
        return evidences;
    }

    public void setEvidences(List<GraphRelationEvidence> evidences)
    {
        this.evidences = evidences != null ? evidences : new ArrayList<>();
    }
}
