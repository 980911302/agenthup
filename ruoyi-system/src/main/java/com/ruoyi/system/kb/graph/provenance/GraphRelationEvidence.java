package com.ruoyi.system.kb.graph.provenance;

/**
 * 关系证据节点：事实来源，可按 docId/generation 精确删除。
 * 本类不参与 KB-GR-02 生产写路径。
 */
public class GraphRelationEvidence
{
    private Long kbId;
    private Long docId;
    private String evidenceKey;
    private String predicate;
    private String description;
    private Long generation;
    private Long runId;
    private String sourceEntityKey;
    private String targetEntityKey;
    private Long textUnitChunkId;

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

    public String getEvidenceKey()
    {
        return evidenceKey;
    }

    public void setEvidenceKey(String evidenceKey)
    {
        this.evidenceKey = evidenceKey;
    }

    public String getPredicate()
    {
        return predicate;
    }

    public void setPredicate(String predicate)
    {
        this.predicate = predicate;
    }

    public String getDescription()
    {
        return description;
    }

    public void setDescription(String description)
    {
        this.description = description;
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

    public Long getTextUnitChunkId()
    {
        return textUnitChunkId;
    }

    public void setTextUnitChunkId(Long textUnitChunkId)
    {
        this.textUnitChunkId = textUnitChunkId;
    }
}
