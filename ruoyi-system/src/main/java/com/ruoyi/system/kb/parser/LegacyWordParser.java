package com.ruoyi.system.kb.parser;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.springframework.stereotype.Component;

/**
 * 老版 Word DOC 解析。二进制 DOC 很难稳定还原样式,至少保留段落顺序并启发式识别标题。
 */
@Component
public class LegacyWordParser implements KbParser
{
    private static final Pattern HEADING = Pattern.compile(
        "^(第[一二三四五六七八九十百千0-9]+[章节篇部]|[0-9]+(?:\\.[0-9]+){0,5})[、.．\\s]+(.+)$");

    @Override
    public boolean supports(String extension)
    {
        return "doc".equalsIgnoreCase(extension);
    }

    @Override
    public IrDoc parse(File file, String fileName) throws Exception
    {
        IrDoc doc = ParserSupport.newDocument(fileName, "doc");
        List<String> headingPath = new ArrayList<>();
        int pos = 0;
        try (FileInputStream in = new FileInputStream(file);
             HWPFDocument hwpf = new HWPFDocument(in);
             WordExtractor extractor = new WordExtractor(hwpf))
        {
            for (String paragraph : extractor.getParagraphText())
            {
                String text = ParserSupport.normalize(paragraph);
                if (text.isEmpty())
                {
                    continue;
                }
                Matcher matcher = HEADING.matcher(text);
                if (matcher.matches() && text.length() <= 120)
                {
                    int level = headingLevel(text);
                    while (headingPath.size() >= level)
                    {
                        headingPath.remove(headingPath.size() - 1);
                    }
                    while (headingPath.size() < level - 1)
                    {
                        headingPath.add("");
                    }
                    headingPath.add(text);
                    ParserSupport.addHeading(doc, pos++, text, level, headingPath, null, null);
                }
                else
                {
                    ParserSupport.addBlock(doc, pos++, text, "paragraph", headingPath, null, null);
                }
            }
        }
        return doc;
    }

    private static int headingLevel(String text)
    {
        Matcher matcher = Pattern.compile("^([0-9]+(?:\\.[0-9]+)*)").matcher(text);
        if (!matcher.find())
        {
            return 1;
        }
        return Math.min(6, matcher.group(1).split("\\.").length);
    }
}
