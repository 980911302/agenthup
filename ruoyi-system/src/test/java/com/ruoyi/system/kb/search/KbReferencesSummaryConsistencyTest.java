package com.ruoyi.system.kb.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.ruoyi.system.kb.vector.KbSearchHit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 摘要与正文的同源性:徽标数字必须等于展开后看到的文件卡片数。
 *
 * <p>这是整个 kb.references 归并方案要解决的根本问题 —— 此前徽标走后端 SQL 聚合、
 * 展开走前端 aggregateCitationFiles，两套算法各算各的，于是出现「结束时显示 5 个、
 * 刷新后 1 个」。现在两边都从同一份 payload 取:摘要 SQL 读
 * {@code json_extract(payload,'$.fileCount')}，前端渲染 {@code payload.files}。
 *
 * <p>本类锁住的就是这两者相等。它是两条数据路径之间唯一的绑定点，删掉之后
 * 任何一方改动都不会有测试失败 —— 正是上次 bug 能悄悄溜过去的原因。
 */
class KbReferencesSummaryConsistencyTest
{
    /** 摘要 SQL 用的 JSON 路径,字段名变了这里要一起改 */
    private static final String SUMMARY_FIELD = "fileCount";

    @Test
    void 单次检索摘要数等于正文文件数()
    {
        Map<String, Object> payload = KbReferencesUiPayload.from("请假",
                List.of(hit(1L, 2L, 3L, "手册", "年假"),
                        hit(2L, 2L, 4L, "制度", "病假")));

        assertSummaryMatchesFiles(payload);
        assertEquals(2, payload.get(SUMMARY_FIELD));
    }

    @Test
    void 跨两次检索归并后仍然相等()
    {
        // 第二次检索重复命中「手册」,归并后它只应算一个文件
        Map<String, Object> first = KbReferencesUiPayload.from("年假",
                List.of(hit(1L, 2L, 3L, "手册", "年假 5 天"),
                        hit(2L, 2L, 4L, "制度", "病假 3 天")));
        Map<String, Object> second = KbReferencesUiPayload.from("调休",
                List.of(hit(5L, 2L, 3L, "手册", "调休规则"),
                        hit(6L, 2L, 9L, "流程", "审批流")));

        Map<String, Object> merged = KbReferencesUiPayload.merge(first, second);

        assertSummaryMatchesFiles(merged);
        assertEquals(3, merged.get(SUMMARY_FIELD), "手册跨两次命中应合并为一个文件");
        assertEquals(4, merged.get("chunkCount"), "chunk 是去重后的真实总数");
    }

    @Test
    void 归并触发截断后摘要数依然等于正文文件数()
    {
        // 截断只砍 chunks、不砍 file 条目 —— 一旦有人改成连 file 一起砍,
        // 徽标就会比展开后看到的多,这个断言必须先红。
        // 裁剪只发生在 merge 之后,所以这里必须走归并路径。
        List<KbSearchHit> a = new ArrayList<>();
        List<KbSearchHit> b = new ArrayList<>();
        for (int i = 0; i < 20; i++)
        {
            a.add(hit(100L + i, 2L, (long) (200 + i), "doc-" + i, "x".repeat(600)));
            b.add(hit(500L + i, 2L, (long) (200 + i), "doc-" + i, "y".repeat(600)));
        }

        Map<String, Object> merged = KbReferencesUiPayload.merge(
                KbReferencesUiPayload.from("q1", a), KbReferencesUiPayload.from("q2", b));

        assertEquals(Boolean.TRUE, merged.get("truncated"), "样本需要触发截断才有意义");
        assertSummaryMatchesFiles(merged);
    }

    @Test
    void 截断不会让计数缩水()
    {
        // 全部集中在一个文件,逼出 per-file 的 chunk 截断
        List<KbSearchHit> a = new ArrayList<>();
        List<KbSearchHit> b = new ArrayList<>();
        for (int i = 0; i < 15; i++)
        {
            a.add(hit(300L + i, 2L, 7L, "巨型文档", "段落 " + i));
            b.add(hit(700L + i, 2L, 7L, "巨型文档", "补充 " + i));
        }

        Map<String, Object> merged = KbReferencesUiPayload.merge(
                KbReferencesUiPayload.from("q1", a), KbReferencesUiPayload.from("q2", b));

        assertEquals(1, merged.get(SUMMARY_FIELD));
        assertEquals(30, merged.get("chunkCount"),
                "chunkCount 是去重后的真实数,不能因为展示被裁而缩水");

        List<Map<String, Object>> files = filesOf(merged);
        Map<String, Object> only = files.get(0);
        assertEquals(30, only.get("chunkCount"), "文件级计数同样是真实数");
        assertTrue(chunksOf(only).size() < 30, "展示列表确实被裁过");
        assertEquals(Boolean.TRUE, merged.get("truncated"));
    }

    @Test
    void 单次检索取到topK上限时不超体积闸口()
    {
        // from 阶段刻意不裁剪(裁剪会让归并后的计数失真),体积由 topK ≤ 20 兜住。
        // 这条守的是那个余量:元数据变长到顶穿 16KB 时必须先红。
        List<KbSearchHit> hits = new ArrayList<>();
        for (int i = 0; i < 20; i++)
        {
            KbSearchHit h = hit(900L + i, 2L, (long) (900 + i),
                    "很长的中文文档名称占位符-" + i + ".md", "x".repeat(600));
            h.setHeadingPath("第一章 / 第二节 / 第三小节 / 具体条目标题-" + i);
            hits.add(h);
        }

        Map<String, Object> payload = KbReferencesUiPayload.from("topK 上限", hits);

        int chars = com.alibaba.fastjson2.JSON.toJSONString(payload).length();
        int max = com.ruoyi.system.tool.UiArtifactNames
                .spec(com.ruoyi.system.tool.UiArtifactNames.KB_REFERENCES).maxPayloadChars();
        assertTrue(chars <= max,
                "单次检索 payload 超限会被 UiArtifactEmitter 静默丢弃,引用整个消失。actual=" + chars);
    }

    @Test
    void 空命中时摘要为零且不为null()
    {
        Map<String, Object> payload = KbReferencesUiPayload.from("查无此项", List.of());

        assertNotNull(payload.get(SUMMARY_FIELD), "字段必须存在,摘要 SQL 靠 json_extract 取它");
        assertEquals(0, payload.get(SUMMARY_FIELD));
        assertSummaryMatchesFiles(payload);
    }

    /** 摘要口径(fileCount)与正文口径(files 数组长度)必须永远一致 */
    private static void assertSummaryMatchesFiles(Map<String, Object> payload)
    {
        Object summary = payload.get(SUMMARY_FIELD);
        assertNotNull(summary, "缺 " + SUMMARY_FIELD + ",摘要接口会取到 null");
        assertEquals(((Number) summary).intValue(), filesOf(payload).size(),
                "徽标数字与展开后的文件卡片数不一致 —— 两条数据路径又分叉了");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> filesOf(Map<String, Object> payload)
    {
        List<Map<String, Object>> files = (List<Map<String, Object>>) payload.get("files");
        return files == null ? List.of() : files;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> chunksOf(Map<String, Object> file)
    {
        List<Map<String, Object>> chunks = (List<Map<String, Object>>) file.get("chunks");
        return chunks == null ? List.of() : chunks;
    }

    private static KbSearchHit hit(Long chunkId, Long kbId, Long docId, String docName, String content)
    {
        KbSearchHit h = new KbSearchHit();
        h.setChunkId(chunkId);
        h.setKbId(kbId);
        h.setDocId(docId);
        h.setDocName(docName);
        h.setContent(content);
        return h;
    }
}
