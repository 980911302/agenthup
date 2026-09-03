package com.ruoyi.system.kb;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.ruoyi.system.kb.parser.IrBlock;
import com.ruoyi.system.kb.parser.IrDoc;
import com.ruoyi.system.kb.parser.IrQualityReport;
import com.ruoyi.system.kb.parser.IrTable;

/**
 * 从 IR 生成安全预览与目录（纯函数，可单测）。
 */
public final class KbDocPreviewBuilder
{
    public static final int DEFAULT_BLOCK_LIMIT = 200;

    private KbDocPreviewBuilder() {}

    public static Map<String, Object> build(IrDoc ir, int blockLimit)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        if (ir == null)
        {
            out.put("available", false);
            out.put("reason", "NO_IR");
            return out;
        }
        int limit = blockLimit > 0 ? blockLimit : DEFAULT_BLOCK_LIMIT;
        List<IrBlock> blocks = ir.getBlocks() == null ? List.of() : ir.getBlocks();
        List<IrTable> tables = ir.getTables() == null ? List.of() : ir.getTables();

        Set<Integer> pages = new HashSet<>();
        Set<String> sheets = new HashSet<>();
        Set<String> slides = new HashSet<>();
        int headingCount = 0;
        int paragraphCount = 0;
        int codeCount = 0;
        int listCount = 0;

        List<Map<String, Object>> outline = new ArrayList<>();
        List<Map<String, Object>> previewBlocks = new ArrayList<>();
        int emitted = 0;

        for (IrBlock b : blocks)
        {
            if (b == null)
            {
                continue;
            }
            if (b.getPageNumber() != null)
            {
                pages.add(b.getPageNumber());
            }
            String label = b.getSourceLabel();
            if (label != null && !label.isBlank())
            {
                // 工作表 / 幻灯片启发式
                String lower = label.toLowerCase();
                if (lower.startsWith("slide") || lower.contains("幻灯") || lower.startsWith("page "))
                {
                    slides.add(label);
                }
                else
                {
                    sheets.add(label);
                }
            }
            String type = b.getBlockType() == null ? "paragraph" : b.getBlockType().toLowerCase();
            if ("heading".equals(type))
            {
                headingCount++;
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("position", b.getPosition());
                node.put("level", b.getLevel() == null ? 1 : b.getLevel());
                node.put("title", safeText(b.getText(), 200));
                node.put("pageNumber", b.getPageNumber());
                node.put("sourceLabel", b.getSourceLabel());
                outline.add(node);
            }
            else if ("code".equals(type))
            {
                codeCount++;
            }
            else if ("list_item".equals(type) || "list".equals(type))
            {
                listCount++;
            }
            else
            {
                paragraphCount++;
            }

            if (emitted < limit)
            {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("position", b.getPosition());
                row.put("blockType", type);
                row.put("level", b.getLevel());
                row.put("text", safeText(b.getText(), 4000));
                row.put("headingPath", b.getHeadingPath());
                row.put("pageNumber", b.getPageNumber());
                row.put("sourceLabel", b.getSourceLabel());
                row.put("html", toSafeBlockHtml(type, b.getText(), b.getLevel()));
                previewBlocks.add(row);
                emitted++;
            }
        }

        List<Map<String, Object>> tablePreviews = new ArrayList<>();
        int tableEmit = 0;
        for (IrTable t : tables)
        {
            if (t == null)
            {
                continue;
            }
            if (tableEmit < 30)
            {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("position", t.getPosition());
                row.put("caption", safeText(t.getCaption(), 200));
                row.put("html", sanitizeTableHtml(t.getHtml()));
                tablePreviews.add(row);
                tableEmit++;
            }
        }

        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("blockCount", blocks.size());
        counts.put("tableCount", tables.size());
        counts.put("headingCount", headingCount);
        counts.put("paragraphCount", paragraphCount);
        counts.put("codeCount", codeCount);
        counts.put("listCount", listCount);
        counts.put("pageCount", pages.isEmpty() ? null : pages.size());
        counts.put("sheetCount", sheets.isEmpty() ? null : sheets.size());
        counts.put("slideCount", slides.isEmpty() ? null : slides.size());
        counts.put("previewBlockCount", previewBlocks.size());
        counts.put("truncated", blocks.size() > limit);

        out.put("available", true);
        out.put("sourceName", ir.getSourceName());
        out.put("sourceType", ir.getSourceType());
        out.put("extractor", ir.getExtractor());
        out.put("fallbackUsed", ir.isFallbackUsed());
        out.put("counts", counts);
        out.put("outline", outline);
        out.put("blocks", previewBlocks);
        out.put("tables", tablePreviews);
        out.put("quality", qualityMap(ir.getQuality(), counts));
        return out;
    }

    public static Map<String, Object> qualityMap(IrQualityReport q, Map<String, Object> counts)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        if (q == null)
        {
            m.put("status", "UNKNOWN");
            m.put("grade", "UNKNOWN");
            m.put("warnings", List.of());
            m.put("accepted", true);
            if (counts != null)
            {
                m.putAll(counts);
            }
            return m;
        }
        m.put("status", q.getStatus());
        m.put("grade", gradeOf(q));
        m.put("accepted", q.isAccepted());
        m.put("blockCount", q.getBlockCount());
        m.put("textLength", q.getTextLength());
        m.put("scannedSuspected", q.isScannedSuspected());
        m.put("warnings", q.getWarnings() == null ? List.of() : q.getWarnings());
        m.put("suggestions", suggestionsOf(q));
        if (counts != null)
        {
            if (counts.get("pageCount") != null) m.put("pageCount", counts.get("pageCount"));
            if (counts.get("sheetCount") != null) m.put("sheetCount", counts.get("sheetCount"));
            if (counts.get("slideCount") != null) m.put("slideCount", counts.get("slideCount"));
            if (counts.get("tableCount") != null) m.put("tableCount", counts.get("tableCount"));
            if (counts.get("headingCount") != null) m.put("headingCount", counts.get("headingCount"));
        }
        return m;
    }

    public static String gradeOf(IrQualityReport q)
    {
        if (q == null)
        {
            return "UNKNOWN";
        }
        if (IrQualityReport.FAIL.equals(q.getStatus()))
        {
            return "POOR";
        }
        if (IrQualityReport.WARN.equals(q.getStatus()))
        {
            return "FAIR";
        }
        if (q.getTextLength() >= 500 && q.getBlockCount() >= 3)
        {
            return "GOOD";
        }
        if (q.getTextLength() >= 50)
        {
            return "OK";
        }
        return "THIN";
    }

    public static List<String> suggestionsOf(IrQualityReport q)
    {
        List<String> tips = new ArrayList<>();
        if (q == null)
        {
            return tips;
        }
        if (q.isScannedSuspected())
        {
            tips.add("提供可复制文字的 PDF/Word，或使用 OCR 后再上传");
        }
        if (q.getWarnings() != null)
        {
            for (String w : q.getWarnings())
            {
                if (w == null) continue;
                if (w.contains("重复"))
                {
                    tips.add("检查页眉页脚是否被重复抽取，必要时精简模板后重传");
                }
                else if (w.contains("替换字符") || w.contains("乱码"))
                {
                    tips.add("用 UTF-8 另存或导出为 DOCX/Markdown 后重试");
                }
                else if (w.contains("超大文本块"))
                {
                    tips.add("文档结构较少，建议补充标题层级以便更好切片");
                }
            }
        }
        if (IrQualityReport.FAIL.equals(q.getStatus()) && tips.isEmpty())
        {
            tips.add("重新处理；若仍失败请管理员查看处理诊断");
        }
        return tips;
    }

    public static String safeText(String text, int max)
    {
        if (text == null)
        {
            return "";
        }
        String t = text.replace("\u0000", "");
        if (t.length() > max)
        {
            return t.substring(0, max) + "…";
        }
        return t;
    }

    public static String escapeHtml(String s)
    {
        if (s == null)
        {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);
            switch (c)
            {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    public static String toSafeBlockHtml(String type, String text, Integer level)
    {
        String body = escapeHtml(text == null ? "" : text).replace("\n", "<br/>");
        String t = type == null ? "paragraph" : type.toLowerCase();
        if ("heading".equals(t))
        {
            int lv = level == null ? 2 : Math.min(6, Math.max(1, level));
            return "<h" + lv + " class=\"kb-prev-h\">" + body + "</h" + lv + ">";
        }
        if ("code".equals(t))
        {
            return "<pre class=\"kb-prev-code\">" + body + "</pre>";
        }
        if ("list_item".equals(t) || "list".equals(t))
        {
            return "<div class=\"kb-prev-li\">• " + body + "</div>";
        }
        if ("table".equals(t))
        {
            return "<div class=\"kb-prev-p\">" + body + "</div>";
        }
        return "<p class=\"kb-prev-p\">" + body + "</p>";
    }

    /**
     * 极简表格 HTML 消毒：去掉 script/style/事件属性，仅保留常见标签。
     */
    public static String sanitizeTableHtml(String html)
    {
        if (html == null || html.isBlank())
        {
            return "";
        }
        String s = html;
        s = s.replaceAll("(?is)<script[^>]*>.*?</script>", "");
        s = s.replaceAll("(?is)<style[^>]*>.*?</style>", "");
        s = s.replaceAll("(?i)\\son\\w+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)", "");
        s = s.replaceAll("(?i)javascript:", "");
        // 去掉危险标签但保留表格结构
        s = s.replaceAll("(?is)</?(?:iframe|object|embed|link|meta)[^>]*>", "");
        return s;
    }
}
