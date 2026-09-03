package com.ruoyi.system.kb.parser;

import java.util.ArrayList;
import java.util.List;

/**
 * 格式解析器共用的 IR 构造与文本清洗工具。
 */
final class ParserSupport
{
    private ParserSupport()
    {
    }

    static IrDoc newDocument(String fileName, String sourceType)
    {
        IrDoc doc = new IrDoc();
        doc.setSourceName(fileName);
        doc.setSourceType(sourceType);
        return doc;
    }

    static IrBlock addBlock(IrDoc doc, int position, String text, String blockType,
                            List<String> headingPath, Integer pageNumber, String sourceLabel)
    {
        String normalized = normalize(text);
        if (normalized.isEmpty())
        {
            return null;
        }
        IrBlock block = new IrBlock();
        block.setText(normalized);
        block.setBlockType(blockType != null ? blockType : "paragraph");
        block.setHeadingPath(headingPath != null ? new ArrayList<>(headingPath) : new ArrayList<>());
        block.setPosition(position);
        block.setPageNumber(pageNumber);
        block.setSourceLabel(sourceLabel);
        doc.getBlocks().add(block);
        return block;
    }

    static IrBlock addHeading(IrDoc doc, int position, String text, int level,
                              List<String> headingPath, Integer pageNumber, String sourceLabel)
    {
        IrBlock block = addBlock(doc, position, text, "heading", headingPath, pageNumber, sourceLabel);
        if (block != null)
        {
            block.setLevel(level);
        }
        return block;
    }

    static String normalize(String text)
    {
        if (text == null)
        {
            return "";
        }
        return text.replace('\u00A0', ' ')
            .replaceAll("[ \\t\\x0B\\f]+", " ")
            .replaceAll(" *\\R *", "\n")
            .replaceAll("\\n{3,}", "\n\n")
            .trim();
    }

    static String extensionOf(String fileName)
    {
        if (fileName == null)
        {
            return "";
        }
        int i = fileName.lastIndexOf('.');
        return i >= 0 ? fileName.substring(i + 1).toLowerCase() : "";
    }
}
