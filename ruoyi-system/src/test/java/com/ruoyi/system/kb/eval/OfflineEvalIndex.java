package com.ruoyi.system.kb.eval;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.ruoyi.system.kb.chunker.ChunkDraft;
import com.ruoyi.system.kb.chunker.ChunkParams;
import com.ruoyi.system.kb.chunker.ParagraphChunker;
import com.ruoyi.system.kb.eval.EvalModels.IndexedChunk;
import com.ruoyi.system.kb.eval.EvalModels.RankedHit;
import com.ruoyi.system.kb.eval.EvalModels.RelevantAnchor;
import com.ruoyi.system.kb.parser.DelimitedTextParser;
import com.ruoyi.system.kb.parser.DocxParser;
import com.ruoyi.system.kb.parser.HtmlParser;
import com.ruoyi.system.kb.parser.IrDoc;
import com.ruoyi.system.kb.parser.JsonParser;
import com.ruoyi.system.kb.parser.KbParser;
import com.ruoyi.system.kb.parser.PdfParser;
import com.ruoyi.system.kb.parser.PresentationParser;
import com.ruoyi.system.kb.parser.SpreadsheetParser;
import com.ruoyi.system.kb.parser.TextParser;

/**
 * 离线评测索引：解析 + P 切片 + 词法检索 / 实体扩展 / RRF 融合。
 * 不调用嵌入或 LLM，保证可重复。
 */
public class OfflineEvalIndex
{
    private static final Pattern TOKEN = Pattern.compile("[\\p{IsHan}]|[A-Za-z0-9_]+");

    private final List<IndexedChunk> chunks = new ArrayList<>();
    private final Map<String, Set<String>> entityToChunkKeys = new HashMap<>();
    private final ParagraphChunker chunker = new ParagraphChunker();

    public void build(Map<String, Path> corpusFiles) throws Exception
    {
        chunks.clear();
        entityToChunkKeys.clear();
        for (Map.Entry<String, Path> e : corpusFiles.entrySet())
        {
            String docId = e.getKey();
            Path file = e.getValue();
            String fileName = file.getFileName().toString();
            KbParser parser = parserFor(fileName);
            IrDoc ir = parser.parse(file.toFile(), fileName);
            List<ChunkDraft> drafts = chunker.chunk(ir, new ChunkParams("P", 400, 40));
            for (ChunkDraft d : drafts)
            {
                IndexedChunk c = new IndexedChunk(
                    docId,
                    fileName,
                    d.getChunkIndex(),
                    d.getContent(),
                    d.getHeadingPath(),
                    d.getBlockType(),
                    d.getSourceLabel(),
                    d.getSourcePageFrom(),
                    d.getSourcePageTo());
                chunks.add(c);
                indexEntities(c);
            }
        }
    }

    public List<IndexedChunk> allChunks()
    {
        return List.copyOf(chunks);
    }

    public List<RankedHit> search(String mode, String query, int topK)
    {
        return switch (mode == null ? "vector" : mode.toLowerCase(Locale.ROOT))
        {
            case "graph" -> searchGraph(query, topK);
            case "mix" -> searchMix(query, topK);
            default -> searchVector(query, topK);
        };
    }

    public List<RankedHit> searchVector(String query, int topK)
    {
        Map<String, Double> qv = tf(query);
        List<RankedHit> hits = new ArrayList<>();
        for (IndexedChunk c : chunks)
        {
            double score = cosine(qv, tf(c.content));
            if (score > 0)
            {
                hits.add(new RankedHit(c, score, "vector"));
            }
        }
        return top(hits, topK);
    }

    public List<RankedHit> searchGraph(String query, int topK)
    {
        Set<String> keys = new HashSet<>();
        for (Map.Entry<String, Set<String>> e : entityToChunkKeys.entrySet())
        {
            if (query != null && query.contains(e.getKey()))
            {
                keys.addAll(e.getValue());
            }
        }
        // 回退：实体未命中时用词法，避免 graph 空结果拖垮 mix 基线
        if (keys.isEmpty())
        {
            List<RankedHit> fallback = searchVector(query, topK);
            fallback.forEach(h -> h.channel = "graph-fallback-vector");
            return fallback;
        }
        Map<String, Double> qv = tf(query);
        List<RankedHit> hits = new ArrayList<>();
        for (IndexedChunk c : chunks)
        {
            if (!keys.contains(c.chunkKey))
            {
                continue;
            }
            double score = 1.0 + cosine(qv, tf(c.content));
            hits.add(new RankedHit(c, score, "graph"));
        }
        return top(hits, topK);
    }

    public List<RankedHit> searchMix(String query, int topK)
    {
        List<RankedHit> v = searchVector(query, Math.max(topK, 20));
        List<RankedHit> g = searchGraph(query, Math.max(topK, 20));
        Map<String, Double> rrf = new LinkedHashMap<>();
        Map<String, IndexedChunk> byKey = new HashMap<>();
        accumulateRrf(rrf, byKey, v, 60);
        accumulateRrf(rrf, byKey, g, 60);
        List<RankedHit> hits = new ArrayList<>();
        for (Map.Entry<String, Double> e : rrf.entrySet())
        {
            hits.add(new RankedHit(byKey.get(e.getKey()), e.getValue(), "mix"));
        }
        return top(hits, topK);
    }

    public static boolean matchesAnchor(IndexedChunk chunk, RelevantAnchor anchor)
    {
        if (chunk == null || anchor == null || anchor.docId == null)
        {
            return false;
        }
        if (!anchor.docId.equals(chunk.docId))
        {
            return false;
        }
        if (anchor.mustContain == null || anchor.mustContain.isEmpty())
        {
            return true;
        }
        String content = chunk.content == null ? "" : chunk.content;
        for (String must : anchor.mustContain)
        {
            if (must == null || must.isBlank())
            {
                continue;
            }
            if (!content.contains(must))
            {
                return false;
            }
        }
        return true;
    }

    public static boolean isRelevant(IndexedChunk chunk, List<RelevantAnchor> anchors)
    {
        if (anchors == null || anchors.isEmpty() || chunk == null)
        {
            return false;
        }
        for (RelevantAnchor a : anchors)
        {
            if (matchesAnchor(chunk, a))
            {
                return true;
            }
        }
        return false;
    }

    private void accumulateRrf(Map<String, Double> rrf, Map<String, IndexedChunk> byKey,
        List<RankedHit> ranked, int k)
    {
        for (int i = 0; i < ranked.size(); i++)
        {
            RankedHit h = ranked.get(i);
            String key = h.chunk.chunkKey;
            byKey.put(key, h.chunk);
            double add = 1.0 / (k + i + 1);
            rrf.merge(key, add, Double::sum);
        }
    }

    private List<RankedHit> top(List<RankedHit> hits, int topK)
    {
        hits.sort(Comparator.comparingDouble((RankedHit h) -> h.score).reversed()
            .thenComparing(h -> h.chunk.chunkKey));
        if (hits.size() > topK)
        {
            return new ArrayList<>(hits.subList(0, topK));
        }
        return hits;
    }

    private void indexEntities(IndexedChunk c)
    {
        // 语料内关键实体/术语 → chunk 映射，模拟图扩展种子
        String[] entities = {
            "星河科技", "年假", "病假", "报销", "试用期", "Agent Hub", "pgvector", "Neo4j",
            "PostgreSQL", "张伟", "李娜", "王强", "赵敏", "标准版", "企业版", "agent-service",
            "GraphRAG", "Local Search", "Global Search", "vector baseline", "graph rebuild", "rollback",
            "知识库", "图谱", "嵌入"
        };
        for (String ent : entities)
        {
            if (c.content.contains(ent))
            {
                entityToChunkKeys.computeIfAbsent(ent, k -> new HashSet<>()).add(c.chunkKey);
            }
        }
    }

    private static Map<String, Double> tf(String text)
    {
        Map<String, Double> m = new HashMap<>();
        if (text == null || text.isBlank())
        {
            return m;
        }
        Matcher matcher = TOKEN.matcher(text.toLowerCase(Locale.ROOT));
        double total = 0;
        while (matcher.find())
        {
            String t = matcher.group();
            if (t.isBlank())
            {
                continue;
            }
            m.merge(t, 1.0, Double::sum);
            total += 1;
        }
        if (total > 0)
        {
            for (Map.Entry<String, Double> e : m.entrySet())
            {
                e.setValue(e.getValue() / total);
            }
        }
        return m;
    }

    private static double cosine(Map<String, Double> a, Map<String, Double> b)
    {
        if (a.isEmpty() || b.isEmpty())
        {
            return 0;
        }
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (double v : a.values())
        {
            na += v * v;
        }
        for (double v : b.values())
        {
            nb += v * v;
        }
        if (na == 0 || nb == 0)
        {
            return 0;
        }
        for (Map.Entry<String, Double> e : a.entrySet())
        {
            Double bv = b.get(e.getKey());
            if (bv != null)
            {
                dot += e.getValue() * bv;
            }
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
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
            default -> throw new IllegalArgumentException("unsupported eval format: " + ext);
        };
    }
}
