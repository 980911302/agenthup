package com.ruoyi.system.ai.context;

import com.ruoyi.system.ai.memory.ChatMessageMapperTestSupport;
import com.ruoyi.system.ai.memory.ContextBudget;
import java.util.ArrayList;
import com.ruoyi.system.ai.memory.longterm.IdleSessionExtractScheduler;
import com.ruoyi.system.ai.memory.longterm.InMemoryMemoryVectorStore;
import com.ruoyi.system.ai.memory.longterm.MemoryExtractor;
import com.ruoyi.system.ai.memory.longterm.MemoryServiceImpl;
import com.ruoyi.system.domain.AiChatMessage;
import com.ruoyi.system.domain.AiChatSession;
import com.ruoyi.system.domain.AiMemory;
import com.ruoyi.system.mapper.AiChatSessionMapper;
import com.ruoyi.system.mapper.AiLlmCallMapper;
import com.ruoyi.system.mapper.AiMemoryMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import javax.sql.DataSource;
import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 压缩搭车提炼的测试(spec §8.1 / §13 红线)。
 *
 * <p>护住两条铁律:
 * <ul>
 *   <li><b>红线</b>:{@code <facts>} 畸形(JSON 不合法)时摘要仍正常落库 —— 压缩是关键路径,
 *       搭车的不能掀翻车;</li>
 *   <li>正常时 facts 被解析并写入记忆(默认落 agent 层,spec §8.3 保守方向);</li>
 *   <li>开关 {@code ai.memory.extract.piggyback-compaction} 关闭时退化为纯摘要,不解析 facts。</li>
 * </ul>
 *
 * <p>基座:复用 {@code ChatMessageMapperTestSupport}(H2 建 ai_chat_message 跑压缩) +
 * 内联 {@code MemoryTestSupport} 同款装配(H2 建 ai_memory + {@link InMemoryMemoryVectorStore})
 * 构造「半真实」的 {@link ContextCompactor}:聊天侧走真 recorder/mapper,记忆侧走真
 * {@link MemoryServiceImpl},会话主行用 mock(其查询 join 多张表,非本测试关注点)。
 */
class ContextCompactorMemoryPiggybackTest extends ChatMessageMapperTestSupport
{
    private static final Long USER_ID = 100L;
    private static final Long AGENT_ID = 61L;
    private static final String SESSION_ID = "s1";

    private SqlSession memorySession;
    private AiMemoryMapper memoryMapper;
    private MemoryServiceImpl memoryService;
    private InMemoryMemoryVectorStore vectorStore;
    private MemoryExtractor memoryExtractor;
    private FakeProgressStore progressStore;
    private AiChatSessionMapper sessionMapper;
    private AiLlmCallMapper llmCallMapper;

    @BeforeEach
    void setUpMemory() throws Exception
    {
        // 同 MemoryTestSupport:独立 H2(mem、MySQL 模式)+ 原生 MyBatis 装配 AiMemoryMapper
        String dbName = "mempiggyback_" + getClass().getSimpleName();
        DataSource ds = org.h2.jdbcx.JdbcConnectionPool.create(
                "jdbc:h2:mem:" + dbName + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        Environment env = new Environment("test", new JdbcTransactionFactory(), ds);
        Configuration config = new Configuration(env);
        try (InputStream in = getClass().getResourceAsStream("/mapper/system/AiMemoryMapper.xml"))
        {
            assert in != null : "mapper xml 未找到";
            XMLMapperBuilder builder = new XMLMapperBuilder(in, config,
                    "mapper/system/AiMemoryMapper.xml", config.getSqlFragments());
            builder.parse();
        }
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(config);
        memorySession = factory.openSession();
        memoryMapper = memorySession.getMapper(AiMemoryMapper.class);
        // H2 库跨用例常驻(DB_CLOSE_DELAY=-1),先删后建保证每个用例从空表开始,
        // 否则 selectByUser 会看到上一个用例落库的记忆(本类断言的是全量条数)
        memoryMapper.dropTableForTest();
        memoryMapper.createTableForTest();

        vectorStore = new InMemoryMemoryVectorStore();
        MemoryServiceImpl svc = new MemoryServiceImpl();
        setField(svc, "memoryMapper", memoryMapper);
        setField(svc, "vectorStore", vectorStore);
        memoryService = svc;

        // 会话主行 mock(selectAiChatSessionById 的 SQL join 多张表,非本测试关注点):
        // 只 stub 出「该会话属于哪个用户」,记忆租户 userId 从这解析
        sessionMapper = mock(AiChatSessionMapper.class);
        AiChatSession session = new AiChatSession();
        session.setSessionId(SESSION_ID);
        session.setUserId(USER_ID);
        when(sessionMapper.selectAiChatSessionById(SESSION_ID)).thenReturn(session);

        // 搭车落库统一走 MemoryExtractor.persistFacts,和空闲扫描同一条路径 ——
        // 这样才拿得到向量与 content_hash(此前搭车直接调 memoryService.add,两样都没有)
        MemoryExtractor ex = new MemoryExtractor();
        setField(ex, "memoryService", memoryService);
        setField(ex, "memoryMapper", memoryMapper);
        setField(ex, "vectorStore", vectorStore);
        setField(ex, "maxFactsPerRun", 10);
        setField(ex, "dedupThreshold", 0.92);
        setField(ex, "timeoutSeconds", 0L);
        memoryExtractor = ex;
        progressStore = new FakeProgressStore();
    }

    /** 只记调用,断言搭车是否推进了空闲扫描位点。 */
    private static final class FakeProgressStore
            implements IdleSessionExtractScheduler.ProgressStore
    {
        final List<Long> advanced = new ArrayList<>();

        @Override
        public List<IdleSessionExtractScheduler.ExtractTarget> candidates()
        {
            return List.of();
        }

        @Override
        public void advance(String sessionId, Long agentId, Long userId, long extractToMessageId)
        {
            advanced.add(extractToMessageId);
        }

        @Override
        public void markFailure(String sessionId, Long agentId, Long userId)
        {
        }
    }

    @AfterEach
    void tearDownMemory()
    {
        if (memorySession != null)
        {
            memorySession.commit();
            memorySession.close();
        }
    }

    /* ============ 红线:facts 畸形不能丢摘要 ============ */

    /** spec §13 红线:<facts> JSON 不合法时,摘要仍正常落库,本次不提炼。 */
    @Test
    void malformedFacts_summaryStillPersisted_noFactsSaved() throws Exception
    {
        seedHistory("c1");
        ContextCompactor compactor = newCompactor(true);

        // 摘要合法、facts 段是坏 JSON(缺右括号) —— 最容易踩的红线场景
        String bad = "<summary>这是前情提要</summary><facts>[{\"content\":\"用户在北京工作\",\"type\":\"fact\"}</facts>";
        assertTrue(compactor.compactIfNeeded("c1", SESSION_ID, AGENT_ID,
                new StubChatModel(bad), 2000, 500, 7L, "qwen-max"));

        AiChatMessage summary = recorder.latestSummary("c1");
        assertNotNull(summary, "facts 畸形绝不能丢摘要");
        assertEquals("这是前情提要", summary.getContent());
        assertTrue(memoriesOf(USER_ID).isEmpty(), "facts 畸形时不该落库任何记忆");
    }

    /** <facts> 标签整个缺失(旧格式纯文本):摘要照常落库,不提炼。 */
    @Test
    void missingFactsTag_summaryStillPersisted() throws Exception
    {
        seedHistory("c2");
        ContextCompactor compactor = newCompactor(true);

        assertTrue(compactor.compactIfNeeded("c2", SESSION_ID, AGENT_ID,
                new StubChatModel("纯文本提要"), 2000, 500, 7L, "qwen-max"));

        assertEquals("纯文本提要", recorder.latestSummary("c2").getContent());
        assertTrue(memoriesOf(USER_ID).isEmpty());
    }

    /** 摘要段缺失时整个压缩仍按原逻辑:摘要空则跳过,不写 SUMMARY 也不提炼。 */
    @Test
    void missingSummaryTag_skipsCompaction() throws Exception
    {
        seedHistory("c3");
        ContextCompactor compactor = newCompactor(true);

        String noSummary = "<facts>[{\"content\":\"x\",\"type\":\"fact\"}]</facts>";
        assertFalse(compactor.compactIfNeeded("c3", SESSION_ID, AGENT_ID,
                new StubChatModel(noSummary), 2000, 500, 7L, "qwen-max"));
        assertTrue(memoriesOf(USER_ID).isEmpty(), "压缩被跳过时绝不能提炼");
    }

    /* ============ 正常:facts 被解析并写入记忆 ============ */

    /** 摘要 + 合法 facts:摘要落库,两条事实写入记忆(默认 agent 层)。 */
    @Test
    void validFacts_persistedToAgentScope() throws Exception
    {
        seedHistory("c4");
        ContextCompactor compactor = newCompactor(true);

        String ok = """
                <summary>这是前情提要</summary>
                <facts>[{"content":"用户在北京工作","type":"fact"},
                         {"content":"回复要简洁","type":"preference"}]</facts>
                """;
        assertTrue(compactor.compactIfNeeded("c4", SESSION_ID, AGENT_ID,
                new StubChatModel(ok), 2000, 500, 7L, "qwen-max"));

        assertEquals("这是前情提要", recorder.latestSummary("c4").getContent());

        List<AiMemory> memories = memoriesOf(USER_ID);
        assertEquals(2, memories.size(), "两条事实应写入记忆");
        AiMemory fact = memoryByContent(memories, "用户在北京工作");
        AiMemory pref = memoryByContent(memories, "回复要简洁");
        assertEquals("fact", fact.getType());
        assertEquals("preference", pref.getType());
        // spec §8.3 保守方向:压缩搭车默认落 agent 层,不升用户层
        assertEquals(AGENT_ID, fact.getAgentId());
        assertEquals(AGENT_ID, pref.getAgentId());
        // 提炼位点复用压缩 boundaryId:sourceMessageId 必须落在摘要覆盖范围内(>0)
        assertNotNull(fact.getSourceMessageId());
        assertTrue(fact.getSourceMessageId() > 0, "提炼位点应记录压缩边界");
        assertEquals(SESSION_ID, fact.getSourceSessionId());

        // 走没走统一落库入口(MemoryExtractor.persistFacts)的判据:content_hash 被回填了。
        // 此前搭车直接调 memoryService.add,hash 和向量都没有 —— 结果是这条记忆既认不出
        // 重复、读侧也永远检索不到(读侧是纯向量检索)。
        // 向量写入本身由 MemoryExtractorTest.persistFacts_writesVector 覆盖(那个测试在
        // 同一个包里,能接 setEmbedFn;不为跨包断言放宽生产可见性)。
        assertNotNull(fact.getContentHash(), "搭车必须走统一入口:content_hash 应被回填");
        assertNotNull(pref.getContentHash(), "搭车必须走统一入口:content_hash 应被回填");

        // 位点必须同步推进:不推的话 30 分钟后兜底扫描会重提炼同一段历史
        assertEquals(1, progressStore.advanced.size(), "搭车提炼后应推进空闲扫描位点");
        assertEquals(fact.getSourceMessageId(), progressStore.advanced.get(0),
                "推进的位点应等于压缩 boundaryId");
    }

    /** 事实条数超过上限时只取前 N 条(对齐 spec §8.4 单次产出上限)。 */
    @Test
    void tooManyFacts_trimmedToLimit() throws Exception
    {
        seedHistory("c5");
        ContextCompactor compactor = newCompactor(true);

        StringBuilder facts = new StringBuilder("[");
        for (int i = 0; i < 15; i++)
        {
            if (i > 0)
            {
                facts.append(',');
            }
            facts.append("{\"content\":\"事实").append(i).append("\",\"type\":\"fact\"}");
        }
        facts.append(']');
        String raw = "<summary>提要</summary><facts>" + facts + "</facts>";

        assertTrue(compactor.compactIfNeeded("c5", SESSION_ID, AGENT_ID,
                new StubChatModel(raw), 2000, 500, 7L, "qwen-max"));

        assertEquals(10, memoriesOf(USER_ID).size(), "单次产出不得超过上限(默认10)");
    }

    /** 未知类型收敛到 fact;content 缺失的条目被丢弃。 */
    @Test
    void unknownType_fallsBackToFact() throws Exception
    {
        seedHistory("c6");
        ContextCompactor compactor = newCompactor(true);

        String raw = "<summary>提要</summary><facts>[" +
                "{\"content\":\"怪类型\",\"type\":\"gossip\"}," +
                "{\"content\":\"\",\"type\":\"fact\"}," +
                "{\"type\":\"preference\"}]</facts>";
        assertTrue(compactor.compactIfNeeded("c6", SESSION_ID, AGENT_ID,
                new StubChatModel(raw), 2000, 500, 7L, "qwen-max"));

        List<AiMemory> memories = memoriesOf(USER_ID);
        assertEquals(1, memories.size(), "未知类型收敛为 fact,空 content 丢弃");
        assertEquals("fact", memories.get(0).getType());
        assertEquals("怪类型", memories.get(0).getContent());
    }

    /* ============ 开关关闭:不解析 facts,只出摘要 ============ */

    /** piggyback-compaction=false:模型输出里带着 facts 也不解析不落库,退化为纯摘要。 */
    @Test
    void switchOff_ignoresFacts_summaryOnly() throws Exception
    {
        seedHistory("c7");
        ContextCompactor compactor = newCompactor(false);

        String raw = "<summary>纯摘要</summary><facts>[{\"content\":\"用户在北京工作\",\"type\":\"fact\"}]</facts>";
        CapturingChatModel capturing = new CapturingChatModel(raw);
        assertTrue(compactor.compactIfNeeded("c7", SESSION_ID, AGENT_ID,
                capturing, 2000, 500, 7L, "qwen-max"));

        assertEquals("纯摘要", recorder.latestSummary("c7").getContent());
        assertTrue(memoriesOf(USER_ID).isEmpty(), "开关关闭时不落库任何记忆");
        assertFalse(capturing.lastPrompt.contains("<facts>"),
                "开关关闭时 prompt 不应要求 facts 段(省输出 token)");
    }

    /* ============ 缺失依赖:记忆组件未装配时压缩照常 ============ */

    /** memoryExtractor 未注入(required=false 未装配)时,压缩照常成功,不提炼不炸。 */
    @Test
    void noMemoryExtractor_compactionStillSucceeds() throws Exception
    {
        seedHistory("c8");
        ContextCompactor compactor = newCompactor(true);
        setField(compactor, "memoryExtractor", null); // 模拟记忆组件未启用

        String raw = "<summary>提要</summary><facts>[{\"content\":\"用户在北京工作\",\"type\":\"fact\"}]</facts>";
        assertTrue(compactor.compactIfNeeded("c8", SESSION_ID, AGENT_ID,
                new StubChatModel(raw), 2000, 500, 7L, "qwen-max"));
        assertEquals("提要", recorder.latestSummary("c8").getContent());
    }

    /* ============ parseFacts 纯解析 ============ */

    @Test
    void parseFacts_malformedJson_returnsEmpty()
    {
        assertEquals(List.of(), ContextCompactor.parseFacts("<facts>[{\"content\":\"x\"</facts>"));
        assertEquals(List.of(), ContextCompactor.parseFacts("没有facts段"));
        assertEquals(List.of(), ContextCompactor.parseFacts(null));
    }

    @Test
    void parseFacts_validJson_returnsTypedFacts()
    {
        List<ContextCompactor.ExtractedFact> facts = ContextCompactor.parseFacts(
                "<summary>s</summary><facts>[{\"content\":\"a\",\"type\":\"fact\"}," +
                "{\"content\":\"b\",\"type\":\"preference\"}]</facts>");
        assertEquals(2, facts.size());
        assertEquals("a", facts.get(0).content());
        assertEquals("fact", facts.get(0).type());
        assertEquals("b", facts.get(1).content());
        assertEquals("preference", facts.get(1).type());
    }

    /* ============ 基座装配 ============ */

    private void seedHistory(String conversationId)
    {
        for (int i = 0; i < 6; i++)
        {
            recorder.insert(conversationId, SESSION_ID, AGENT_ID, "USER", "问题" + i, "0", 900);
            recorder.insert(conversationId, SESSION_ID, AGENT_ID, "ASSISTANT", "回答" + i, "0", 900);
        }
        session.commit();
    }

    private List<AiMemory> memoriesOf(Long userId)
    {
        memorySession.commit();
        return memoryMapper.selectByUser(userId);
    }

    private static AiMemory memoryByContent(List<AiMemory> memories, String content)
    {
        return memories.stream().filter(m -> content.equals(m.getContent()))
                .findFirst().orElseThrow(() -> new AssertionError("未找到记忆: " + content));
    }

    private ContextCompactor newCompactor(boolean piggybackEnabled) throws Exception
    {
        ContextCompactor c = new ContextCompactor();
        setField(c, "recorder", recorder);
        setField(c, "tokenEstimator", tokenEstimator);
        setField(c, "contextBudget", new StubBudget());
        setField(c, "enabled", true);
        setField(c, "keepRecentTurns", 2);
        llmCallMapper = mock(AiLlmCallMapper.class);
        setField(c, "llmCallMapper", llmCallMapper);
        setField(c, "memoryExtractor", memoryExtractor);
        setField(c, "extractProgressStore", progressStore);
        setField(c, "sessionMapper", sessionMapper);
        setField(c, "piggybackCompactionEnabled", piggybackEnabled);
        return c;
    }

    /** 避开 ISysConfigService(要 Redis),threshold 与 target 都给纯计算结果。 */
    private static final class StubBudget extends ContextBudget
    {
        @Override
        public int threshold(Integer window, Integer maxOutput)
        {
            return testInputBudget(window, maxOutput) * 80 / 100;
        }

        @Override
        public int target(Integer window, Integer maxOutput)
        {
            return testInputBudget(window, maxOutput) * 40 / 100;
        }

        private static int testInputBudget(Integer window, Integer maxOutput)
        {
            int w = window != null ? window : 32768;
            int reserved = maxOutput != null ? maxOutput : w / 8;
            return Math.max(w - reserved, w / 2);
        }
    }

    private static class StubChatModel implements ChatModel
    {
        private final String reply;

        StubChatModel(String reply)
        {
            this.reply = reply;
        }

        @Override
        public ChatResponse call(Prompt prompt)
        {
            return new ChatResponse(List.of(new Generation(new AssistantMessage(reply))));
        }
    }

    /** 记下压缩模型实际收到的 prompt,用来断言开关关闭时 prompt 退化为纯摘要。 */
    private static final class CapturingChatModel extends StubChatModel
    {
        private String lastPrompt = "";

        CapturingChatModel(String reply)
        {
            super(reply);
        }

        @Override
        public ChatResponse call(Prompt prompt)
        {
            lastPrompt = prompt.getInstructions().stream()
                    .map(org.springframework.ai.chat.messages.Message::getText)
                    .reduce("", (a, b) -> a + b);
            return super.call(prompt);
        }
    }
}
