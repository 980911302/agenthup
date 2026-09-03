package com.ruoyi.system.kb.search;

import java.util.List;
import java.util.Map;
import com.ruoyi.system.kb.vector.KbSearchHit;
import com.ruoyi.system.tool.UiArtifactNames;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KbReferencesUiPayloadTest
{
    @Test
    void singleSearchFileAndChunkCountsMatchHits()
    {
        KbSearchHit a = hit(11L, 2L, 3L, "手册", "年假 5 天");
        KbSearchHit b = hit(12L, 2L, 4L, "制度", "病假 3 天");

        Map<String, Object> payload = KbReferencesUiPayload.from("请假", List.of(a, b));

        assertEquals(2, payload.get("schemaVersion"));
        assertEquals(List.of("请假"), payload.get("queries"));
        assertEquals(2, payload.get("fileCount"));
        assertEquals(2, payload.get("chunkCount"));
        assertEquals(Boolean.FALSE, payload.get("truncated"));
        List<Map<String, Object>> files = filesOf(payload);
        assertEquals(2, files.size());
        assertEquals("手册", files.get(0).get("docName"));
        assertEquals(3L, files.get(0).get("docId"));
        assertEquals(2L, files.get(0).get("kbId"));
        assertEquals(1, files.get(0).get("chunkCount"));
        assertEquals(11L, chunksOf(files.get(0)).get(0).get("chunkId"));
        assertEquals("年假 5 天", chunksOf(files.get(0)).get(0).get("content"));
        assertFalse(chunksOf(files.get(0)).get(0).containsKey("headingPath"));
    }

    @Test
    void sameDocNameChunksAreGroupedIntoOneFile()
    {
        KbSearchHit a = hit(1L, 1L, 12L, "知识库模块.md", "片段甲");
        KbSearchHit b = hit(2L, 1L, 12L, "知识库模块.md", "片段乙");

        Map<String, Object> payload = KbReferencesUiPayload.from("设计", List.of(a, b));

        assertEquals(1, payload.get("fileCount"));
        assertEquals(2, payload.get("chunkCount"));
        List<Map<String, Object>> files = filesOf(payload);
        assertEquals(1, files.size());
        assertEquals(2, files.get(0).get("chunkCount"));
        assertEquals(2, chunksOf(files.get(0)).size());
    }

    @Test
    void emptyHitsStillHasQueryAndZeroCounts()
    {
        Map<String, Object> payload = KbReferencesUiPayload.from("q", List.of());
        assertEquals(List.of("q"), payload.get("queries"));
        assertEquals(0, payload.get("fileCount"));
        assertEquals(0, payload.get("chunkCount"));
        assertTrue(filesOf(payload).isEmpty());
    }

    @Test
    void specIsMergeAndSchema2()
    {
        UiArtifactNames.Spec spec = UiArtifactNames.spec(UiArtifactNames.KB_REFERENCES);
        assertEquals(2, spec.schemaVersion());
        assertEquals(UiArtifactNames.MergePolicy.MERGE, spec.mergePolicy());
    }

    @Test
    void mergeDedupesSameFileAcrossSearches()
    {
        Map<String, Object> first = KbReferencesUiPayload.from("知识库 设计", List.of(
                hit(101L, 1L, 12L, "知识库模块.md", "片段一"),
                hit(201L, 1L, 8L, "整体架构总览.md", "总览")));
        Map<String, Object> second = KbReferencesUiPayload.from("KbSearchService", List.of(
                hit(102L, 1L, 12L, "知识库模块.md", "片段二"),
                hit(101L, 1L, 12L, "知识库模块.md", "片段一重复")));

        Map<String, Object> merged = KbReferencesUiPayload.merge(first, second);

        assertEquals(2, merged.get("fileCount"));
        assertEquals(3, merged.get("chunkCount"));
        assertEquals(List.of("知识库 设计", "KbSearchService"), merged.get("queries"));
        List<Map<String, Object>> files = filesOf(merged);
        Map<String, Object> kb = files.stream()
                .filter(f -> "知识库模块.md".equals(f.get("docName")))
                .findFirst().orElseThrow();
        assertEquals(2, kb.get("chunkCount"));
        assertEquals(2, chunksOf(kb).size());
    }

    @Test
    void missingDocIdDoesNotSplitSameFile()
    {
        KbSearchHit withId = hit(1L, 1L, 12L, "知识库模块.md", "有 id");
        KbSearchHit noId = hit(2L, 1L, null, "知识库模块.md", "无 id");
        Map<String, Object> payload = KbReferencesUiPayload.from("q", List.of(withId, noId));
        assertEquals(1, payload.get("fileCount"));
        assertEquals(12L, filesOf(payload).get(0).get("docId"));
        assertEquals(2, payload.get("chunkCount"));
    }

    @Test
    void mergeCapsPayloadWithinMaxChars()
    {
        List<KbSearchHit> firstHits = new java.util.ArrayList<>();
        List<KbSearchHit> secondHits = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++)
        {
            KbSearchHit a = hit((long) i, 1L, (long) i, "文件-" + i + ".md",
                    "x".repeat(KbReferencesUiPayload.MAX_CONTENT_CHARS));
            firstHits.add(a);
            KbSearchHit b = hit((long) (100 + i), 1L, (long) i, "文件-" + i + ".md",
                    "y".repeat(KbReferencesUiPayload.MAX_CONTENT_CHARS));
            secondHits.add(b);
        }
        Map<String, Object> merged = KbReferencesUiPayload.merge(
                KbReferencesUiPayload.from("q1", firstHits),
                KbReferencesUiPayload.from("q2", secondHits));

        assertEquals(20, merged.get("fileCount"));
        assertEquals(40, merged.get("chunkCount"));
        assertEquals(Boolean.TRUE, merged.get("truncated"));
        int chars = com.alibaba.fastjson2.JSON.toJSONString(merged).length();
        assertTrue(chars <= UiArtifactNames.spec(UiArtifactNames.KB_REFERENCES).maxPayloadChars(),
                "归并后 payload 必须 ≤ maxPayloadChars, actual=" + chars);
        int shown = filesOf(merged).stream().mapToInt(f -> chunksOf(f).size()).sum();
        assertTrue(shown <= KbReferencesUiPayload.MAX_CHUNKS_TOTAL);
    }

    private static KbSearchHit hit(Long chunkId, Long kbId, Long docId, String docName, String content)
    {
        KbSearchHit hit = new KbSearchHit();
        hit.setChunkId(chunkId);
        hit.setKbId(kbId);
        hit.setDocId(docId);
        hit.setDocName(docName);
        hit.setContent(content);
        return hit;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> filesOf(Map<String, Object> payload)
    {
        return (List<Map<String, Object>>) payload.get("files");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> chunksOf(Map<String, Object> file)
    {
        return (List<Map<String, Object>>) file.get("chunks");
    }
}
