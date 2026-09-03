package com.ruoyi.system.ai.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import com.ruoyi.system.ai.agent.AgentContext;
import com.ruoyi.system.ai.agent.AgentContextFactory.OperatorHolder;
import com.ruoyi.system.ai.memory.ChatMessageRecorder;

/**
 * 锁定发给上游的消息顺序：{@code [system] + [记忆历史] + [本轮 user]}。
 *
 * <p><b>为什么单独为顺序写测试</b>：这个顺序是上游 KV-cache 命中的地基
 * （见 docs/ai/ai-prompt-cache-optimization.md §2）。阶段二自建工具循环后，顺序改由
 * {@link ChatTurnRunner#buildInitialMessagesForRun} 手工维护，不再有 advisor 兜底，
 * 更需要测试锁住。
 *
 * <p>同时锁住另一条不变量:<b>不再注入工具摘要</b>。工具往返已经作为真实的
 * assistant(tool_calls)+tool 消息留在历史里,再拼一份 200 字符摘要就是同一份信息的
 * 第二份拷贝 —— 白烧 token,还多占一段前缀。
 *
 * <p>本测试打在<b>生产路径</b> {@code buildInitialMessagesForRun} 上。此前它打在一个
 * 同名的包级方法上,而那个方法早已没有生产调用方 —— 顺序保证等于锁了个空。
 * 记忆注入的拆分语义由 {@link ChatTurnMemoryInjectionTest} 负责,这里把
 * {@code memoryRetriever} 留空,只验纯顺序。
 */
class ChatTurnMessageOrderTest
{
    private ChatTurnRunner runner;
    private RecordingChatMemory memory;

    @BeforeEach
    void setUp() throws Exception
    {
        runner = new ChatTurnRunner();
        memory = new RecordingChatMemory();
        setField(runner, "chatMemory", memory);
        // 落库侧只需不抛;本测试不关心落库内容(那是 ChatTurnMemoryInjectionTest 的职责)
        ChatMessageRecorder recorder = mock(ChatMessageRecorder.class);
        when(recorder.insert(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any())).thenReturn(1L);
        setField(runner, "messageRecorder", recorder);
        // memoryRetriever 留 null:composeMemoryInjectedText 直接返回原话,顺序不受注入影响
    }

    @Test
    void messageOrder_systemThenHistoryThenUser() throws Exception
    {
        memory.stored.add(new UserMessage("上一轮用户问题"));
        memory.stored.add(new AssistantMessage("上一轮助手回答"));

        List<Message> messages = build(context("你是测试助手"), "本轮问题");

        assertEquals(4, messages.size());
        assertInstanceOf(SystemMessage.class, messages.get(0));
        assertEquals("你是测试助手", messages.get(0).getText());

        assertEquals("上一轮用户问题", messages.get(1).getText());
        assertEquals("上一轮助手回答", messages.get(2).getText());

        assertInstanceOf(UserMessage.class, messages.get(3));
        assertEquals("本轮问题", messages.get(3).getText());
    }

    /** 工具往返已经是历史真实消息,不能再额外注入同一份工具摘要。 */
    @Test
    void noExtraToolSummaryMessage_isInjected() throws Exception
    {
        memory.stored.add(new UserMessage("上一轮"));

        List<Message> messages = build(context("sys"), "问题");

        assertTrue(messages.stream().noneMatch(m -> m.getText() != null
                        && m.getText().contains("已调用过的工具")),
                "工具摘要注入必须彻底移除");
    }

    @Test
    void emptySystemPrompt_omitsThatSlot() throws Exception
    {
        List<Message> messages = build(context(""), "问题");

        assertEquals(1, messages.size());
        assertEquals("问题", messages.get(0).getText());
    }

    @Test
    void statelessSubAgent_noConversationId_skipsMemory() throws Exception
    {
        // 子 agent 无状态:conversationId 为 null,既不读也不写记忆
        AgentContext ctx = new AgentContext(1L, "code", null, null, List.of(), "sys",
                null, 100L, null, 0);

        List<Message> messages = build(ctx, "子任务");

        assertEquals(2, messages.size());
        assertTrue(memory.added.isEmpty(), "无 conversationId 时不应写记忆");
        assertTrue(memory.readKeys.isEmpty(), "无 conversationId 时不应读记忆");
    }

    private List<Message> build(AgentContext ctx, String userText) throws Exception
    {
        ChatTurnRequest request = new ChatTurnRequest(
                "run-1", "sess-1", 1L, userText, List.of(),
                new OperatorHolder("u", 100L, false));
        return runner.buildInitialMessagesForRun(ctx, userText, null, request).messages();
    }

    private AgentContext context(String systemPrompt)
    {
        return new AgentContext(1L, "code", null, null, List.of(), systemPrompt,
                "sess-1:1", 100L, null, 0);
    }

    private static void setField(Object target, String name, Object value) throws Exception
    {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    /** 记录读写的假记忆,避免依赖数据库 */
    private static final class RecordingChatMemory implements ChatMemory
    {
        final List<Message> stored = new ArrayList<>();
        final List<Message> added = new ArrayList<>();
        final List<String> readKeys = new ArrayList<>();

        @Override
        public void add(String conversationId, List<Message> messages)
        {
            added.addAll(messages);
        }

        @Override
        public List<Message> get(String conversationId)
        {
            readKeys.add(conversationId);
            return new ArrayList<>(stored);
        }

        @Override
        public void clear(String conversationId)
        {
            stored.clear();
        }
    }
}
