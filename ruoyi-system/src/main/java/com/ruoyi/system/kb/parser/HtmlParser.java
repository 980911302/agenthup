package com.ruoyi.system.kb.parser;

import java.io.File;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;
import org.springframework.stereotype.Component;

/**
 * HTML/HTM 结构解析。忽略脚本与样式,保留标题、段落、列表、代码、引用和表格行。
 */
@Component
public class HtmlParser implements KbParser
{
    private static final Set<String> EXTS = Set.of("html", "htm");

    @Override
    public boolean supports(String extension)
    {
        return extension != null && EXTS.contains(extension.toLowerCase());
    }

    @Override
    public IrDoc parse(File file, String fileName) throws Exception
    {
        String ext = ParserSupport.extensionOf(fileName);
        IrDoc doc = ParserSupport.newDocument(fileName, ext);
        HtmlCollector collector = new HtmlCollector(doc);
        try (FileReader reader = new FileReader(file, StandardCharsets.UTF_8))
        {
            new ParserDelegator().parse(reader, collector, true);
        }
        collector.finish();
        return doc;
    }

    private static final class HtmlCollector extends HTMLEditorKit.ParserCallback
    {
        private final IrDoc doc;
        private final List<String> headingPath = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();
        private HTML.Tag currentTag;
        private String blockType;
        private int headingLevel;
        private int ignoreDepth;
        private int position;

        private HtmlCollector(IrDoc doc)
        {
            this.doc = doc;
        }

        @Override
        public void handleStartTag(HTML.Tag tag, MutableAttributeSet attributes, int pos)
        {
            if (tag == HTML.Tag.SCRIPT || tag == HTML.Tag.STYLE)
            {
                ignoreDepth++;
                return;
            }
            if (ignoreDepth > 0)
            {
                return;
            }
            int level = headingLevel(tag);
            if (level > 0)
            {
                flushBlock();
                currentTag = tag;
                blockType = "heading";
                headingLevel = level;
            }
            else if (tag == HTML.Tag.P || tag == HTML.Tag.LI || tag == HTML.Tag.PRE
                || tag == HTML.Tag.BLOCKQUOTE || tag == HTML.Tag.TR)
            {
                flushBlock();
                currentTag = tag;
                blockType = blockType(tag);
            }
            else if ((tag == HTML.Tag.TD || tag == HTML.Tag.TH) && text.length() > 0)
            {
                text.append(" | ");
            }
        }

        @Override
        public void handleEndTag(HTML.Tag tag, int pos)
        {
            if (tag == HTML.Tag.SCRIPT || tag == HTML.Tag.STYLE)
            {
                ignoreDepth = Math.max(0, ignoreDepth - 1);
                return;
            }
            if (ignoreDepth > 0)
            {
                return;
            }
            if (tag == currentTag)
            {
                flushBlock();
            }
        }

        @Override
        public void handleSimpleTag(HTML.Tag tag, MutableAttributeSet attributes, int pos)
        {
            if (ignoreDepth == 0 && tag == HTML.Tag.BR)
            {
                text.append('\n');
            }
        }

        @Override
        public void handleText(char[] data, int pos)
        {
            if (ignoreDepth > 0)
            {
                return;
            }
            if (text.length() > 0 && !Character.isWhitespace(text.charAt(text.length() - 1)))
            {
                text.append(' ');
            }
            text.append(data);
        }

        private void finish()
        {
            flushBlock();
        }

        private void flushBlock()
        {
            String value = ParserSupport.normalize(text.toString());
            text.setLength(0);
            if (value.isEmpty())
            {
                currentTag = null;
                blockType = null;
                headingLevel = 0;
                return;
            }
            if ("heading".equals(blockType))
            {
                while (headingPath.size() >= headingLevel)
                {
                    headingPath.remove(headingPath.size() - 1);
                }
                while (headingPath.size() < headingLevel - 1)
                {
                    headingPath.add("");
                }
                headingPath.add(value);
                ParserSupport.addHeading(doc, position++, value, headingLevel,
                    headingPath, null, null);
            }
            else
            {
                ParserSupport.addBlock(doc, position++, value,
                    blockType != null ? blockType : "paragraph", headingPath, null, null);
            }
            currentTag = null;
            blockType = null;
            headingLevel = 0;
        }

        private static int headingLevel(HTML.Tag tag)
        {
            if (tag == HTML.Tag.H1) return 1;
            if (tag == HTML.Tag.H2) return 2;
            if (tag == HTML.Tag.H3) return 3;
            if (tag == HTML.Tag.H4) return 4;
            if (tag == HTML.Tag.H5) return 5;
            if (tag == HTML.Tag.H6) return 6;
            return 0;
        }

        private static String blockType(HTML.Tag tag)
        {
            if (tag == HTML.Tag.LI) return "list_item";
            if (tag == HTML.Tag.PRE) return "code";
            if (tag == HTML.Tag.BLOCKQUOTE) return "quote";
            if (tag == HTML.Tag.TR) return "table_row";
            return "paragraph";
        }
    }
}
