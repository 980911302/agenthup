package com.ruoyi.system.kb.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommonFormatParserTest
{
    @TempDir
    Path tempDir;

    @Test
    void delimitedParserKeepsQuotedCommaAndNewline() throws Exception
    {
        Path file = write("people.csv", "name,note\n张三,\"研发,平台\"\n李四,\"跨行\n说明\"\n");
        IrDoc doc = new DelimitedTextParser().parse(file.toFile(), "people.csv");

        assertEquals(3, doc.getBlocks().size());
        assertTrue(doc.getBlocks().get(1).getText().contains("note: 研发,平台"));
        assertTrue(doc.getBlocks().get(2).getText().contains("跨行\n说明"));
    }

    @Test
    void htmlParserPreservesStructureAndDropsScript() throws Exception
    {
        Path file = write("guide.html",
            "<html><body><h1>使用指南</h1><p>安装说明</p><script>bad()</script>"
                + "<ul><li>第一步</li><li>第二步</li></ul></body></html>");
        IrDoc doc = new HtmlParser().parse(file.toFile(), "guide.html");

        assertEquals("heading", doc.getBlocks().get(0).getBlockType());
        assertTrue(doc.getBlocks().stream().anyMatch(b -> "list_item".equals(b.getBlockType())));
        assertFalse(doc.getBlocks().stream().anyMatch(b -> b.getText().contains("bad")));
    }

    @Test
    void jsonAndXmlKeepStructuralSourceLabels() throws Exception
    {
        Path json = write("config.json", "{\"service\":{\"name\":\"agent\",\"port\":8080},\"enabled\":true}");
        IrDoc jsonDoc = new JsonParser().parse(json.toFile(), "config.json");
        assertTrue(jsonDoc.getBlocks().stream().anyMatch(b -> "$.service".equals(b.getSourceLabel())));
        assertTrue(jsonDoc.getBlocks().stream().anyMatch(b -> b.getText().contains("enabled: true")));

        Path xml = write("config.xml", "<root><service id=\"1\"><name>agent</name></service></root>");
        IrDoc xmlDoc = new XmlParser().parse(xml.toFile(), "config.xml");
        assertTrue(xmlDoc.getBlocks().stream().anyMatch(b -> "/root/service".equals(b.getSourceLabel())));
        assertTrue(xmlDoc.getBlocks().stream().anyMatch(b -> b.getText().contains("agent")));
    }

    @Test
    void spreadsheetParserKeepsSheetAndHeaderContext() throws Exception
    {
        Path file = tempDir.resolve("products.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(file))
        {
            var sheet = workbook.createSheet("产品");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("名称");
            header.createCell(1).setCellValue("价格");
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("标准版");
            row.createCell(1).setCellValue(99);
            workbook.write(out);
        }

        IrDoc doc = new SpreadsheetParser().parse(file.toFile(), "products.xlsx");
        assertEquals("产品", doc.getBlocks().get(0).getText());
        assertTrue(doc.getBlocks().stream().anyMatch(b ->
            "table_row".equals(b.getBlockType()) && b.getText().contains("名称: 标准版")));
        assertTrue(doc.getBlocks().stream().allMatch(b -> "产品".equals(b.getSourceLabel())));
    }

    @Test
    void presentationParserKeepsSlideNumberAndTitle() throws Exception
    {
        Path file = tempDir.resolve("intro.pptx");
        try (XMLSlideShow show = new XMLSlideShow(); OutputStream out = Files.newOutputStream(file))
        {
            XSLFSlide slide = show.createSlide();
            slide.createTextBox().setText("项目介绍");
            slide.createTextBox().setText("这是正文");
            show.write(out);
        }

        IrDoc doc = new PresentationParser().parse(file.toFile(), "intro.pptx");
        assertEquals("项目介绍", doc.getBlocks().get(0).getText());
        assertEquals(1, doc.getBlocks().get(0).getPageNumber());
        assertTrue(doc.getBlocks().stream().anyMatch(b -> b.getText().contains("这是正文")));
    }

    @Test
    void springAiPdfReaderKeepsPageMetadata() throws Exception
    {
        Path file = tempDir.resolve("guide.pdf");
        try (PDDocument pdf = new PDDocument())
        {
            addPdfPage(pdf, "First page knowledge content");
            addPdfPage(pdf, "Second page graph content");
            pdf.save(file.toFile());
        }

        IrDoc doc = new PdfParser().parse(file.toFile(), "guide.pdf");

        assertTrue(doc.getBlocks().stream().anyMatch(block ->
            Integer.valueOf(1).equals(block.getPageNumber()) && block.getText().contains("First page")));
        assertTrue(doc.getBlocks().stream().anyMatch(block ->
            Integer.valueOf(2).equals(block.getPageNumber()) && block.getText().contains("Second page")));
        assertTrue(doc.getBlocks().stream().anyMatch(block -> "第 2 页".equals(block.getSourceLabel())));
    }

    @Test
    void tikaFallbackUsesProfessionalOfficeExtraction() throws Exception
    {
        Path file = tempDir.resolve("fallback.docx");
        try (XWPFDocument word = new XWPFDocument(); OutputStream out = Files.newOutputStream(file))
        {
            word.createParagraph().createRun().setText("Professional Tika fallback content");
            word.write(out);
        }

        TikaFallbackParser parser = new TikaFallbackParser();
        IrDoc doc = parser.parse(file.toFile(), "fallback.docx");

        assertTrue(parser.isFallback());
        assertTrue(doc.getBlocks().stream().anyMatch(block ->
            block.getText().contains("Professional Tika fallback content")));
        assertTrue(doc.getBlocks().stream().allMatch(block -> "Apache Tika".equals(block.getSourceLabel())));
    }

    @Test
    void everyAllowedExtensionHasAParser()
    {
        List<KbParser> parsers = List.of(
            new PdfParser(), new DocxParser(), new LegacyWordParser(), new RtfParser(),
            new SpreadsheetParser(), new PresentationParser(), new TextParser(),
            new HtmlParser(), new DelimitedTextParser(), new JsonParser(), new XmlParser());

        for (String extension : KbFileFormats.allowedExtensions())
        {
            assertTrue(parsers.stream().anyMatch(p -> p.supports(extension)),
                "上传白名单缺少解析器: " + extension + ", parsers=" + Arrays.toString(parsers.toArray()));
        }
    }

    private Path write(String name, String content) throws Exception
    {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static void addPdfPage(PDDocument pdf, String text) throws Exception
    {
        PDPage page = new PDPage();
        pdf.addPage(page);
        try (PDPageContentStream stream = new PDPageContentStream(pdf, page))
        {
            stream.beginText();
            stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
            stream.newLineAtOffset(72, 720);
            stream.showText(text);
            stream.endText();
        }
    }
}
