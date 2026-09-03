package com.ruoyi.system.domain;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 图抽取 TextUnit 表 kb_graph_text_unit（与检索 LEAF 解耦）。
 */
public class KbGraphTextUnit
{
    private Long textUnitId;
    private Long kbId;
    private Long docId;
    private Integer ordinal;
    private String content;
    private String headingPath;
    private String blockType;
    private Integer sourcePageFrom;
    private Integer sourcePageTo;
    private String sourceLabel;
    private Integer tokenCount;
    private String contentHash;
    private String parserVersion;
    private String graphUnitVersion;
    private String unitParamsHash;
    private Long generation;
    private Long runId;
    private Date createTime;

    /** 非表列：映射的 LEAF chunkId */
    private List<Long> leafChunkIds = new ArrayList<>();

    public Long getTextUnitId()
    {
        return textUnitId;
    }

    public void setTextUnitId(Long textUnitId)
    {
        this.textUnitId = textUnitId;
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

    public Integer getOrdinal()
    {
        return ordinal;
    }

    public void setOrdinal(Integer ordinal)
    {
        this.ordinal = ordinal;
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

    public Integer getTokenCount()
    {
        return tokenCount;
    }

    public void setTokenCount(Integer tokenCount)
    {
        this.tokenCount = tokenCount;
    }

    public String getContentHash()
    {
        return contentHash;
    }

    public void setContentHash(String contentHash)
    {
        this.contentHash = contentHash;
    }

    public String getParserVersion()
    {
        return parserVersion;
    }

    public void setParserVersion(String parserVersion)
    {
        this.parserVersion = parserVersion;
    }

    public String getGraphUnitVersion()
    {
        return graphUnitVersion;
    }

    public void setGraphUnitVersion(String graphUnitVersion)
    {
        this.graphUnitVersion = graphUnitVersion;
    }

    public String getUnitParamsHash()
    {
        return unitParamsHash;
    }

    public void setUnitParamsHash(String unitParamsHash)
    {
        this.unitParamsHash = unitParamsHash;
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

    public Date getCreateTime()
    {
        return createTime;
    }

    public void setCreateTime(Date createTime)
    {
        this.createTime = createTime;
    }

    public List<Long> getLeafChunkIds()
    {
        return leafChunkIds;
    }

    public void setLeafChunkIds(List<Long> leafChunkIds)
    {
        this.leafChunkIds = leafChunkIds != null ? leafChunkIds : new ArrayList<>();
    }
}
