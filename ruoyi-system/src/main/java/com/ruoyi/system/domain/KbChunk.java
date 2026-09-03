package com.ruoyi.system.domain;

import java.util.Date;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

/**
 * 知识库分块表 kb_chunk
 *
 * @author ruoyi
 */
public class KbChunk
{
    private static final long serialVersionUID = 1L;

    /** 分块ID */
    private Long chunkId;

    /** 知识库ID */
    private Long kbId;

    /** 文档ID */
    private Long docId;

    /** 在文档内的序号 */
    private Integer chunkIndex;

    /** 分块原文 */
    private String content;

    /** 章节面包屑,用 → 分隔 */
    private String headingPath;

    /** 来源块类型 */
    private String blockType;

    /** 估算 token 数 */
    private Integer tokenCount;

    /** 向量维度(向量本体在 kb_vector_{dim},不在本表) */
    private Integer embeddingDim;

    /** 分块策略(F/P) */
    private String chunkerStrategy;

    /** 分块参数指纹 */
    private String chunkParamsHash;

    /** 嵌入模型 code */
    private String embeddingModel;

    /** 来源起始页,1-based */
    private Integer sourcePageFrom;

    /** 来源结束页,1-based */
    private Integer sourcePageTo;

    /** 来源标签,如工作表、幻灯片或结构路径 */
    private String sourceLabel;

    /** 层级分块类型:LEAF/PARENT/SUMMARY */
    private String chunkLevel;

    /** 父分块ID,为后续 Parent-Child/GraphRAG 检索预留 */
    private Long parentChunkId;

    /** 创建时间 */
    private Date createTime;

    // ---- 检索/展示用关联字段(非表列) ----
    /** 文档名称 */
    private String docName;

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

    public String getBlockType()
    {
        return blockType;
    }

    public void setBlockType(String blockType)
    {
        this.blockType = blockType;
    }

    public Integer getTokenCount()
    {
        return tokenCount;
    }

    public void setTokenCount(Integer tokenCount)
    {
        this.tokenCount = tokenCount;
    }

    public Integer getEmbeddingDim()
    {
        return embeddingDim;
    }

    public void setEmbeddingDim(Integer embeddingDim)
    {
        this.embeddingDim = embeddingDim;
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

    public String getEmbeddingModel()
    {
        return embeddingModel;
    }

    public void setEmbeddingModel(String embeddingModel)
    {
        this.embeddingModel = embeddingModel;
    }

    public Integer getSourcePageFrom() { return sourcePageFrom; }
    public void setSourcePageFrom(Integer sourcePageFrom) { this.sourcePageFrom = sourcePageFrom; }
    public Integer getSourcePageTo() { return sourcePageTo; }
    public void setSourcePageTo(Integer sourcePageTo) { this.sourcePageTo = sourcePageTo; }
    public String getSourceLabel() { return sourceLabel; }
    public void setSourceLabel(String sourceLabel) { this.sourceLabel = sourceLabel; }
    public String getChunkLevel() { return chunkLevel; }
    public void setChunkLevel(String chunkLevel) { this.chunkLevel = chunkLevel; }
    public Long getParentChunkId() { return parentChunkId; }
    public void setParentChunkId(Long parentChunkId) { this.parentChunkId = parentChunkId; }

    public Date getCreateTime()
    {
        return createTime;
    }

    public void setCreateTime(Date createTime)
    {
        this.createTime = createTime;
    }

    public String getDocName()
    {
        return docName;
    }

    public void setDocName(String docName)
    {
        this.docName = docName;
    }

    @Override
    public String toString()
    {
        return new ToStringBuilder(this, ToStringStyle.MULTI_LINE_STYLE)
            .append("chunkId", getChunkId())
            .append("kbId", getKbId())
            .append("docId", getDocId())
            .append("chunkIndex", getChunkIndex())
            .append("headingPath", getHeadingPath())
            .append("blockType", getBlockType())
            .append("tokenCount", getTokenCount())
            .append("embeddingDim", getEmbeddingDim())
            .append("chunkerStrategy", getChunkerStrategy())
            .append("embeddingModel", getEmbeddingModel())
            .append("sourcePageFrom", getSourcePageFrom())
            .append("sourcePageTo", getSourcePageTo())
            .append("sourceLabel", getSourceLabel())
            .append("chunkLevel", getChunkLevel())
            .append("parentChunkId", getParentChunkId())
            .toString();
    }
}
