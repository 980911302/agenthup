package com.ruoyi.system.kb.graph.provenance;

/**
 * 图抽取单元（阶段 1 可由 LEAF chunk 映射；阶段 2 解耦为独立 TextUnit）。
 * 本类不参与 KB-GR-02 生产写路径。
 */
public class GraphTextUnit
{
    private Long kbId;
    private Long docId;
    private Long chunkId;
    private String contentHash;
    private Long generation;
    private Long runId;
    private String content;
    private String headingPath;
    private Integer sourcePageFrom;
    private Integer sourcePageTo;
    private String sourceLabel;

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

    public Long getChunkId()
    {
        return chunkId;
    }

    public void setChunkId(Long chunkId)
    {
        this.chunkId = chunkId;
    }

    public String getContentHash()
    {
        return contentHash;
    }

    public void setContentHash(String contentHash)
    {
        this.contentHash = contentHash;
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

    public String getContent()
    {
        return content;
    }

    public void setContent(String content)
    {
        this.content = content;
    }

    public String getHeadingPath()
    {
        return headingPath;
    }

    public void setHeadingPath(String headingPath)
    {
        this.headingPath = headingPath;
    }

    public Integer getSourcePageFrom()
    {
        return sourcePageFrom;
    }

    public void setSourcePageFrom(Integer sourcePageFrom)
    {
        this.sourcePageFrom = sourcePageFrom;
    }

    public Integer getSourcePageTo()
    {
        return sourcePageTo;
    }

    public void setSourcePageTo(Integer sourcePageTo)
    {
        this.sourcePageTo = sourcePageTo;
    }

    public String getSourceLabel()
    {
        return sourceLabel;
    }

    public void setSourceLabel(String sourceLabel)
    {
        this.sourceLabel = sourceLabel;
    }
}
