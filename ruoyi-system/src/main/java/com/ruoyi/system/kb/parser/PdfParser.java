package com.ruoyi.system.kb.parser;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * PDF 解析。使用 Spring AI PagePdfDocumentReader(底层 PDFBox)按页抽取，
 * 再转换为项目统一 IR；Reader 持有的 PDDocument 在本适配层显式关闭。
 */
@Component
public class PdfParser implements KbParser
{
    @Override
    public boolean supports(String extension)
    {
        return "pdf".equalsIgnoreCase(extension);
    }

    @Override
    public IrDoc parse(File file, String fileName) throws Exception
    {
        IrDoc irDoc = ParserSupport.newDocument(fileName, "pdf");
        PdfDocumentReaderConfig config = PdfDocumentReaderConfig.builder()
            .withPagesPerDocument(1)
            .withPageTopMargin(0)
            .withPageBottomMargin(0)
            .build();

        int position = 0;
        try (ManagedPagePdfDocumentReader reader = new ManagedPagePdfDocumentReader(
            new FileSystemResource(file), config))
        {
            List<Document> pages = reader.read();
            for (Document page : pages)
            {
                Map<String, Object> metadata = page.getMetadata();
                Integer pageFrom = integer(metadata.get(PagePdfDocumentReader.METADATA_START_PAGE_NUMBER));
                Integer pageTo = integer(metadata.get(PagePdfDocumentReader.METADATA_END_PAGE_NUMBER));
                if (pageTo == null)
                {
                    pageTo = pageFrom;
                }
                String pageLabel = label(pageFrom, pageTo);
                String text = page.getText();
                if (text == null || text.isBlank())
                {
                    continue;
                }
                String[] paragraphs = text.split("\\R\\s*\\R");
                for (String paragraph : paragraphs)
                {
                    String normalized = normalizeWhitespace(paragraph);
                    if (!normalized.isEmpty())
                    {
                        ParserSupport.addBlock(irDoc, position++, normalized, "paragraph",
                            List.of(), pageFrom, pageLabel);
                    }
                }
            }
        }
        return irDoc;
    }

    private static Integer integer(Object value)
    {
        if (value instanceof Number number)
        {
            return number.intValue();
        }
        if (value != null)
        {
            try
            {
                return Integer.valueOf(value.toString());
            }
            catch (NumberFormatException ignored)
            {
                // 非法页码不阻断正文抽取
            }
        }
        return null;
    }

    private static String label(Integer from, Integer to)
    {
        if (from == null)
        {
            return null;
        }
        if (to == null || from.equals(to))
        {
            return "第 " + from + " 页";
        }
        return "第 " + from + "–" + to + " 页";
    }

    private static String normalizeWhitespace(String text)
    {
        return text == null ? "" : text.replaceAll("[ \\t\\x0B\\f]+", " ")
            .replaceAll(" *\\R *", "\n")
            .replaceAll("\\n{3,}", "\n\n")
            .trim();
    }

    /** Spring AI Reader 未实现 AutoCloseable，这里补齐底层 PDF 文档释放。 */
    private static final class ManagedPagePdfDocumentReader extends PagePdfDocumentReader
        implements AutoCloseable
    {
        ManagedPagePdfDocumentReader(Resource resource, PdfDocumentReaderConfig config)
        {
            super(resource, config);
        }

        @Override
        public void close() throws IOException
        {
            document.close();
        }
    }
}
