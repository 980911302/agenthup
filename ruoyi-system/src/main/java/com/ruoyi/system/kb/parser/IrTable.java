package com.ruoyi.system.kb.parser;

/**
 * IR 表格(HTML 保留行列关系,参与分块时当文本块处理)。
 */
public class IrTable
{
    /** 表格 HTML */
    private String html;

    /** 标题/说明 */
    private String caption;

    /** 位置 */
    private int position;

    public String getHtml()
    {
        return html;
    }

    public void setHtml(String html)
    {
        this.html = html;
    }

    public String getCaption()
    {
        return caption;
    }

    public void setCaption(String caption)
    {
        this.caption = caption;
    }

    public int getPosition()
    {
        return position;
    }

    public void setPosition(int position)
    {
        this.position = position;
    }
}
