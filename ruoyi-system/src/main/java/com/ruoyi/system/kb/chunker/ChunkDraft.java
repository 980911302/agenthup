package com.ruoyi.system.kb.chunker;

/**
 * 分块草稿(落库前,尚无 kb/doc id 与向量)。
 */
public class ChunkDraft
{
    private int chunkIndex;
    private String content;
    private String headingPath;
    private String blockType;
    private int tokenCount;
    private String chunkerStrategy;
    private String chunkParamsHash;
    private Integer sourcePageFrom;
    private Integer sourcePageTo;
    private String sourceLabel;
    private String chunkLevel = "LEAF";

    /**
     * PARENT 草稿暂存子 LEAF 的 chunkIndex 列表（入库前用，不落库）。
     */
    private java.util.List<Integer> childLeafIndices;

    public int getChunkIndex()
    {
        return chunkIndex;
    }

    public void setChunkIndex(int chunkIndex)
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

    public String getBlockType()
    {
        return blockType;
    }

    public void setBlockType(String blockType)
    {
        this.blockType = blockType;
    }

    public int getTokenCount()
    {
        return tokenCount;
    }

    public void setTokenCount(int tokenCount)
    {
        this.tokenCount = tokenCount;
    }

    public String getChunkerStrategy()
    {
        return chunkerStrategy;
    }

    public void setChunkerStrategy(String chunkerStrategy)
    {
        this.chunkerStrategy = chunkerStrategy;
    }

    public String getChunkParamsHash()
    {
        return chunkParamsHash;
    }

    public void setChunkParamsHash(String chunkParamsHash)
    {
        this.chunkParamsHash = chunkParamsHash;
    }

    public Integer getSourcePageFrom() { return sourcePageFrom; }
    public void setSourcePageFrom(Integer sourcePageFrom) { this.sourcePageFrom = sourcePageFrom; }
    public Integer getSourcePageTo() { return sourcePageTo; }
    public void setSourcePageTo(Integer sourcePageTo) { this.sourcePageTo = sourcePageTo; }
    public String getSourceLabel() { return sourceLabel; }
    public void setSourceLabel(String sourceLabel) { this.sourceLabel = sourceLabel; }
    public String getChunkLevel() { return chunkLevel; }
    public void setChunkLevel(String chunkLevel) { this.chunkLevel = chunkLevel; }

    public java.util.List<Integer> getChildLeafIndices()
    {
        return childLeafIndices;
    }

    public void setChildLeafIndices(java.util.List<Integer> childLeafIndices)
    {
        this.childLeafIndices = childLeafIndices;
    }
}
