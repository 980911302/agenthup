package com.ruoyi.system.ai.memory.longterm;

import com.ruoyi.system.domain.AiChatMessage;
import com.ruoyi.system.domain.AiMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 提炼器测试(spec §13 的 MemoryExtractorTest 部分)。
 *
 * <p>覆盖:facts JSON 解析与类型/scope 判定、hash 精确去重、相似度去重、supersede 判定、
 * 单次产出上限、scope 判不出默认落 agent 层、提炼失败安静跳过(位点不推进语义由调用方保证)。
 *
 * <p>用 {@code MemoryTestSupport}(H2 + InMemoryMemoryVectorStore)装配真实
 * {@code MemoryServiceImpl},StubChatModel 按顺序返回预置 JSON。
 */
class MemoryExtractorTest extends MemoryTestSupport
{
    /** MemoryTestSupport 的 H2 库按类级存活(DB_CLOSE_DELAY=-1),各测试方法间会累积;
     *  每个用例前重建 ai_memory,保证断言互不干扰。 */
    @BeforeEach
    void resetMemoryTable() throws Exception
    {
        mapper.dropTableForTest();
        mapper.createTableForTest();
        vectorStore = new InMemoryMemoryVectorStore();
        MemoryTestSupport.setField(service, "vectorStore", vectorStore);
    }

    /** 顺序返回预置响应的假 ChatModel。 */
    private static final class StubChatModel implements ChatModel
    {
        private final List<String> replies = new ArrayList<>();

        StubChatModel(String... replies)
        {
            for (String r : replies)
            {
                this.replies.add(r);
            }
        }

        @Override
        public ChatResponse call(Prompt prompt)
        {
            String reply = replies.isEmpty() ? "[]" : replies.remove(0);
            return new ChatResponse(List.of(new Generation(new AssistantMessage(reply))));
        }
    }

    private MemoryExtractor newExtractor(ChatModel model)
    {
        MemoryExtractor e = new MemoryExtractor();
        try
        {
            MemoryTestSupport.setField(e, "memoryService", service);
            MemoryTestSupport.setField(e, "memoryMapper", mapper);
            MemoryTestSupport.setField(e, "vectorStore", vectorStore);
            MemoryTestSupport.setField(e, "maxFactsPerRun", 10);
            MemoryTestSupport.setField(e, "dedupThreshold", 0.92);
            MemoryTestSupport.setField(e, "timeoutSeconds", 0L);
            e.setChatModel(model);
        }
        catch (Exception ex)
        {
            throw new IllegalStateException(ex);
        }
        return e;
    }

    /** 构造一条消息。 */
    private static AiChatMessage msg(long id, String type, String content)
    {
        AiChatMessage m = new AiChatMessage();
        m.setMessageId(id);
        m.setMessageType(type);
        m.setContent(content);
        m.setVisibleToLlm("0");
        m.setCreateTime(new Date());
        return m;
    }

    @Test
    void extract_parsesFacts_andWritesToMemory()
    {
        MemoryExtractor e = newExtractor(new StubChatModel(
                "[{\"content\":\"用户在北京工作\",\"type\":\"fact\",\"scope\":\"user\"},"
                        + "{\"content\":\"回复要简洁\",\"type\":\"preference\",\"scope\":\"agent\"}]"));

        MemoryExtractor.ExtractResult r = e.extract(1L, 5L, "s1", List.of(msg(1, "USER", "我在北京工作")), 1L);
        assertTrue(r.attempted());
        assertEquals(2, r.persisted());
        List<AiMemory> all = mapper.selectByUser(1L);
        assertEquals(2, all.size());
        assertTrue(all.stream().anyMatch(m -> "active".equals(m.getStatus())
                && "用户在北京工作".equals(m.getContent()) && m.getAgentId() == 0L));
        assertTrue(all.stream().anyMatch(m -> "active".equals(m.getStatus())
                && "回复要简洁".equals(m.getContent()) && m.getAgentId() == 5L));
    }

    @Test
    void extract_hashExactDuplicate_isDropped()
    {
        MemoryExtractor e = newExtractor(new StubChatModel(
                "[{\"content\":\"用户在北京工作\",\"type\":\"fact\",\"scope\":\"user\"}]",
                "[{\"content\":\"用户在北京工作!\",\"type\":\"fact\",\"scope\":\"user\"}]"));

        assertEquals(1, e.extract(1L, 5L, "s1", List.of(msg(1, "USER", "我在北京工作")), 1L).persisted());
        // 第二次提炼同一事实(标点/空白不同 → 归一化后 hash 相同)→ 丢弃
        assertEquals(0, e.extract(1L, 5L, "s1", List.of(msg(2, "USER", "我在北京工作!")), 2L).persisted());

        assertEquals(1, mapper.selectByUser(1L).size());
    }

    @Test
    void extract_vectorNearDuplicate_isDropped()
    {
        // embedding 函数:含"不"的文本给不同向量(相关但不超阈值),其余给同一向量(相似度 1.0)
        MemoryExtractor e = newExtractor(new StubChatModel(
                "[{\"content\":\"用户喜欢喝咖啡\",\"type\":\"preference\",\"scope\":\"user\"}]",
                "[{\"content\":\"用户偏爱咖啡\",\"type\":\"preference\",\"scope\":\"user\"}]"));
        e.setEmbedFn(new ContentEmbed());

        assertEquals(1, e.extract(1L, 5L, "s1", List.of(msg(1, "USER", "我喜欢喝咖啡")), 1L).persisted());
        // 第二条候选与已有向量相似度 1.0 > 阈值 → 丢弃
        assertEquals(0, e.extract(1L, 5L, "s1", List.of(msg(2, "USER", "我偏爱咖啡")), 2L).persisted());

        assertEquals(1, mapper.selectByUser(1L).size());
    }

    @Test
    void extract_supersede_whenLlmSaysUpdate()
    {
        // 第一段:写入"用户喜欢苹果"(preference, user 层)
        MemoryExtractor e = newExtractor(new StubChatModel(
                "[{\"content\":\"用户喜欢苹果\",\"type\":\"preference\",\"scope\":\"user\"}]"));
        e.setEmbedFn(new ContentEmbed());
        assertEquals(1, e.extract(1L, 5L, "s1", List.of(msg(1, "USER", "我喜欢苹果")), 1L).persisted());

        // 第二段:候选"用户现在不喜欢苹果"含"不",与已有向量相关但相似度 < 0.92,
        // 不触发去重丢弃;supersede 判定返回覆盖旧 memoryId
        MemoryExtractor e2 = newExtractor(new StubChatModel(
                "[{\"content\":\"用户现在不喜欢苹果\",\"type\":\"preference\",\"scope\":\"user\"}]",
                "{\"supersede\": " + memoryIdOf(1L, 0L) + "}"));
        e2.setEmbedFn(new ContentEmbed());

        MemoryExtractor.ExtractResult r2 =
                e2.extract(1L, 5L, "s1", List.of(msg(2, "USER", "我以后不吃苹果了")), 2L);
        assertTrue(r2.attempted());
        assertEquals(1, r2.persisted());

        List<AiMemory> all = mapper.selectByUser(1L);
        assertEquals(2, all.size());
        assertTrue(all.stream().anyMatch(m -> "superseded".equals(m.getStatus())
                && "用户喜欢苹果".equals(m.getContent())));
        assertTrue(all.stream().anyMatch(m -> "active".equals(m.getStatus())
                && "用户现在不喜欢苹果".equals(m.getContent())));
    }

    @Test
    void extract_scopeUncertain_defaultsToAgentLayer()
    {
        // scope 判不出(缺省/非 user)→ 一律 agent 层(保守方向)
        MemoryExtractor e = newExtractor(new StubChatModel(
                "[{\"content\":\"用户在本agent的工单号是X\",\"type\":\"fact\",\"scope\":\"agent\"},"
                        + "{\"content\":\"用户是Java后端\",\"type\":\"fact\"}]"));

        MemoryExtractor.ExtractResult r = e.extract(1L, 5L, "s1", List.of(msg(1, "USER", "我是Java后端")), 1L);
        assertTrue(r.attempted());
        assertEquals(2, r.persisted());

        List<AiMemory> all = mapper.selectByUser(1L);
        assertEquals(2, all.size());
        for (AiMemory m : all)
        {
            assertEquals(5L, m.getAgentId(), "判不出 scope 必须落 agent 层,不能污染用户层");
        }
    }

    @Test
    void extract_respectsMaxFactsPerRun()
    {
        String facts = "[{\"content\":\"事实1\",\"type\":\"fact\",\"scope\":\"agent\"},"
                + "{\"content\":\"事实2\",\"type\":\"fact\",\"scope\":\"agent\"},"
                + "{\"content\":\"事实3\",\"type\":\"fact\",\"scope\":\"agent\"}]";
        MemoryExtractor e = newExtractor(new StubChatModel(facts));
        try
        {
            MemoryTestSupport.setField(e, "maxFactsPerRun", 2);
        }
        catch (Exception ex)
        {
            throw new IllegalStateException(ex);
        }

        MemoryExtractor.ExtractResult r = e.extract(1L, 5L, "s1", List.of(msg(1, "USER", "abc")), 1L);
        assertTrue(r.attempted());
        assertEquals(2, r.persisted(), "单次产出不能超过 maxFactsPerRun");
        assertEquals(2, mapper.selectByUser(1L).size());
    }

    @Test
    void extract_llmFailsOrGarbage_returnsZeroQuietly()
    {
        // LLM 返回垃圾:不抛,返回 0
        MemoryExtractor e = newExtractor(new StubChatModel("这不是JSON"));
        assertEquals(false, e.extract(1L, 5L, "s1", List.of(msg(1, "USER", "你好")), 1L).attempted());

        // 空历史:返回 0
        assertEquals(false, e.extract(1L, 5L, "s1", List.of(), 1L).attempted());
        assertEquals(0, mapper.selectByUser(1L).size());
    }

    @Test
    void extract_emptyFactsArray_isDoneNotSkipped()
    {
        // LLM 合法返回 []:提炼已执行但没提炼出事实 → attempted=true(调用方可推进位点)
        MemoryExtractor e = newExtractor(new StubChatModel("[]"));
        MemoryExtractor.ExtractResult r =
                e.extract(1L, 5L, "s1", List.of(msg(1, "USER", "你好")), 1L);
        assertTrue(r.attempted(), "合法空结果应视为已提炼,允许推进位点");
        assertEquals(0, r.persisted());
        assertEquals(0, mapper.selectByUser(1L).size());
    }

    @Test
    void extract_unknownType_normalizesToFact()
    {
        MemoryExtractor e = newExtractor(new StubChatModel(
                "[{\"content\":\"用户喜欢猫\",\"type\":\"whatever\",\"scope\":\"user\"}]"));
        assertEquals(1, e.extract(1L, 5L, "s1", List.of(msg(1, "USER", "我喜欢猫")), 1L).persisted());

        AiMemory m = mapper.selectByUser(1L).get(0);
        assertEquals("fact", m.getType());
    }

    /**
     * persistFacts(压缩搭车的入口)必须写向量与 content_hash。
     *
     * <p>读侧是纯向量检索:台账有行而向量缺失 = 这条记忆永远查不出来。压缩搭车此前
     * 绕开本入口直接调 {@code memoryService.add},正是漏了这两样。
     */
    @Test
    void persistFacts_writesVectorAndHash_andDefaultsToAgentLayer()
    {
        MemoryExtractor e = newExtractor(null);
        e.setEmbedFn(MemoryExtractorTest::fakeEmbed);

        int persisted = e.persistFacts(1L, 5L, "s1", 42L, List.of(
                new MemoryExtractor.Fact("用户在北京工作", "fact", null),
                new MemoryExtractor.Fact("回复要简洁", "preference", null)));

        assertEquals(2, persisted);
        List<AiMemory> rows = mapper.selectByUser(1L);
        assertEquals(2, rows.size());
        for (AiMemory m : rows)
        {
            assertNotNull(m.getContentHash(), "必须回填 content_hash,否则后续认不出重复");
            assertTrue(vectorStore.hasVector(m.getMemoryId()),
                    "必须写向量,否则读侧永远检索不到这条记忆");
            // scope 传 null → 保守落 agent 层(spec §8.3)
            assertEquals(5L, m.getAgentId());
        }
    }

    /** 按内容哈希摊成 8 维向量:同文必同向量,够跑写入与去重断言。 */
    private static float[] fakeEmbed(String text)
    {
        float[] v = new float[8];
        int h = text == null ? 0 : text.hashCode();
        for (int i = 0; i < v.length; i++)
        {
            v[i] = ((h >>> (i * 3)) & 0x7) / 7.0f;
        }
        return v;
    }

    @Test
    void parseSupersedeTarget_parsesJson()
    {
        assertEquals(42L, MemoryExtractor.parseSupersedeTarget("{\"supersede\": 42}"));
        assertEquals(42L, MemoryExtractor.parseSupersedeTarget("回答:{\"supersede\":42}"));
        assertNull(MemoryExtractor.parseSupersedeTarget("{\"supersede\": null}"));
        assertNull(MemoryExtractor.parseSupersedeTarget("不覆盖"));
    }

    @Test
    void contentHash_normalizesPunctuation()
    {
        assertEquals(MemoryExtractor.contentHash("用户在北京工作"),
                MemoryExtractor.contentHash("用户在北京工作!"));
        assertEquals(MemoryExtractor.contentHash("用户喜欢 喝咖啡"),
                MemoryExtractor.contentHash("用户喜欢,喝咖啡"));
    }

    /** 查某个租户下第一条 active 记忆的 memoryId。 */
    private Long memoryIdOf(Long userId, long agentId)
    {
        return mapper.selectByUser(userId).stream()
                .filter(m -> m.getAgentId() != null && m.getAgentId() == agentId
                        && "active".equals(m.getStatus()))
                .findFirst().orElseThrow().getMemoryId();
    }

    /**
     * 内容相关的假 embedding:含"不"的文本向量与不含"不"的向量相关但不相同
     * (余弦 &lt; 0.92,不触发相似度去重);不含"不"的文本两两向量完全相同(相似度 1.0)。
     */
    private static final class ContentEmbed implements MemoryExtractor.EmbeddingFn
    {
        @Override
        public float[] apply(String text)
        {
            if (text != null && text.contains("不"))
            {
                return new float[]{0.8f, 0.6f, 0.1f, 0.1f};
            }
            return new float[]{0.1f, 0.2f, 0.3f, 0.4f};
        }
    }
}
