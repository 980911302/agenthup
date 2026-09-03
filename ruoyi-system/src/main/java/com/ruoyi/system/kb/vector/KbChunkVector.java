package com.ruoyi.system.kb.vector;

/**
 * 内存索引条目。
 */
public class KbChunkVector
{
    private Long chunkId;
    private Long kbId;
    private Long docId;
    private Integer chunkIndex;
    private String content;
    private String headingPath;
    private String docName;
    private float[] embedding;

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

    public String getHeadingPath()
    {
        return headingPath;
    }

    public void setHeadingPath(String headingPath)
    {
        this.headingPath = headingPath;
    }

    public String getDocName()
    {
        return docName;
    }

    public void setDocName(String docName)
    {
        this.docName = docName;
    }

    public float[] getEmbedding()
    {
        return embedding;
    }

    public void setEmbedding(float[] embedding)
    {
        this.embedding = embedding;
    }
}
