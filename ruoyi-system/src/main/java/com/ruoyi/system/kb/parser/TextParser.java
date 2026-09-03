package com.ruoyi.system.kb.parser;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import com.ruoyi.common.utils.StringUtils;

/**
 * TXT / Markdown 解析。MD 按 # 层级填充 headingPath。
 */
@Component
public class TextParser implements KbParser
{
    private static final Set<String> EXTS = Set.of("txt", "md", "markdown");
    private static final Pattern MD_HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$");

    @Override
    public boolean supports(String extension)
    {
        return extension != null && EXTS.contains(extension.toLowerCase());
    }

    @Override
    public IrDoc parse(File file, String fileName) throws Exception
    {
        String text = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        IrDoc doc = new IrDoc();
        doc.setSourceName(fileName);
        String ext = extensionOf(fileName);
        doc.setSourceType(ext);

        if ("md".equals(ext) || "markdown".equals(ext))
        {
            parseMarkdown(doc, text);
        }
        else
        {
            parsePlain(doc, text);
        }
        return doc;
    }

    private void parseMarkdown(IrDoc doc, String text)
    {
        List<String> headingStack = new ArrayList<>();
        int pos = 0;
        String[] lines = text.split("\\R", -1);
        StringBuilder para = new StringBuilder();

        for (String line : lines)
        {
            Matcher m = MD_HEADING.matcher(line);
            if (m.matches())
            {
                flushParagraph(doc, para, headingStack, pos++);
                int level = m.group(1).length();
                String title = m.group(2).trim();
                // 调整标题栈到当前层级
                while (headingStack.size() >= level)
                {
                    headingStack.remove(headingStack.size() - 1);
                }
                while (headingStack.size() < level - 1)
                {
                    headingStack.add("");
                }
                headingStack.add(title);

                IrBlock block = new IrBlock();
                block.setText(title);
                block.setBlockType("heading");
                block.setLevel(level);
                block.setHeadingPath(new ArrayList<>(headingStack));
                block.setPosition(pos++);
                doc.getBlocks().add(block);
            }
            else if (line.trim().isEmpty())
            {
                flushParagraph(doc, para, headingStack, pos++);
            }
            else
            {
                if (para.length() > 0)
                {
                    para.append('\n');
                }
                para.append(line);
            }
        }
        flushParagraph(doc, para, headingStack, pos);
    }

    private void parsePlain(IrDoc doc, String text)
    {
        int pos = 0;
        // 按空行切段,无标题层级
        String[] parts = text.split("\\R\\s*\\R");
        for (String part : parts)
        {
            String t = part.trim();
            if (t.isEmpty())
            {
                continue;
            }
            IrBlock block = new IrBlock();
            block.setText(t);
            block.setBlockType("paragraph");
            block.setHeadingPath(List.of());
            block.setPosition(pos++);
            doc.getBlocks().add(block);
        }
        if (doc.getBlocks().isEmpty() && StringUtils.isNotEmpty(text.trim()))
        {
            IrBlock block = new IrBlock();
            block.setText(text.trim());
            block.setBlockType("paragraph");
            block.setHeadingPath(List.of());
            block.setPosition(0);
            doc.getBlocks().add(block);
        }
    }

    private void flushParagraph(IrDoc doc, StringBuilder para, List<String> headingStack, int pos)
    {
        if (para.length() == 0)
        {
            return;
        }
        String t = para.toString().trim();
        para.setLength(0);
        if (t.isEmpty())
        {
            return;
        }
        IrBlock block = new IrBlock();
        block.setText(t);
        block.setBlockType("paragraph");
        block.setHeadingPath(new ArrayList<>(headingStack));
        block.setPosition(pos);
        doc.getBlocks().add(block);
    }

    private static String extensionOf(String fileName)
    {
        if (fileName == null)
        {
            return "txt";
        }
        int i = fileName.lastIndexOf('.');
        return i >= 0 ? fileName.substring(i + 1).toLowerCase() : "txt";
    }
}
