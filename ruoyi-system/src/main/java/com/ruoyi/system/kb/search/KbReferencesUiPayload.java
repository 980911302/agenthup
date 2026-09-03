package com.ruoyi.system.kb.search;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.system.kb.vector.KbSearchHit;
import com.ruoyi.ai.contract.kb.SearchHit;
import com.ruoyi.ai.kb.KnowledgeSearchFormats;
import com.ruoyi.system.tool.UiArtifactNames;

/**
 * {@code kb.references} v2 载荷:按文件归并后的结构,前端只渲染不算。
 *
 * <p>{@code fileCount}/{@code chunkCount} 永远是去重后的真实数;
 * 展示被裁剪时只把 {@code truncated} 置 true,不改计数。
 */
public final class KbReferencesUiPayload
{
    public static final int SCHEMA_VERSION = 2;
    public static final int MAX_CHUNKS_PER_FILE = 4;
    public static final int MAX_CHUNKS_TOTAL = 12;
    public static final int MAX_CONTENT_CHARS = 600;
    public static final int MAX_CONTENT_CHARS_COMPACT = 300;
    /** 兼容旧常量名 */
    public static final int MAX_CHUNKS = MAX_CHUNKS_TOTAL;

    private static final String UNKNOWN_DOC = "未知文档";

    private KbReferencesUiPayload() {}

    public static Map<String, Object> from(String query, List<KbSearchHit> hits)
    {
        Map<String, Object> payload = empty();
        appendQuery(payload, query);
        if (hits != null)
        {
            for (KbSearchHit hit : hits)
            {
                if (hit != null)
                {
                    addHit(payload, hit);
                }
            }
        }
        // 这里只重算计数、不做展示裁剪。裁剪必须留到 merge 之后:from 阶段先裁的话,
        // 被裁掉的 chunk 在归并时已经不存在,累积出来的 chunkCount 会低于真实命中数
        // (实测两次各 20 命中会从 40 变成 24),而那个数字是要显示给用户的。
        // 单次 payload 的体积由 topK ≤ 20 兜住(实测约 14KB,上限 16KB)。
        recount(payload);
        return payload;
    }

    /** 稳定检索契约到现有 kb.references v2 UI schema 的适配。 */
    public static Map<String, Object> fromContract(String query, List<SearchHit> hits)
    {
        return KnowledgeSearchFormats.referencesPayload(query, hits);
    }

    /**
     * 把一次新检索并进已有 v2 载荷。计数是去重后的真实数,展示裁剪只改 chunks 列表。
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> merge(Map<String, Object> existing, Map<String, Object> incoming)
    {
        Map<String, Object> out = existing == null ? empty() : copyOf(existing);
        if (incoming != null)
        {
            List<String> queries = (List<String>) incoming.get("queries");
            if (queries != null)
            {
                for (String q : queries)
                {
                    appendQuery(out, q);
                }
            }
            List<Map<String, Object>> inFiles = (List<Map<String, Object>>) incoming.get("files");
            if (inFiles != null)
            {
                for (Map<String, Object> inFile : inFiles)
                {
                    mergeFile(out, inFile);
                }
            }
        }
        applyVolumeLimits(out);
        return out;
    }

    static Map<String, Object> empty()
    {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", SCHEMA_VERSION);
        payload.put("queries", new ArrayList<String>());
        payload.put("fileCount", 0);
        payload.put("chunkCount", 0);
        payload.put("truncated", Boolean.FALSE);
        payload.put("files", new ArrayList<Map<String, Object>>());
        return payload;
    }

    @SuppressWarnings("unchecked")
    private static void addHit(Map<String, Object> payload, KbSearchHit hit)
    {
        List<Map<String, Object>> files = (List<Map<String, Object>>) payload.get("files");
        String docName = hit.getDocName() == null || hit.getDocName().isBlank()
                ? UNKNOWN_DOC : hit.getDocName();
        Map<String, Object> file = null;
        for (Map<String, Object> existing : files)
        {
            if (docName.equals(existing.get("docName")))
            {
                file = existing;
                break;
            }
        }
        if (file == null)
        {
            file = new LinkedHashMap<>();
            file.put("docName", docName);
            putIfNotNull(file, "kbId", hit.getKbId());
            putIfNotNull(file, "docId", hit.getDocId());
            file.put("chunkCount", 0);
            file.put("chunks", new ArrayList<Map<String, Object>>());
            files.add(file);
        }
        else
        {
            if (file.get("kbId") == null && hit.getKbId() != null)
            {
                file.put("kbId", hit.getKbId());
            }
            if (file.get("docId") == null && hit.getDocId() != null)
            {
                file.put("docId", hit.getDocId());
            }
        }
        List<Map<String, Object>> chunks = (List<Map<String, Object>>) file.get("chunks");
        if (alreadyHasChunk(chunks, hit))
        {
            return;
        }
        chunks.add(toChunk(hit));
    }

    private static boolean alreadyHasChunk(List<Map<String, Object>> chunks, KbSearchHit hit)
    {
        String key = chunkKey(hit.getChunkId(), hit.getContent());
        for (Map<String, Object> chunk : chunks)
        {
            if (key.equals(chunkKey(chunk.get("chunkId"), (String) chunk.get("content"))))
            {
                return true;
            }
        }
        return false;
    }

    static String chunkKey(Object chunkId, String content)
    {
        if (chunkId != null && !"".equals(chunkId.toString()))
        {
            return "id:" + chunkId;
        }
        String text = content == null ? "" : content;
        return "c:" + (text.length() <= 200 ? text : text.substring(0, 200));
    }

    private static Map<String, Object> toChunk(KbSearchHit hit)
    {
        Map<String, Object> chunk = new LinkedHashMap<>();
        putIfNotNull(chunk, "chunkId", hit.getChunkId());
        putIfNotNull(chunk, "headingPath", hit.getHeadingPath());
        putIfNotNull(chunk, "channel", hit.getChannel());
        String content = hit.getContent();
        if (content != null && content.length() > MAX_CONTENT_CHARS)
        {
            chunk.put("content", content.substring(0, MAX_CONTENT_CHARS));
            chunk.put("truncated", Boolean.TRUE);
        }
        else
        {
            putIfNotNull(chunk, "content", content);
        }
        return chunk;
    }

    @SuppressWarnings("unchecked")
    private static void mergeFile(Map<String, Object> payload, Map<String, Object> incomingFile)
    {
        if (incomingFile == null)
        {
            return;
        }
        List<Map<String, Object>> files = (List<Map<String, Object>>) payload.get("files");
        String docName = incomingFile.get("docName") == null || incomingFile.get("docName").toString().isBlank()
                ? UNKNOWN_DOC : incomingFile.get("docName").toString();
        Map<String, Object> file = findFile(files, docName);
        if (file == null)
        {
            file = new LinkedHashMap<>();
            file.put("docName", docName);
            putIfNotNull(file, "kbId", incomingFile.get("kbId"));
            putIfNotNull(file, "docId", incomingFile.get("docId"));
            putIfNotNull(file, "kbName", incomingFile.get("kbName"));
            file.put("chunkCount", 0);
            file.put("chunks", new ArrayList<Map<String, Object>>());
            files.add(file);
        }
        else
        {
            if (file.get("kbId") == null && incomingFile.get("kbId") != null)
            {
                file.put("kbId", incomingFile.get("kbId"));
            }
            if (file.get("docId") == null && incomingFile.get("docId") != null)
            {
                file.put("docId", incomingFile.get("docId"));
            }
            if (file.get("kbName") == null && incomingFile.get("kbName") != null)
            {
                file.put("kbName", incomingFile.get("kbName"));
            }
        }
        List<Map<String, Object>> chunks = (List<Map<String, Object>>) file.get("chunks");
        List<Map<String, Object>> inChunks = (List<Map<String, Object>>) incomingFile.get("chunks");
        if (inChunks == null)
        {
            return;
        }
        for (Map<String, Object> inChunk : inChunks)
        {
            if (inChunk == null)
            {
                continue;
            }
            String key = chunkKey(inChunk.get("chunkId"), (String) inChunk.get("content"));
            boolean exists = false;
            for (Map<String, Object> chunk : chunks)
            {
                if (key.equals(chunkKey(chunk.get("chunkId"), (String) chunk.get("content"))))
                {
                    exists = true;
                    break;
                }
            }
            if (!exists)
            {
                chunks.add(new LinkedHashMap<>(inChunk));
            }
        }
    }

    private static Map<String, Object> findFile(List<Map<String, Object>> files, String docName)
    {
        for (Map<String, Object> existing : files)
        {
            if (docName.equals(existing.get("docName")))
            {
                return existing;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    static void recount(Map<String, Object> payload)
    {
        List<Map<String, Object>> files = (List<Map<String, Object>>) payload.get("files");
        int chunks = 0;
        if (files != null)
        {
            for (Map<String, Object> file : files)
            {
                List<Map<String, Object>> list = (List<Map<String, Object>>) file.get("chunks");
                int n = list == null ? 0 : list.size();
                file.put("chunkCount", n);
                chunks += n;
            }
            payload.put("fileCount", files.size());
        }
        else
        {
            payload.put("fileCount", 0);
        }
        payload.put("chunkCount", chunks);
    }

    @SuppressWarnings("unchecked")
    static void applyVolumeLimits(Map<String, Object> payload)
    {
        List<Map<String, Object>> files = (List<Map<String, Object>>) payload.get("files");
        int trueFiles = files == null ? 0 : files.size();
        int trueChunks = 0;
        if (files != null)
        {
            for (Map<String, Object> file : files)
            {
                List<Map<String, Object>> list = (List<Map<String, Object>>) file.get("chunks");
                int n = list == null ? 0 : list.size();
                file.put("chunkCount", n);
                trueChunks += n;
            }
        }
        payload.put("fileCount", trueFiles);
        payload.put("chunkCount", trueChunks);

        boolean truncated = Boolean.TRUE.equals(payload.get("truncated"));
        if (files != null)
        {
            for (Map<String, Object> file : files)
            {
                List<Map<String, Object>> chunks = (List<Map<String, Object>>) file.get("chunks");
                if (chunks != null && chunks.size() > MAX_CHUNKS_PER_FILE)
                {
                    file.put("chunks", new ArrayList<>(chunks.subList(0, MAX_CHUNKS_PER_FILE)));
                    truncated = true;
                }
            }
            int shown = 0;
            for (Map<String, Object> file : files)
            {
                List<Map<String, Object>> chunks = (List<Map<String, Object>>) file.get("chunks");
                if (chunks == null)
                {
                    continue;
                }
                if (shown >= MAX_CHUNKS_TOTAL)
                {
                    if (!chunks.isEmpty())
                    {
                        truncated = true;
                    }
                    file.put("chunks", new ArrayList<>());
                    continue;
                }
                int room = MAX_CHUNKS_TOTAL - shown;
                if (chunks.size() > room)
                {
                    file.put("chunks", new ArrayList<>(chunks.subList(0, room)));
                    truncated = true;
                    shown += room;
                }
                else
                {
                    shown += chunks.size();
                }
            }
        }
        UiArtifactNames.Spec spec = UiArtifactNames.spec(UiArtifactNames.KB_REFERENCES);
        int maxChars = spec == null ? 16 * 1024 : spec.maxPayloadChars();
        if (payloadChars(payload) > (int) (maxChars * 0.8))
        {
            shrinkContent(payload, MAX_CONTENT_CHARS_COMPACT);
            truncated = true;
        }
        while (payloadChars(payload) > maxChars)
        {
            if (!dropLastDisplayedChunk(payload))
            {
                break;
            }
            truncated = true;
        }
        payload.put("truncated", truncated);
    }

    @SuppressWarnings("unchecked")
    private static void shrinkContent(Map<String, Object> payload, int maxContent)
    {
        List<Map<String, Object>> files = (List<Map<String, Object>>) payload.get("files");
        if (files == null)
        {
            return;
        }
        for (Map<String, Object> file : files)
        {
            List<Map<String, Object>> chunks = (List<Map<String, Object>>) file.get("chunks");
            if (chunks == null)
            {
                continue;
            }
            for (Map<String, Object> chunk : chunks)
            {
                Object content = chunk.get("content");
                if (content instanceof String text && text.length() > maxContent)
                {
                    chunk.put("content", text.substring(0, maxContent));
                    chunk.put("truncated", Boolean.TRUE);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean dropLastDisplayedChunk(Map<String, Object> payload)
    {
        List<Map<String, Object>> files = (List<Map<String, Object>>) payload.get("files");
        if (files == null)
        {
            return false;
        }
        for (int i = files.size() - 1; i >= 0; i--)
        {
            List<Map<String, Object>> chunks = (List<Map<String, Object>>) files.get(i).get("chunks");
            if (chunks != null && !chunks.isEmpty())
            {
                chunks.remove(chunks.size() - 1);
                return true;
            }
        }
        return false;
    }

    private static int payloadChars(Map<String, Object> payload)
    {
        try
        {
            return JSON.toJSONString(payload).length();
        }
        catch (Exception ex)
        {
            return Integer.MAX_VALUE;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> copyOf(Map<String, Object> source)
    {
        return (Map<String, Object>) JSON.parseObject(JSON.toJSONString(source));
    }

    @SuppressWarnings("unchecked")
    static void appendQuery(Map<String, Object> payload, String query)
    {
        List<String> queries = (List<String>) payload.get("queries");
        if (queries == null)
        {
            queries = new ArrayList<>();
            payload.put("queries", queries);
        }
        String q = query == null ? "" : query;
        if (!queries.contains(q))
        {
            queries.add(q);
        }
    }

    private static void putIfNotNull(Map<String, Object> target, String key, Object value)
    {
        if (value != null)
        {
            target.put(key, value);
        }
    }
}
