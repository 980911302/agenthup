package com.ruoyi.system.kb.parser;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.apache.poi.hslf.usermodel.HSLFShape;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTextShape;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.springframework.stereotype.Component;

/**
 * PowerPoint PPT/PPTX 解析。按幻灯片保留顺序,每页首段作为标题路径。
 */
@Component
public class PresentationParser implements KbParser
{
    private static final Set<String> EXTS = Set.of("ppt", "pptx");

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
        if ("ppt".equals(ext))
        {
            parsePpt(doc, file);
        }
        else
        {
            parsePptx(doc, file);
        }
        return doc;
    }

    private void parsePptx(IrDoc doc, File file) throws Exception
    {
        int pos = 0;
        try (FileInputStream in = new FileInputStream(file); XMLSlideShow show = new XMLSlideShow(in))
        {
            int slideNumber = 0;
            for (XSLFSlide slide : show.getSlides())
            {
                slideNumber++;
                List<String> texts = new ArrayList<>();
                for (XSLFShape shape : slide.getShapes())
                {
                    if (shape instanceof XSLFTextShape textShape)
                    {
                        String text = ParserSupport.normalize(textShape.getText());
                        if (!text.isEmpty())
                        {
                            texts.add(text);
                        }
                    }
                }
                pos = appendSlide(doc, pos, slideNumber, texts);
            }
        }
    }

    private void parsePpt(IrDoc doc, File file) throws Exception
    {
        int pos = 0;
        try (FileInputStream in = new FileInputStream(file); HSLFSlideShow show = new HSLFSlideShow(in))
        {
            int slideNumber = 0;
            for (HSLFSlide slide : show.getSlides())
            {
                slideNumber++;
                List<String> texts = new ArrayList<>();
                for (HSLFShape shape : slide.getShapes())
                {
                    if (shape instanceof HSLFTextShape textShape)
                    {
                        String text = ParserSupport.normalize(textShape.getText());
                        if (!text.isEmpty())
                        {
                            texts.add(text);
                        }
                    }
                }
                pos = appendSlide(doc, pos, slideNumber, texts);
            }
        }
    }

    private int appendSlide(IrDoc doc, int position, int slideNumber, List<String> texts)
    {
        String slideLabel = "幻灯片 " + slideNumber;
        String title = texts.isEmpty() ? slideLabel : firstLine(texts.get(0));
        List<String> path = List.of(slideLabel, title);
        ParserSupport.addHeading(doc, position++, title, 1, path, slideNumber, slideLabel);
        for (String text : texts)
        {
            if (text.equals(title) || text.isEmpty())
            {
                continue;
            }
            if (ParserSupport.addBlock(doc, position, text, "paragraph", path,
                slideNumber, slideLabel) != null)
            {
                position++;
            }
        }
        return position;
    }

    private static String firstLine(String text)
    {
        int i = text.indexOf('\n');
        String first = i >= 0 ? text.substring(0, i) : text;
        return first.length() <= 160 ? first : first.substring(0, 160);
    }
}
