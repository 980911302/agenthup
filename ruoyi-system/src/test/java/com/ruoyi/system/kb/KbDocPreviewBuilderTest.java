package com.ruoyi.system.kb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.ruoyi.system.kb.parser.IrBlock;
import com.ruoyi.system.kb.parser.IrDoc;
import com.ruoyi.system.kb.parser.IrQualityReport;
import com.ruoyi.system.kb.parser.IrTable;

class KbDocPreviewBuilderTest
{
    @Test
    void buildsOutlineAndEscapesHtml()
    {
        IrDoc ir = new IrDoc();
        ir.setSourceType("pdf");
        IrBlock h = new IrBlock();
        h.setBlockType("heading");
        h.setLevel(1);
        h.setText("Title <script>");
        h.setPosition(0);
        h.setPageNumber(1);
        IrBlock p = new IrBlock();
        p.setBlockType("paragraph");
        p.setText("Hello & world");
        p.setPosition(1);
        p.setPageNumber(1);
        ir.setBlocks(List.of(h, p));
        IrTable t = new IrTable();
        t.setHtml("<table onclick=\"alert(1)\"><tr><td>a</td></tr></table><script>x</script>");
        ir.setTables(List.of(t));

        Map<String, Object> m = KbDocPreviewBuilder.build(ir, 50);
        assertTrue((Boolean) m.get("available"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> outline = (List<Map<String, Object>>) m.get("outline");
        assertEquals(1, outline.size());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) m.get("blocks");
        assertTrue(String.valueOf(blocks.get(0).get("html")).contains("&lt;script&gt;"));
        assertFalse(String.valueOf(blocks.get(0).get("html")).contains("<script>"));
        @SuppressWarnings("unchecked")
        Map<String, Object> counts = (Map<String, Object>) m.get("counts");
        assertEquals(1, counts.get("pageCount"));
    }

    @Test
    void qualityGrade()
    {
        IrQualityReport fail = new IrQualityReport();
        fail.setStatus(IrQualityReport.FAIL);
        assertEquals("POOR", KbDocPreviewBuilder.gradeOf(fail));

        IrQualityReport pass = new IrQualityReport();
        pass.setStatus(IrQualityReport.PASS);
        pass.setTextLength(800);
        pass.setBlockCount(10);
        assertEquals("GOOD", KbDocPreviewBuilder.gradeOf(pass));
    }

    @Test
    void sanitizeTableRemovesScript()
    {
        String html = KbDocPreviewBuilder.sanitizeTableHtml(
            "<table><tr><td>ok</td></tr></table><script>alert(1)</script>");
        assertFalse(html.toLowerCase().contains("script"));
        assertTrue(html.contains("<table>"));
    }
}
