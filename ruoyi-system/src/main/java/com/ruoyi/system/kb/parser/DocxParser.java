package com.ruoyi.system.kb.parser;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.apache.poi.xwpf.usermodel.XWPFStyles;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;
import com.ruoyi.common.utils.StringUtils;

/**
 * DOCX 解析(poi-ooxml)。标题靠样式名 Heading 1/2/3 填充 headingPath。
 */
@Component
public class DocxParser implements KbParser
{
    @Override
    public boolean supports(String extension)
    {
        return "docx".equalsIgnoreCase(extension);
    }

    @Override
    public IrDoc parse(File file, String fileName) throws Exception
    {
        IrDoc doc = new IrDoc();
        doc.setSourceName(fileName);
        doc.setSourceType("docx");

        List<String> headingStack = new ArrayList<>();
        int pos = 0;

        try (FileInputStream in = new FileInputStream(file); XWPFDocument xwpf = new XWPFDocument(in))
        {
            XWPFStyles styles = xwpf.getStyles();
            for (IBodyElement element : xwpf.getBodyElements())
            {
                if (element instanceof XWPFParagraph para)
                {
                    String text = para.getText();
                    if (StringUtils.isEmpty(text) || text.isBlank())
                    {
                        continue;
                    }
                    text = text.trim();
                    Integer level = resolveHeadingLevel(para, styles);
                    if (level != null && level > 0)
                    {
                        while (headingStack.size() >= level)
                        {
                            headingStack.remove(headingStack.size() - 1);
                        }
                        while (headingStack.size() < level - 1)
                        {
                            headingStack.add("");
                        }
                        headingStack.add(text);

                        IrBlock block = new IrBlock();
                        block.setText(text);
                        block.setBlockType("heading");
                        block.setLevel(level);
                        block.setHeadingPath(new ArrayList<>(headingStack));
                        block.setPosition(pos++);
                        doc.getBlocks().add(block);
                    }
                    else
                    {
                        IrBlock block = new IrBlock();
                        block.setText(text);
                        block.setBlockType("paragraph");
                        block.setHeadingPath(new ArrayList<>(headingStack));
                        block.setPosition(pos++);
                        doc.getBlocks().add(block);
                    }
                }
                else if (element instanceof XWPFTable table)
                {
                    String html = tableToHtml(table);
                    IrTable irTable = new IrTable();
                    irTable.setHtml(html);
                    irTable.setPosition(pos);
                    doc.getTables().add(irTable);

                    // 表格同时作为 block 参与分块,保住行列关系
                    IrBlock block = new IrBlock();
                    block.setText(html);
                    block.setBlockType("table");
                    block.setHeadingPath(new ArrayList<>(headingStack));
                    block.setPosition(pos++);
                    doc.getBlocks().add(block);
                }
            }
        }
        return doc;
    }

    /**
     * 从段落样式解析标题层级。支持 Heading1 / heading 1 / 标题 1 等常见命名。
     */
    private Integer resolveHeadingLevel(XWPFParagraph para, XWPFStyles styles)
    {
        String styleId = para.getStyle();
        if (StringUtils.isEmpty(styleId))
        {
            return null;
        }
        String name = styleId;
        if (styles != null)
        {
            XWPFStyle style = styles.getStyle(styleId);
            if (style != null && style.getName() != null)
            {
                name = style.getName();
            }
        }
        String lower = name.toLowerCase().replace(" ", "");
        if (lower.startsWith("heading"))
        {
            return parseLevelSuffix(lower.substring("heading".length()));
        }
        if (lower.startsWith("标题"))
        {
            return parseLevelSuffix(lower.substring("标题".length()));
        }
        // outline level (0-based in OOXML)
        if (para.getCTP() != null && para.getCTP().getPPr() != null
            && para.getCTP().getPPr().getOutlineLvl() != null)
        {
            int lvl = para.getCTP().getPPr().getOutlineLvl().getVal().intValue();
            return lvl + 1;
        }
        return null;
    }

    private Integer parseLevelSuffix(String suffix)
    {
        if (suffix == null || suffix.isEmpty())
        {
            return 1;
        }
        try
        {
            return Integer.parseInt(suffix.replaceAll("[^0-9]", ""));
        }
        catch (NumberFormatException e)
        {
            return 1;
        }
    }

    private String tableToHtml(XWPFTable table)
    {
        StringBuilder sb = new StringBuilder("<table>");
        for (XWPFTableRow row : table.getRows())
        {
            sb.append("<tr>");
            for (XWPFTableCell cell : row.getTableCells())
            {
                sb.append("<td>").append(escapeHtml(cell.getText())).append("</td>");
            }
            sb.append("</tr>");
        }
        sb.append("</table>");
        return sb.toString();
    }

    private static String escapeHtml(String s)
    {
        if (s == null)
        {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
