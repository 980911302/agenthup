package com.ruoyi.system.kb.parser;

import java.io.File;
import java.io.FileInputStream;
import java.util.List;
import javax.swing.text.Document;
import javax.swing.text.rtf.RTFEditorKit;
import org.springframework.stereotype.Component;

/**
 * RTF 富文本解析。JDK RTF reader 负责去控制字,IR 保留提取后的段落顺序。
 */
@Component
public class RtfParser implements KbParser
{
    @Override
    public boolean supports(String extension)
    {
        return "rtf".equalsIgnoreCase(extension);
    }

    @Override
    public IrDoc parse(File file, String fileName) throws Exception
    {
        RTFEditorKit kit = new RTFEditorKit();
        Document source = kit.createDefaultDocument();
        try (FileInputStream in = new FileInputStream(file))
        {
            kit.read(in, source, 0);
        }
        String text = source.getText(0, source.getLength());
        IrDoc doc = ParserSupport.newDocument(fileName, "rtf");
        int pos = 0;
        for (String part : text.split("\\R\\s*\\R|\\R"))
        {
            if (ParserSupport.addBlock(doc, pos, part, "paragraph", List.of(), null, null) != null)
            {
                pos++;
            }
        }
        return doc;
    }
}
