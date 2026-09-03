package com.ruoyi.system.ai.memory;

import com.ruoyi.system.domain.AiChatMessage;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@link DbChatMemory#get()} 只返回 {@code visible_to_llm='0'} 且在最新 SUMMARY 之后的消息,
 * TOOL/THINKING/中间 ASSISTANT(visible=1)绝不进 LLM 上下文(§4.5 R1)。
 *
 * <p>用原生 MyBatis + H2(mem、MySQL 模式)建表,不启动 Spring Boot 上下文,测试纯粹、快、无 Redis 依赖。
 * mapper XML 走 classpath 加载(就是 src/main 那份,含 createTableForTest 的 H2 建表语句)。
 * 装配用反射注入 private 字段(@Autowired 在无 Spring 时无效)。
 *
 * @author ruoyi
 */
class DbChatMemoryVisibleFilterTest extends ChatMessageMapperTestSupport
{
    @Test
    void get_returnsOnlyVisibleMessages()
    {
        String conv = "s1:10";
        recorder.insert(conv, "s1", 10L, "USER", "帮我查橡塑制品企业", "0", 5);
        recorder.insert(conv, "s1", 10L, "THINKING", "...", "1", 5);          // 不可见
        recorder.recordToolCall(conv, "s1", 10L, null, "loadAgentKnowledge",
                "{\"query\":\"橡塑制品\"}", "...结果...", "builtin", 100L, true); // 不可见
        recorder.insert(conv, "s1", 10L, "ASSISTANT", "我先查一下知识库", "1", 5); // 中间文本,不可见
        recorder.insert(conv, "s1", 10L, "ASSISTANT", "根据知识库查询结果…", "0", 5); // 最终回答,可见
        session.commit();

        List<Message> msgs = chatMemory.get(conv);
        // 应为:USER + 最终 ASSISTANT,共 2 条;THINKING/TOOL/中间 ASSISTANT 被过滤
        assertEquals(2, msgs.size(), "只应返回可见的 USER + 最终 ASSISTANT");
    }

    @Test
    void get_respectsSummaryBoundary()
    {
        String conv = "s1:11";
        long u1 = recorder.insert(conv, "s1", 11L, "USER", "旧问题", "0", 5);
        recorder.insert(conv, "s1", 11L, "ASSISTANT", "旧回答", "0", 5);
        // 插入 SUMMARY,summary_to_id = u1(覆盖 u1 及之前)
        AiChatMessage sum = new AiChatMessage();
        sum.setConversationId(conv);
        sum.setSessionId("s1");
        sum.setAgentId(11L);
        sum.setMessageType("SUMMARY");
        sum.setVisibleToLlm("0");
        sum.setSummaryToId(u1);
        sum.setContent("前情提要");
        sum.setTokens(3);
        sum.setCreateTime(new Date());
        mapper.insertMessage(sum);
        // SUMMARY 之后的新消息
        recorder.insert(conv, "s1", 11L, "USER", "新问题", "0", 5);
        session.commit();

        List<Message> msgs = chatMemory.get(conv);
        // 第一条应是摘要 SystemMessage,其次是新 USER
        assertEquals(2, msgs.size(), "应为摘要 + 新 USER");
        assertTrue(msgs.get(0).getText().contains("前情提要"),
                "首条应是前情提要 SystemMessage");
    }

    /**
     * 上下文预算 sum 的口径:参与上下文的都算(visible='0'),THINKING 纯展示不算。
     *
     * <p>工具往返改造后 TOOL 行是 '0'(真进上下文),老数据里的 '1' 行则不进也不算 ——
     * 两种都覆盖一下,免得口径改了没人发现。
     */
    @Test
    void sumTokensSinceSummary_countsVisibleOnly()
    {
        String conv = "s1:12";
        recorder.insert(conv, "s1", 12L, "USER", "u", "0", 10);
        recorder.insert(conv, "s1", 12L, "ASSISTANT", "a", "0", 20);
        // 新口径的 TOOL 行:visible='0',计入
        AiChatMessage tool = new AiChatMessage();
        tool.setConversationId(conv);
        tool.setSessionId("s1");
        tool.setAgentId(12L);
        tool.setMessageType("TOOL");
        tool.setVisibleToLlm("0");
        tool.setToolName("demo");
        tool.setTokens(5);
        tool.setCreateTime(new Date());
        mapper.insertMessage(tool);
        // 老数据的 TOOL 行:visible='1',不进上下文也就不该计入
        AiChatMessage legacy = new AiChatMessage();
        legacy.setConversationId(conv);
        legacy.setSessionId("s1");
        legacy.setAgentId(12L);
        legacy.setMessageType("TOOL");
        legacy.setVisibleToLlm("1");
        legacy.setToolName("legacy");
        legacy.setTokens(999);
        legacy.setCreateTime(new Date());
        mapper.insertMessage(legacy);
        // THINKING 纯展示,不计入
        recorder.insert(conv, "s1", 12L, "THINKING", "think...", "1", 100);
        session.commit();

        long sum = mapper.sumTokensSinceSummary(conv, 0);
        assertEquals(35L, sum, "应 = USER(10)+ASSISTANT(20)+新TOOL(5);老TOOL与THINKING都不计");
    }

    /**
     * {@code recordThinking} 走的是真实写入路径,把三条不变量一次锁住:
     * 落库、不进 LLM 上下文、不吃上下文预算。
     *
     * <p>上面两个用例用的是手工 {@code insert(...,"THINKING",...)},锁的是「表里长这样就该被过滤」;
     * 这里锁的是「思考功能实际写出来的行,确实长那样」—— 少了这条,写入侧改错了上面也发现不了。
     */
    @Test
    void recordThinking_persisted_butNeverEntersContextNorBudget()
    {
        String conv = "s1:20";
        recorder.insert(conv, "s1", 20L, "USER", "问题", "0", 10);
        recorder.recordThinking(conv, "s1", 20L, "我先想想该怎么答");
        recorder.insert(conv, "s1", 20L, "ASSISTANT", "回答", "0", 20);
        session.commit();

        // 落库了,且前端时间线能看到
        List<AiChatMessage> timeline = mapper.selectTimelineBySession("s1");
        assertEquals(3, timeline.size(), "USER + THINKING + ASSISTANT 都应在时间线里");
        AiChatMessage thinking = timeline.get(1);
        assertEquals("THINKING", thinking.getMessageType());
        assertEquals("1", thinking.getVisibleToLlm(), "思考绝不能标成参与上下文");
        assertEquals("我先想想该怎么答", thinking.getContent());

        // 不进 LLM 上下文
        List<Message> ctx = chatMemory.get(conv);
        assertEquals(2, ctx.size(), "LLM 只应看到 USER + ASSISTANT");
        assertTrue(ctx.stream().noneMatch(m -> m.getText().contains("我先想想")),
                "思考正文不得出现在发给模型的消息里");

        // 不吃预算
        assertEquals(30L, mapper.sumTokensSinceSummary(conv, 0),
                "预算只算 USER(10)+ASSISTANT(20),思考纯展示");
    }

    /**
     * 超长思考:表内留预览 + 路径,全文落文件(与工具结果同一套溢出机制)。
     *
     * <p><b>路径断言必须走 {@code selectMessageById}(全量投影),不能走
     * {@code selectTimelineBySession}</b>:后者用的是 {@code columnsLite},它
     * <b>故意不查 {@code tool_result_path}</b> —— 前端 timeline 只需要
     * {@code has_full_tool_result} 这个布尔来决定要不要给「展开」入口,
     * 服务端路径不该发到前端去。用 lite 投影断言路径,查到的永远是 null,
     * 看起来像「溢出没落盘」,实际上盘落得好好的。
     */
    @Test
    void recordThinking_overflowsToFile_whenTooLong() throws Exception
    {
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("thinking-test");
        com.ruoyi.system.ai.ContextFileStore store =
                new com.ruoyi.system.ai.ContextFileStore(root.toString());
        setField(recorder, "contextFileStore", store);
        setField(recorder, "inlineLimit", 100);

        String longThought = "推理".repeat(400); // 800 字符,远超 inlineLimit
        long messageId = recorder.recordThinking("s1:21", "s1", 21L, longThought);
        session.commit();

        // 全量投影:表内只留预览,完整正文的引用在 tool_result_path
        AiChatMessage row = mapper.selectMessageById(messageId);
        assertTrue(row.getContent().length() < longThought.length(), "表内应只留预览");
        assertTrue(row.getContent().contains("已截断"), "预览应带截断标记");
        assertTrue(row.getToolResultPath() != null && !row.getToolResultPath().isEmpty(),
                "溢出后必须留下文件引用");
        // 存的是相对上下文根目录的路径,必须经 store 解析而不是当成文件系统路径直接读 ——
        // 直接 readString 只在「根目录恰好等于进程工作目录」时碰巧成立。
        assertFalse(java.nio.file.Path.of(row.getToolResultPath()).isAbsolute(),
                "落库的应是相对根目录的引用,不是某台机器上的绝对路径");
        assertEquals(longThought, store.loadExternal(row.getToolResultPath()),
                "文件里应是完整正文");

        // timeline(lite 投影)这一侧的契约:不给路径,但要给「有完整内容」的标志,
        // 否则前端不知道该提供「展开」入口 —— 溢出的正文就再也没人点得到了
        AiChatMessage lite = mapper.selectTimelineBySession("s1").stream()
                .filter(m -> messageId == m.getMessageId()).findFirst().orElseThrow();
        assertNull(lite.getToolResultPath(), "timeline 是瘦身投影,不应把服务端路径发给前端");
        assertEquals(Boolean.TRUE, lite.getHasFullToolResult(),
                "溢出的思考必须在 timeline 上标出「有完整内容」,前端才会给展开入口");
    }

    /**
     * 回滚最后一轮：selectIdsFrom 圈定的范围必须与 deleteFromMessageId 删除的范围完全一致。
     * <p>两者一旦不对齐，就会有消息被删掉但对应的 ai_llm_call.message_id 没解绑，
     * 留下指向不存在消息的悬空引用。本用例锁住这个约束。
     */
    @Test
    void selectIdsFrom_matchesDeleteRange_andIsolatesOtherConversations()
    {
        String conv = "s1:10";
        String otherConv = "s1:99";
        // 第一轮(不该被回滚)
        recorder.insert(conv, "s1", 10L, "USER", "第一个问题", "0", 5);
        recorder.insert(conv, "s1", 10L, "ASSISTANT", "第一个回答", "0", 8);
        // 第二轮(要回滚的这一轮)
        long lastUserId = recorder.insert(conv, "s1", 10L, "USER", "第二个问题", "0", 5);
        recorder.insert(conv, "s1", 10L, "THINKING", "思考…", "1", 3);
        recorder.insert(conv, "s1", 10L, "ASSISTANT", "第二个回答", "0", 9);
        // 同 session 下另一个 agent 的会话：绝不能被误删
        recorder.insert(otherConv, "s1", 99L, "USER", "别的智能体的问题", "0", 4);
        session.commit();

        List<Long> ids = mapper.selectIdsFrom(conv, lastUserId);
        assertEquals(3, ids.size(), "应圈定第二轮的 USER + THINKING + ASSISTANT");
        assertTrue(ids.contains(lastUserId), "回滚起点 USER 本身必须包含在内");

        int deleted = mapper.deleteFromMessageId(conv, lastUserId);
        session.commit();
        assertEquals(ids.size(), deleted, "解绑范围与删除范围必须一致，否则 llm_call 会留下悬空 message_id");

        // 第一轮与其它 conversation 完好
        assertEquals(2, mapper.selectVisible(conv, 0).size(), "第一轮的 USER/ASSISTANT 应保留");
        assertEquals(1, mapper.selectVisible(otherConv, 0).size(), "其它 conversation 不受影响");
    }

    @Test
    void clearBySessionRemovesEveryAgentConversationButNotOtherSessions()
    {
        recorder.insert("s1:10", "s1", 10L, "USER", "agent-10", "0", 2);
        recorder.insert("s1:11", "s1", 11L, "USER", "agent-11", "0", 2);
        recorder.insert("s2:10", "s2", 10L, "USER", "other-session", "0", 2);
        session.commit();

        assertEquals(2, mapper.deleteBySessionId("s1"));
        session.commit();

        assertEquals(0, mapper.countBySession("s1"));
        assertEquals(1, mapper.countBySession("s2"));
    }

    /** 新增两列必须能写进去也能读回来 —— 漏改 resultMap 或 columns 会在这里挂 */
    @Test
    void toolCallsAndPrunedColumns_roundTrip()
    {
        String conv = "s1:30";
        String json = "[{\"id\":\"call_1\",\"type\":\"function\","
                + "\"name\":\"readFile\",\"arguments\":\"{\\\"path\\\":\\\"/a.txt\\\"}\"}]";
        AiChatMessage m = new AiChatMessage();
        m.setConversationId(conv);
        m.setSessionId("s1");
        m.setAgentId(30L);
        m.setMessageType("ASSISTANT");
        m.setVisibleToLlm("0");
        m.setToolCalls(json);
        m.setPruned("0");
        m.setTokens(7);
        m.setCreateTime(new Date());
        mapper.insertMessage(m);
        session.commit();

        AiChatMessage got = mapper.selectVisible(conv, 0).get(0);
        assertEquals(json, got.getToolCalls(), "tool_calls 必须原样往返");
        assertEquals("0", got.getPruned(), "pruned 默认应为 0");
    }

    /**
     * 长会话不得被消息条数静默截断。上下文何时压缩只由 token 预算决定，
     * 否则工具密集的会话很容易超过 200 行，模型将永远看不到最新轮次。
     */
    @Test
    void get_keepsNewestMessagesBeyondLegacyRowLimit()
    {
        String conv = "s1:40";
        for (int i = 0; i < 205; i++)
        {
            recorder.insert(conv, "s1", 40L, "USER", "message-" + i, "0", 1);
        }
        session.commit();

        List<Message> messages = chatMemory.get(conv);
        assertEquals(205, messages.size(), "未触发 token 压缩时不得按行数截断上下文");
        assertEquals("message-204", messages.get(messages.size() - 1).getText(),
                "最新消息必须进入模型上下文");
    }

    @Test
    void selectByRunId_returnsCompleteOrderedLedgerOnlyForThatRun()
    {
        recorder.insert("s1:40", "s1", 40L, "USER", "question", "0", 1,
                "run-1", ChatMessageKind.USER_INPUT, "user", null);
        recorder.recordThinking("s1:40", "s1", 40L, "thinking",
                "run-1", "reasoning:1", null);
        recorder.insert("s1:40", "s1", 40L, "ASSISTANT", "answer", "0", 1,
                "run-1", ChatMessageKind.ASSISTANT_FINAL, "answer", null);
        recorder.insert("s1:40", "s1", 40L, "USER", "other", "0", 1,
                "run-2", ChatMessageKind.USER_INPUT, "user", null);
        session.commit();

        List<AiChatMessage> ledger = mapper.selectByRunId("run-1");
        assertEquals(3, ledger.size());
        assertEquals("question", ledger.get(0).getContent());
        assertEquals("thinking", ledger.get(1).getContent());
        assertEquals("answer", ledger.get(2).getContent());
        assertTrue(ledger.stream().allMatch(row -> "run-1".equals(row.getRunId())));
    }

    @Test
    void timelineRange_returnsWholeToolDenseTurnWithoutFixedMultiplierCap()
    {
        long first = recorder.insert("s1:50", "s1", 50L, "USER", "long turn", "0", 1,
                "run-long", ChatMessageKind.USER_INPUT, "user", null);
        for (int i = 0; i < 350; i++)
        {
            recorder.recordThinking("s1:50", "s1", 50L, "step-" + i,
                    "run-long", "reasoning:" + i, null);
        }
        recorder.insert("s1:50", "s1", 50L, "ASSISTANT", "done", "0", 1,
                "run-long", ChatMessageKind.ASSISTANT_FINAL, "answer", null);
        session.commit();

        List<AiChatMessage> complete = mapper.selectTimelineRangeFrom("s1", first, null);
        assertEquals(352, complete.size(), "完整轮次不得被页大小倍数截断");
        assertEquals("done", complete.get(complete.size() - 1).getContent());
    }
}
