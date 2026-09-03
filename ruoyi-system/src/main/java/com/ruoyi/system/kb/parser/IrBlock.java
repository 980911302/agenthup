package com.ruoyi.system.kb.parser;

import java.util.ArrayList;
import java.util.List;

/**
 * IR 文本块。
 */
public class IrBlock
{
    /** 正文 */
    private String text;

    /** 块类型:paragraph / heading / list_item / code / table */
    private String blockType;

    /** 章节路径各段(不含分隔符) */
    private List<String> headingPath = new ArrayList<>();

    /** 标题层级(heading 用,1-based) */
    private Integer level;

    /** 在文档中的顺序位置 */
    private int position;

    /** 来源页码(PDF 页/幻灯片页),1-based;无分页格式为空 */
    private Integer pageNumber;

    /** 来源标签,如工作表名、幻灯片标题 */
    private String sourceLabel;

    public String getText()
    {
        return text;
    }

    public void setText(String text)
    {
        this.text = text;
    }

    public String getBlockType()
    {
        return blockType;
    }

    public void setBlockType(String blockType)
    {
        this.blockType = blockType;
    }

    public List<String> getHeadingPath()
    {
        return headingPath;
    }

    public void setHeadingPath(List<String> headingPath)
    {
        this.headingPath = headingPath != null ? headingPath : new ArrayList<>();
    }

    public Integer getLevel()
    {
        return level;
    }

    public void setLevel(Integer level)
    {
        this.level = level;
    }

    public int getPosition()
    {
        return position;
    }

    public void setPosition(int position)
    {
        this.position = position;
    }

    public Integer getPageNumber()
    {
        return pageNumber;
    }

    public void setPageNumber(Integer pageNumber)
    {
        this.pageNumber = pageNumber;
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
