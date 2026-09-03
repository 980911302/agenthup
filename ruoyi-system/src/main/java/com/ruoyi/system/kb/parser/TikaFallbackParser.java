package com.ruoyi.system.kb.parser;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;

/**
 * Apache Tika 专业解析兜底。正常情况仍优先走格式专用解析器，以保留表格、页码和标题层级。
 */
@Component
public class TikaFallbackParser implements KbParser
{
    /** 防止恶意或损坏文件产生无限文本导致堆内存耗尽。 */
    private static final int MAX_EXTRACTED_CHARS = 10_000_000;

    @Override
    public boolean isFallback()
    {
        return true;
    }

    @Override
    public boolean supports(String extension)
    {
        return extension != null
            && Arrays.asList(KbFileFormats.allowedExtensions()).contains(extension.toLowerCase());
    }

    @Override
    public IrDoc parse(File file, String fileName)
    {
        String extension = ParserSupport.extensionOf(fileName);
        IrDoc irDoc = ParserSupport.newDocument(fileName, extension);
        TikaDocumentReader reader = new TikaDocumentReader(
            new FileSystemResource(file),
            new BodyContentHandler(MAX_EXTRACTED_CHARS),
            ExtractedTextFormatter.defaults());

        int position = 0;
        List<Document> documents = reader.read();
        for (Document document : documents)
        {
            String text = document.getText();
            if (text == null || text.isBlank())
            {
                continue;
            }
            String[] paragraphs = text.split("\\R\\s*\\R");
            for (String paragraph : paragraphs)
            {
                String normalized = ParserSupport.normalize(paragraph);
                if (!normalized.isEmpty())
                {
                    ParserSupport.addBlock(irDoc, position++, normalized, "paragraph",
                        List.of(), null, "Apache Tika");
                }
            }
        }
        return irDoc;
    }
}
