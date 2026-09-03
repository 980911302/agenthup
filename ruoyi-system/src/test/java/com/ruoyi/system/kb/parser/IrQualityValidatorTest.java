package com.ruoyi.system.kb.parser;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class IrQualityValidatorTest
{
    private final IrQualityValidator validator = new IrQualityValidator();

    @Test
    void rejectsEmptyExtraction()
    {
        IrQualityReport report = validator.inspect(new IrDoc());
        assertEquals(IrQualityReport.FAIL, report.getStatus());
        assertFalse(report.isAccepted());
    }

    @Test
    void rejectsLikelyScannedPdf()
    {
        IrDoc doc = doc("pdf", "封面");
        IrQualityReport report = validator.inspect(doc);
        assertEquals(IrQualityReport.FAIL, report.getStatus());
        assertTrue(report.isScannedSuspected());
        assertTrue(report.summary().contains("OCR"));
    }

    @Test
    void rejectsMojibake()
    {
        IrDoc doc = doc("txt", "正常文本\uFFFD\uFFFD\uFFFD");
        IrQualityReport report = validator.inspect(doc);
        assertEquals(IrQualityReport.FAIL, report.getStatus());
        assertTrue(report.getReplacementCharacterRatio() > 0.01);
    }

    @Test
    void warnsOnRepeatedBlocksButAllowsInspection()
    {
        IrDoc doc = new IrDoc();
        doc.setSourceType("docx");
        for (int i = 0; i < 6; i++)
        {
            add(doc, "这是每一页都会重复出现的公司内部页眉文字");
        }
        add(doc, "这一段是不同的正文内容并且长度足够纳入重复率检测");
        add(doc, "这是第二段不同的正文内容并且长度足够纳入重复率检测");

        IrQualityReport report = validator.inspect(doc);

        assertEquals(IrQualityReport.WARN, report.getStatus());
        assertTrue(report.isAccepted());
    }

    @Test
    void acceptsNormalDocument()
    {
        IrDoc doc = doc("md", "这是结构清晰、编码正常的知识库正文，用于后续切片和图谱抽取。");
        IrQualityReport report = validator.inspect(doc);
        assertEquals(IrQualityReport.PASS, report.getStatus());
        assertEquals(1, report.getBlockCount());
    }

    private static IrDoc doc(String type, String text)
    {
        IrDoc doc = new IrDoc();
        doc.setSourceType(type);
        add(doc, text);
        return doc;
    }

    private static void add(IrDoc doc, String text)
    {
        IrBlock block = new IrBlock();
        block.setText(text);
        block.setBlockType("paragraph");
        doc.getBlocks().add(block);
    }
}
