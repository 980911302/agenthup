package com.ruoyi.ai.kb;

import com.ruoyi.ai.contract.kb.SearchHit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 稳定检索结果的模型文本与 UI 引用载荷格式；不依赖数据库或 RuoYi 实体。 */
public final class KnowledgeSearchFormats
{
    public static final int REFERENCES_SCHEMA_VERSION = 2;
    private static final int MAX_CONTENT_CHARS = 600;
    private static final String UNKNOWN_DOC = "未知文档";

    private KnowledgeSearchFormats() { }

    public static String forModel(List<SearchHit> hits)
    {
        if (hits == null || hits.isEmpty()) return "未检索到相关知识片段。";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hits.size(); i++)
        {
            SearchHit hit = hits.get(i);
            String docName = String.valueOf(hit.metadata().getOrDefault("docName", UNKNOWN_DOC));
            sb.append('[').append(i + 1).append("] 《").append(docName).append('》');
            appendMetadata(sb, hit, "headingPath", " ", "");
            appendMetadata(sb, hit, "channel", " (", ")");
            sb.append('\n').append("    ")
                    .append(hit.content() == null ? "" : hit.content().replace("\n", "\n    "))
                    .append("\n\n");
        }
        return sb.toString().trim();
    }

    public static Map<String, Object> referencesPayload(String query, List<SearchHit> hits)
    {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", REFERENCES_SCHEMA_VERSION);
        payload.put("queries", query == null || query.isBlank()
                ? new ArrayList<String>() : new ArrayList<>(List.of(query)));
        payload.put("fileCount", 0);
        payload.put("chunkCount", 0);
        payload.put("truncated", Boolean.FALSE);
        List<Map<String, Object>> files = new ArrayList<>();
        payload.put("files", files);
        if (hits != null)
        {
            for (SearchHit hit : hits)
            {
                if (hit != null) addHit(files, hit);
            }
        }
        int chunks = 0;
        for (Map<String, Object> file : files)
        {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> fileChunks = (List<Map<String, Object>>) file.get("chunks");
            file.put("chunkCount", fileChunks.size());
            chunks += fileChunks.size();
        }
        payload.put("fileCount", files.size());
        payload.put("chunkCount", chunks);
        return payload;
    }

    @SuppressWarnings("unchecked")
    private static void addHit(List<Map<String, Object>> files, SearchHit hit)
    {
        String docName = String.valueOf(hit.metadata().getOrDefault("docName", UNKNOWN_DOC));
        Map<String, Object> file = files.stream()
                .filter(value -> docName.equals(value.get("docName")))
                .findFirst().orElse(null);
        if (file == null)
        {
            file = new LinkedHashMap<>();
            file.put("docName", docName);
            put(file, "kbId", hit.knowledgeBaseId());
            put(file, "docId", hit.documentId());
            file.put("chunkCount", 0);
            file.put("chunks", new ArrayList<Map<String, Object>>());
            files.add(file);
        }
        List<Map<String, Object>> chunks = (List<Map<String, Object>>) file.get("chunks");
        String key = chunkKey(hit.chunkId(), hit.content());
        if (chunks.stream().anyMatch(chunk -> key.equals(
                chunkKey(chunk.get("chunkId"), (String) chunk.get("content"))))) return;
        Map<String, Object> chunk = new LinkedHashMap<>();
        put(chunk, "chunkId", hit.chunkId());
        put(chunk, "headingPath", hit.metadata().get("headingPath"));
        put(chunk, "channel", hit.metadata().get("channel"));
        String content = hit.content();
        if (content != null && content.length() > MAX_CONTENT_CHARS)
        {
            chunk.put("content", content.substring(0, MAX_CONTENT_CHARS));
            chunk.put("truncated", Boolean.TRUE);
        }
        else put(chunk, "content", content);
        chunks.add(chunk);
    }

    private static String chunkKey(Object chunkId, String content)
    {
        if (chunkId != null && !chunkId.toString().isBlank()) return "id:" + chunkId;
        String text = content == null ? "" : content;
        return "c:" + (text.length() <= 200 ? text : text.substring(0, 200));
    }

    private static void appendMetadata(StringBuilder sb, SearchHit hit, String key,
                                       String prefix, String suffix)
    {
        Object value = hit.metadata().get(key);
        if (value != null && !value.toString().isBlank()) sb.append(prefix).append(value).append(suffix);
    }

    private static void put(Map<String, Object> target, String key, Object value)
    {
        if (value != null) target.put(key, value);
    }
}
