package com.ruoyi.system.kb.eval;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.system.kb.eval.EvalModels.ExpectedIr;
import com.ruoyi.system.kb.parser.DelimitedTextParser;
import com.ruoyi.system.kb.parser.DocxParser;
import com.ruoyi.system.kb.parser.HtmlParser;
import com.ruoyi.system.kb.parser.IrBlock;
import com.ruoyi.system.kb.parser.IrDoc;
import com.ruoyi.system.kb.parser.JsonParser;
import com.ruoyi.system.kb.parser.KbParser;
import com.ruoyi.system.kb.parser.PdfParser;
import com.ruoyi.system.kb.parser.PresentationParser;
import com.ruoyi.system.kb.parser.SpreadsheetParser;
import com.ruoyi.system.kb.parser.TextParser;

/**
 * 阶段 0：多格式语料解析结构验收（非脆弱全文快照）。
 */
class ParserCorpusTest
{
    @TempDir
    static Path tempDir;

    static Map<String, Path> corpus;

    @BeforeAll
    static void materialize() throws Exception
    {
        corpus = EvalCorpusMaterializer.materialize(tempDir.resolve("corpus"));
    }

    @Test
    void allExpectedIrSpecsPass() throws Exception
    {
        Path expectedDir;
        var url = ParserCorpusTest.class.getClassLoader().getResource("kb/eval/expected-ir");
        if (url == null)
        {
            fail("expected-ir resource missing");
            return;
        }
        expectedDir = Path.of(url.toURI());
        List<String> failures = new ArrayList<>();
        try (var stream = Files.list(expectedDir))
        {
            List<Path> specs = stream.filter(p -> p.toString().endsWith(".json")).sorted().toList();
            assertFalse(specs.isEmpty(), "no expected-ir specs");
            for (Path specPath : specs)
            {
                ExpectedIr spec = JSON.parseObject(Files.readString(specPath), ExpectedIr.class);
                Path file = corpus.get(spec.docId);
                if (file == null || !Files.isRegularFile(file))
                {
                    failures.add(spec.docId + ": corpus file missing");
                    continue;
                }
                try
                {
                    IrDoc doc = parserFor(spec.fileName).parse(file.toFile(), spec.fileName);
                    validate(spec, doc, failures);
                }
                catch (Exception ex)
                {
                    failures.add(spec.docId + ": parse error " + ex.getMessage());
                }
            }
        }
        if (!failures.isEmpty())
        {
            fail(String.join("\n", failures));
        }
    }

    @Test
    void coversRequiredFormats()
    {
        assertTrue(corpus.containsKey("hr-handbook"));
        assertTrue(corpus.containsKey("product-guide"));
        assertTrue(corpus.containsKey("employees"));
        assertTrue(corpus.containsKey("service-config"));
        assertTrue(corpus.containsKey("arch-overview"));
        assertTrue(corpus.containsKey("products"));
        assertTrue(corpus.containsKey("intro-deck"));
        assertTrue(corpus.containsKey("ops-guide"));
    }

    private static void validate(ExpectedIr spec, IrDoc doc, List<String> failures)
    {
        List<IrBlock> blocks = doc.getBlocks() == null ? List.of() : doc.getBlocks();
        if (blocks.size() < spec.minBlocks)
        {
            failures.add(spec.docId + ": blocks " + blocks.size() + " < min " + spec.minBlocks);
        }
        String allText = blocks.stream().map(IrBlock::getText).filter(t -> t != null)
            .collect(Collectors.joining("\n"));
        for (String h : nullToEmpty(spec.requiredHeadings))
        {
            boolean ok = blocks.stream().anyMatch(b ->
                "heading".equals(b.getBlockType()) && h.equals(b.getText())
                    || (b.getText() != null && b.getText().contains(h)));
            if (!ok)
            {
                failures.add(spec.docId + ": missing heading/text " + h);
            }
        }
        for (String s : nullToEmpty(spec.requiredSubstrings))
        {
            if (!allText.contains(s))
            {
                failures.add(spec.docId + ": missing substring " + s);
            }
        }
        for (String s : nullToEmpty(spec.forbiddenSubstrings))
        {
            if (allText.contains(s))
            {
                failures.add(spec.docId + ": forbidden substring present " + s);
            }
        }
        for (String t : nullToEmpty(spec.requiredBlockTypes))
        {
            boolean ok = blocks.stream().anyMatch(b -> t.equals(b.getBlockType()));
            if (!ok)
            {
                failures.add(spec.docId + ": missing blockType " + t);
            }
        }
        for (String label : nullToEmpty(spec.requiredSourceLabels))
        {
            boolean ok = blocks.stream().anyMatch(b ->
                b.getSourceLabel() != null && b.getSourceLabel().startsWith(label));
            if (!ok)
            {
                // 允许精确或前缀
                ok = blocks.stream().anyMatch(b -> label.equals(b.getSourceLabel()));
            }
            if (!ok)
            {
                failures.add(spec.docId + ": missing sourceLabel " + label);
            }
        }
        if (spec.minPageNumber != null || spec.maxPageNumber != null)
        {
            List<Integer> pages = blocks.stream().map(IrBlock::getPageNumber)
                .filter(p -> p != null).toList();
            if (pages.isEmpty())
            {
                failures.add(spec.docId + ": expected page numbers but none present");
            }
            else
            {
                int min = pages.stream().mapToInt(Integer::intValue).min().orElse(0);
                int max = pages.stream().mapToInt(Integer::intValue).max().orElse(0);
                if (spec.minPageNumber != null && min > spec.minPageNumber)
                {
                    // allow min page == expected min
                }
                if (spec.minPageNumber != null && min < spec.minPageNumber)
                {
                    failures.add(spec.docId + ": min page " + min + " < " + spec.minPageNumber);
                }
                if (spec.maxPageNumber != null && max > spec.maxPageNumber)
                {
                    failures.add(spec.docId + ": max page " + max + " > " + spec.maxPageNumber);
                }
            }
        }
    }

    private static List<String> nullToEmpty(List<String> list)
    {
        return list == null ? List.of() : list;
    }

    private static KbParser parserFor(String fileName)
    {
        String ext = "";
        int dot = fileName.lastIndexOf('.');
        if (dot >= 0)
        {
            ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        }
        return switch (ext)
        {
            case "md", "markdown", "txt" -> new TextParser();
            case "html", "htm" -> new HtmlParser();
            case "csv", "tsv" -> new DelimitedTextParser();
            case "json" -> new JsonParser();
            case "docx" -> new DocxParser();
            case "xlsx", "xls" -> new SpreadsheetParser();
            case "pptx", "ppt" -> new PresentationParser();
            case "pdf" -> new PdfParser();
            default -> throw new IllegalArgumentException(ext);
        };
    }
}
