package com.ruoyi.system.kb.parser;

import java.util.ArrayList;
import java.util.List;

/**
 * 解析中间表示。所有格式归一到此结构后再分块,加格式零成本。
 */
public class IrDoc
{
    /** 源文件名 */
    private String sourceName;

    /** 源扩展名 */
    private String sourceType;

    /** 实际使用的提取器,用于质量追踪和重建审计 */
    private String extractor;

    /** 是否因专用解析失败而使用了兜底提取器 */
    private boolean fallbackUsed;

    /** 文本块 */
    private List<IrBlock> blocks = new ArrayList<>();

    /** 表格(HTML) */
    private List<IrTable> tables = new ArrayList<>();

    /** 解析质量报告；在进入切片和向量化前生成 */
    private IrQualityReport quality;

    public String getSourceName()
    {
        return sourceName;
    }

    public void setSourceName(String sourceName)
    {
        this.sourceName = sourceName;
    }

    public String getSourceType()
    {
        return sourceType;
    }

    public void setSourceType(String sourceType)
    {
        this.sourceType = sourceType;
    }

    public String getExtractor()
    {
        return extractor;
    }

    public void setExtractor(String extractor)
    {
        this.extractor = extractor;
    }

    public boolean isFallbackUsed()
    {
        return fallbackUsed;
    }

    public void setFallbackUsed(boolean fallbackUsed)
    {
        this.fallbackUsed = fallbackUsed;
    }

    public List<IrBlock> getBlocks()
    {
        return blocks;
    }

    public void setBlocks(List<IrBlock> blocks)
    {
        this.blocks = blocks != null ? blocks : new ArrayList<>();
    }

    public List<IrTable> getTables()
    {
        return tables;
    }

    public void setTables(List<IrTable> tables)
    {
        this.tables = tables != null ? tables : new ArrayList<>();
    }

    public IrQualityReport getQuality()
    {
        return quality;
    }

    public void setQuality(IrQualityReport quality)
    {
        this.quality = quality;
    }
}
