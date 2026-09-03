package com.ruoyi.system.ai.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import com.ruoyi.system.ai.agent.AgentContext;
import com.ruoyi.system.ai.agent.AgentContextFactory.OperatorHolder;
import com.ruoyi.system.ai.memory.ChatMessageRecorder;
import com.ruoyi.system.ai.memory.longterm.MemoryInjection;
import com.ruoyi.system.ai.memory.longterm.MemoryRetriever;

/**
 * 记忆读侧注入的红线测试(记忆系统 spec §7.1):注入<b>只进发送给模型的那份</b>,
 * 落库({@code messageRecorder.insert} / {@code chatMemory.add})用用户原话。
 *
 * <p>同时锁住消息顺序不变({@code [system] + [历史] + [本轮 user]},与
 * {@code ChatTurnMessageOrderTest} 一致)与旁路语义(检索失败/未配置时原样发送)。
 *
 * <p>不启动 Spring 上下文:new {@link ChatTurnRunner} + 反射注入假 {@link ChatMemory} /
 * mock {@link ChatMessageRecorder} / mock {@link MemoryRetriever},走包级方法
 * {@code buildInitialMessagesForRun} 验证发送版与落库版确实被拆开。
 */
class ChatTurnMemoryInjectionTest
{
    private ChatTurnRunner runner;
    private RecordingChatMemory memory;
    private ChatMessageRecorder messageRecorder;
    private MemoryRetriever memoryRetriever;

    @BeforeEach
    void setUp() throws Exception
    {
        runner = new ChatTurnRunner();
        memory = new RecordingChatMemory();
        messageRecorder = mock(ChatMessageRecorder.class);
        memoryRetriever = mock(MemoryRetriever.class);
        setField(runner, "chatMemory", memory);
        setField(runner, "messageRecorder", messageRecorder);
        setField(runner, "memoryRetriever", memoryRetriever);
    }

    /** 注入进发送版、不进落库(messageRecorder.insert 收到的是用户原话)。 */
    @Test
    void injection_goesToModelOnly_recordKeepsOriginal() throws Exception
    {
        String injectionText = "<user_memory>\n以下是系统检索到的该用户已知背景,非用户本轮输入:\n"
                + "- [fact] 用户在北京工作\n</user_memory>\n";
        when(memoryRetriever.retrieve(100L, 5L, "真实用户输入"))
                .thenReturn(new MemoryInjection(Optional.of(injectionText), List.of(1L, 2L)));
        when(messageRecorder.insert(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any())).thenReturn(7L);

        AgentContext ctx = context("sys");
        ChatTurnRequest request = new ChatTurnRequest(
                "run-1", "sess", 5L, "真实用户输入", List.of(), new OperatorHolder("u", 100L, false));

        List<Message> messages = invokeBuildInitialMessagesForRun(ctx, "真实用户输入", request);

        // 顺序不变:[system] + [本轮 user]
        assertEquals(2, messages.size());
        assertInstanceOf(SystemMessage.class, messages.get(0));
        UserMessage sent = (UserMessage) messages.get(1);
        assertTrue(sent.getText().startsWith(injectionText), "发送版必须带注入文本");
        assertTrue(sent.getText().endsWith("\n\n真实用户输入"), "注入在前,用户原话在后: " + sent.getText());

        // 落库版必须还是用户原话 —— 注入内容不得进 ai_chat_message(审计流不被污染,spec §7.1)
        verify(messageRecorder).insert(any(), any(), any(), eq("USER"), eq("真实用户输入"),
                any(), any(), any(), any(), any(), any());
    }

    /** 无注入时:发送版与落库版都是原话,messageRecorder 照常落库。 */
    @Test
    void noInjection_sendsOriginalText() throws Exception
    {
        when(memoryRetriever.retrieve(100L, 5L, "普通问题"))
                .thenReturn(MemoryInjection.none());
        when(messageRecorder.insert(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any())).thenReturn(7L);

        AgentContext ctx = context("sys");
        ChatTurnRequest request = new ChatTurnRequest(
                "run-1", "sess", 5L, "普通问题", List.of(), new OperatorHolder("u", 100L, false));

        List<Message> messages = invokeBuildInitialMessagesForRun(ctx, "普通问题", request);

        UserMessage sent = (UserMessage) messages.get(1);
        assertEquals("普通问题", sent.getText());
        verify(messageRecorder).insert(any(), any(), any(), eq("USER"), eq("普通问题"),
                any(), any(), any(), any(), any(), any());
    }

    /** 未装配 MemoryRetriever(配置缺失/记忆模块未启用)时原样发送,不注入不报错 —— 旁路语义。 */
    @Test
    void noRetrieverConfigured_sendsOriginalText() throws Exception
    {
        setField(runner, "memoryRetriever", null);
        when(messageRecorder.insert(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any())).thenReturn(7L);

        AgentContext ctx = context("sys");
        ChatTurnRequest request = new ChatTurnRequest(
                "run-1", "sess", 5L, "普通问题", List.of(), new OperatorHolder("u", 100L, false));

        List<Message> messages = invokeBuildInitialMessagesForRun(ctx, "普通问题", request);

        assertEquals("普通问题", ((UserMessage) messages.get(1)).getText());
        verify(memoryRetriever, never()).retrieve(any(), any(), any());
    }

    /** 无登录身份(userId=null)时注入被跳过,对话照常 —— 记忆是旁路,不能因为没有 userId 卡死对话。 */
    @Test
    void noOperatorUserId_skipsInjection_butStillSendsAndRecords() throws Exception
    {
        when(messageRecorder.insert(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any())).thenReturn(7L);

        AgentContext ctx = context("sys");
        ChatTurnRequest request = new ChatTurnRequest(
                "run-1", "sess", 5L, "普通问题", List.of(), null);

        List<Message> messages = invokeBuildInitialMessagesForRun(ctx, "普通问题", request);

        assertEquals("普通问题", ((UserMessage) messages.get(1)).getText());
        verify(memoryRetriever, never()).retrieve(any(), any(), any());
        verify(messageRecorder).insert(any(), any(), any(), eq("USER"), eq("普通问题"),
                any(), any(), any(), any(), any(), any());
    }

    /** 纯函数:composeMemoryInjectedText 的拼接规则(注入在前,用户原话在后)。 */
    @Test
    void composeMemoryInjectedText_concatenatesInjectionBeforeOriginal()
    {
        String injectionText = "<user_memory>\n背景\n</user_memory>\n";
        when(memoryRetriever.retrieve(100L, 5L, "真实用户输入"))
                .thenReturn(new MemoryInjection(Optional.of(injectionText), List.of()));

        String text = ChatTurnRunner.composeMemoryInjectedText("真实用户输入", 100L, 5L, memoryRetriever);

        assertTrue(text.startsWith(injectionText));
        assertTrue(text.endsWith("真实用户输入"));
        assertEquals(injectionText + "\n\n真实用户输入", text);
    }

    /** 纯函数:注入为空时原样返回用户原话。 */
    @Test
    void composeMemoryInjectedText_noInjection_returnsOriginal()
    {
        when(memoryRetriever.retrieve(100L, 5L, "普通问题")).thenReturn(MemoryInjection.none());
        assertEquals("普通问题",
                ChatTurnRunner.composeMemoryInjectedText("普通问题", 100L, 5L, memoryRetriever));
        assertEquals("普通问题", ChatTurnRunner.composeMemoryInjectedText("普通问题", null, 5L, memoryRetriever));
        assertEquals("普通问题", ChatTurnRunner.composeMemoryInjectedText("普通问题", 100L, 5L, null));
    }

    /** 子 agent(无 conversationId):不落库,但注入照常进发送版。 */
    @Test
    void statelessSubAgent_injectionStillApplied_noRecord() throws Exception
    {
        String injectionText = "<user_memory>\n背景\n</user_memory>\n";
        when(memoryRetriever.retrieve(100L, 5L, "子任务输入"))
                .thenReturn(new MemoryInjection(Optional.of(injectionText), List.of(9L)));

        AgentContext ctx = new AgentContext(5L, "code", null, null, List.of(), "sys",
                null, 100L, null, 0);
        ChatTurnRequest request = new ChatTurnRequest(
                "run-1", "sess", 5L, "子任务输入", List.of(), new OperatorHolder("u", 100L, false));

        List<Message> messages = invokeBuildInitialMessagesForRun(ctx, "子任务输入", request);

        assertEquals(2, messages.size());
        assertTrue(((UserMessage) messages.get(1)).getText().startsWith(injectionText));
        // 无 conversationId:不写 ai_chat_message
        verify(messageRecorder, never()).insert(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any());
        assertTrue(memory.readKeys.isEmpty());
    }

    // ---- helpers ----

    private AgentContext context(String systemPrompt)
    {
        return new AgentContext(5L, "code", null, null, List.of(), systemPrompt,
                "sess-1:5", 100L, null, 0);
    }

    private List<Message> invokeBuildInitialMessagesForRun(AgentContext ctx, String userText,
                                                           ChatTurnRequest request) throws Exception
    {
        return runner.buildInitialMessagesForRun(ctx, userText, null, request).messages();
    }

    private static void setField(Object target, String name, Object value) throws Exception
    {
        java.lang.reflect.Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    /** 记录读写的假记忆,避免依赖数据库(同 ChatTurnMessageOrderTest) */
    private static final class RecordingChatMemory implements ChatMemory
    {
        final List<Message> stored = new ArrayList<>();
        final List<String> readKeys = new ArrayList<>();

        @Override
        public void add(String conversationId, List<Message> messages)
        {
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
