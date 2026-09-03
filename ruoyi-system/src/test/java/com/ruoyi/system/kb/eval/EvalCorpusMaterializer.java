package com.ruoyi.system.kb.eval;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;

/**
 * 将 classpath 文本语料复制到工作目录，并确定性生成 office/pdf 二进制语料。
 */
public final class EvalCorpusMaterializer
{
    private EvalCorpusMaterializer()
    {
    }

    /**
     * @return docId -> 本地文件路径
     */
    public static Map<String, Path> materialize(Path outDir) throws Exception
    {
        Files.createDirectories(outDir);
        Map<String, Path> files = new LinkedHashMap<>();

        copyResource("kb/eval/corpus/hr-handbook.md", outDir.resolve("hr-handbook.md"));
        files.put("hr-handbook", outDir.resolve("hr-handbook.md"));

        copyResource("kb/eval/corpus/product-guide.html", outDir.resolve("product-guide.html"));
        files.put("product-guide", outDir.resolve("product-guide.html"));

        copyResource("kb/eval/corpus/employees.csv", outDir.resolve("employees.csv"));
        files.put("employees", outDir.resolve("employees.csv"));

        copyResource("kb/eval/corpus/service-config.json", outDir.resolve("service-config.json"));
        files.put("service-config", outDir.resolve("service-config.json"));

        Path docx = outDir.resolve("arch-overview.docx");
        writeDocx(docx);
        files.put("arch-overview", docx);

        Path xlsx = outDir.resolve("products.xlsx");
        writeXlsx(xlsx);
        files.put("products", xlsx);

        Path pptx = outDir.resolve("intro-deck.pptx");
        writePptx(pptx);
        files.put("intro-deck", pptx);

        Path pdf = outDir.resolve("ops-guide.pdf");
        writePdf(pdf);
        files.put("ops-guide", pdf);

        return files;
    }

    private static void copyResource(String classpath, Path target) throws Exception
    {
        try (var in = EvalCorpusMaterializer.class.getClassLoader().getResourceAsStream(classpath))
        {
            if (in == null)
            {
                throw new IllegalStateException("missing resource: " + classpath);
            }
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void writeDocx(Path file) throws Exception
    {
        try (XWPFDocument doc = new XWPFDocument(); OutputStream out = Files.newOutputStream(file))
        {
            XWPFParagraph h = doc.createParagraph();
            h.setStyle("Heading1");
            XWPFRun hr = h.createRun();
            hr.setText("架构总览");
            hr.setBold(true);

            para(doc, "星河科技知识库采用分层架构：解析层产出 IR，切片层产出 LEAF chunk，向量检索落在 PostgreSQL。");
            para(doc, "可选知识图谱存储在 Neo4j。向量检索是稳定基线；图谱失败必须降级，不能阻塞 Basic 检索。");
            para(doc, "图索引带 generation，旧任务不得覆盖新任务。重建与删除必须保持证据血统正确。");
            doc.write(out);
        }
    }

    private static void para(XWPFDocument doc, String text)
    {
        XWPFParagraph p = doc.createParagraph();
        p.createRun().setText(text);
    }

    private static void writeXlsx(Path file) throws Exception
    {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(file))
        {
            var sheet = workbook.createSheet("价目表");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("版本");
            header.createCell(1).setCellValue("价格");
            header.createCell(2).setCellValue("说明");
            var r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue("标准版");
            r1.createCell(1).setCellValue(99);
            r1.createCell(2).setCellValue("最多 10 个知识库");
            var r2 = sheet.createRow(2);
            r2.createCell(0).setCellValue("企业版");
            r2.createCell(1).setCellValue(999);
            r2.createCell(2).setCellValue("不限知识库并开放图谱抽取");
            workbook.write(out);
        }
    }

    private static void writePptx(Path file) throws Exception
    {
        try (XMLSlideShow show = new XMLSlideShow(); OutputStream out = Files.newOutputStream(file))
        {
            XSLFSlide s1 = show.createSlide();
            s1.createTextBox().setText("GraphRAG 路线图");
            s1.createTextBox().setText("下一阶段将支持 Local Search 与 Global Search。");

            XSLFSlide s2 = show.createSlide();
            s2.createTextBox().setText("查询模式");
            s2.createTextBox().setText("Basic 保留向量基线；DRIFT 与 Auto 需评测通过后开放。");
            show.write(out);
        }
    }

    private static void writePdf(Path file) throws Exception
    {
        try (PDDocument pdf = new PDDocument())
        {
            addPage(pdf, "Ops guide page 1: always keep vector baseline available when graph is down.");
            addPage(pdf, "Ops guide page 2: graph rebuild failure must not continue write; use active generation rollback.");
            pdf.save(file.toFile());
        }
    }

    private static void addPage(PDDocument pdf, String text) throws Exception
    {
        PDPage page = new PDPage();
        pdf.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(pdf, page))
        {
            cs.beginText();
            cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
            cs.newLineAtOffset(50, 700);
            cs.showText(text);
            cs.endText();
        }
    }

    public static String readResourceUtf8(String classpath) throws Exception
    {
        try (var in = EvalCorpusMaterializer.class.getClassLoader().getResourceAsStream(classpath))
        {
            if (in == null)
            {
                throw new IllegalStateException("missing resource: " + classpath);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
