package com.ruoyi.system.ai.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.system.ai.memory.ChatMessageRecorder;
import com.ruoyi.system.ai.memory.ConversationIds;
import com.ruoyi.system.ai.memory.TokenEstimator;
import com.ruoyi.system.ai.metering.CacheUsageProbe;
import com.ruoyi.system.ai.run.AgentToolLoop;
import com.ruoyi.system.ai.run.AgentToolLoopSpec;
import com.ruoyi.system.ai.run.ToolCallIdMatcher;
import com.ruoyi.system.ai.trace.TraceSpanRecorder;
import com.ruoyi.system.domain.AiAgentChild;
import com.ruoyi.system.mapper.AiLlmCallMapper;
import com.ruoyi.system.service.IAiChatSessionService;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.internal.ToolCallReactiveContextHolder;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;
import reactor.util.context.Context;

/**
 * SubAgentToolCallback 流完成判定与 AgentToolLoop 接入回归。
 *
 * <p>finish_reason 用例 mock 循环(测的是降级,不是 OpenAI 解析);
 * 工具往返用例走真实 {@link AgentToolLoop} + 存根 ChatModel。
 */
class SubAgentToolCallbackTest
{
    private static final String SESSION_ID = "s1";
    private static final String PARENT_INPUT = "{\"query\":\"做个网站\"}";

    private AiAgentChild child;
    private AgentContextFactory ctxFactory;
    private IAiChatSessionService sessionService;
    private ChatMessageRecorder recorder;
    private AiLlmCallMapper llmCallMapper;
    private List<String> events;

    @BeforeEach
    void setUp()
    {
        child = mock(AiAgentChild.class);
        when(child.getChildAgentId()).thenReturn(6L);
        when(child.getChildAgentName()).thenReturn("测试子智能体");
        when(child.getChildAgentCode()).thenReturn("test_sub");
        when(child.getTriggerDesc()).thenReturn("处理测试任务");

        AgentContext ctx = new AgentContext(6L, "test_sub", null, null, List.of(),
                "sys", null, 1L, null, 0);
        ctxFactory = mock(AgentContextFactory.class);
        when(ctxFactory.buildStateless(eq(6L), eq(SESSION_ID), any(), any(), any(), any()))
                .thenReturn(ctx);
        when(ctxFactory.lookupRunInputText(any())).thenReturn(null);

        sessionService = mock(IAiChatSessionService.class);
        recorder = mock(ChatMessageRecorder.class);
        llmCallMapper = mock(AiLlmCallMapper.class);
        when(llmCallMapper.insertLlmCall(any())).thenReturn(1);
        events = new CopyOnWriteArrayList<>();
    }

    @AfterEach
    void tearDown()
    {
        ToolCallReactiveContextHolder.clearContext();
    }

    private SubAgentToolCallback newCallback(Flux<ChatResponse> flux)
    {
        AgentToolLoop loop = mock(AgentToolLoop.class);
        when(loop.run(any())).thenAnswer(inv -> {
            AgentToolLoopSpec spec = inv.getArgument(0);
            return flux.doOnNext(response -> {
                if (response.getResult() != null && response.getResult().getOutput() != null)
                {
                    String text = response.getResult().getOutput().getText();
                    if (text != null && !text.isEmpty() && spec.reply() != null)
                    {
                        spec.reply().append(text);
                    }
                }
                if (spec.collector() != null)
                {
                    spec.collector().onResponse(response);
                }
            });
        });
        return newCallback(loop);
    }

    private SubAgentToolCallback newCallback(AgentToolLoop loop)
    {
        return new SubAgentToolCallback(child, ctxFactory, SESSION_ID, AgentCallDepth.root(),
                1L, sessionService, recorder, events::add, null,
                llmCallMapper, mock(TokenEstimator.class), null,
                new CacheUsageProbe(), null, 0L, null, loop);
    }

    @Test
    void composeAssignedUser_prefixesOriginalRequest()
    {
        when(ctxFactory.lookupRunInputText("run-9")).thenReturn("给我写个奥特曼主题的网站用vue3");
        SubAgentToolCallback cb = newCallback(Flux.empty());
        String text = cb.composeAssignedUser("请用深色霓虹风格做官网", "run-9");
        assertTrue(text.contains("[用户原始请求]"));
        assertTrue(text.contains("给我写个奥特曼主题的网站用vue3"));
        assertTrue(text.contains("[上级交办]"));
        assertTrue(text.contains("请用深色霓虹风格做官网"));
    }

    @Test
    void streamNormalStop_returnsReplyAndOkTrue()
    {
        SubAgentToolCallback cb = newCallback(Flux.just(resp("规划完成，开始建项目。", "STOP", 100, 5)));
        String result = cb.call("{\"query\":\"做个网站\"}");

        assertTrue(result.contains("规划完成"), "正常完成应返回子 agent 回答");
        assertFalse(result.contains("流未正常完成"), "STOP 不应附中断说明");
        JSONObject end = agentEnd();
        assertNotNull(end);
        assertTrue(end.getBooleanValue("ok"), "agent_end ok 应为 true");
    }

    @Test
    void streamInterruptedNoFinish_degradesOkFalseWithNotice()
    {
        // 上游断连:末包无 finish_reason(ChatGenerationMetadata.NULL)。
        // blockLast 静默返回已累积的"先规划项目结构"开头,父会误以为子还在工作 -> 必须降级。
        SubAgentToolCallback cb = newCallback(Flux.just(resp("先规划项目结构并初始化", null, 100, 5)));
        String result = cb.call("{\"query\":\"做个网站\"}");

        assertTrue(result.contains("先规划项目结构"), "部分回答应保留交回父级");
        assertTrue(result.contains("流未正常完成"), "非 STOP 应附中断说明");
        assertTrue(result.contains("无 finish_reason"), "finish=null 应说明上游响应中断");
        JSONObject end = agentEnd();
        assertNotNull(end);
        assertFalse(end.getBooleanValue("ok"), "agent_end ok 应为 false");
    }

    @Test
    void streamInterruptedToolCalls_degradesOkFalseWithNotice()
    {
        // 流末轮 TOOL_CALLS(子 agent 还想调工具但流断了,没续成下一轮):同样未正常完成。
        SubAgentToolCallback cb = newCallback(Flux.just(resp("我需要先读取文件", "TOOL_CALLS", 100, 5)));
        String result = cb.call("{\"query\":\"做个网站\"}");

        assertTrue(result.contains("流未正常完成"));
        assertTrue(result.contains("finish=TOOL_CALLS"), "应带上具体 finish 值便于排查");
        assertFalse(agentEnd().getBooleanValue("ok"));
    }

    @Test
    void doCall_buildsSpecWithNullConversationAndInvIdOwner()
    {
        AgentToolLoop loop = mock(AgentToolLoop.class);
        ArgumentCaptor<AgentToolLoopSpec> cap = ArgumentCaptor.forClass(AgentToolLoopSpec.class);
        when(loop.run(cap.capture())).thenReturn(Flux.just(resp("ok", "STOP", 1, 1)));

        bindParentMatcher();
        newCallback(loop).call(PARENT_INPUT);

        AgentToolLoopSpec spec = cap.getValue();
        assertNull(spec.conversationId(), "子 agent 无状态,Compactor/markPruned 必须跳过");
        assertEquals("parent_call", spec.ownerAgentCode(), "owner 必须是本次 invId,不是父 owner");
        assertEquals("run-9", spec.runId());
        assertEquals(6L, spec.agentId());
        assertNotNull(spec.flushThinking());
        assertEquals(2, spec.messages().size());
        assertTrue(spec.messages().get(0) instanceof SystemMessage);
        assertTrue(spec.messages().get(1) instanceof UserMessage);
        assertEquals("做个网站", spec.messages().get(1).getText());
    }

    @Test
    void doCall_realLoop_recordsPerRoundThinkingAndChildToolCallId() throws Exception
    {
        ObservingTool echo = new ObservingTool();
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .internalToolExecutionEnabled(false)
                .toolCallbacks(List.of(echo))
                .build();
        com.ruoyi.system.domain.AiModel reasoningModel = new com.ruoyi.system.domain.AiModel();
        reasoningModel.setReasoningEnabled("1");
        AgentContext ctx = new AgentContext(6L, "test_sub", new TwoRoundChatModel(), options,
                List.of(echo), "sys", null, 1L, reasoningModel, 4000);
        when(ctxFactory.buildStateless(eq(6L), eq(SESSION_ID), any(), any(), any(), any()))
                .thenReturn(ctx);

        ToolCallIdMatcher parentMatcher = bindParentMatcher();
        SubAgentToolCallback cb = newCallback(realLoop(recorder));
        String result = cb.call(PARENT_INPUT);

        assertTrue(result.contains("任务完成。"), "最终回答应交回父级");
        assertEquals(result.indexOf("任务完成。"), result.lastIndexOf("任务完成。"),
                "doOnNext 不得再累加 reply");
        assertFalse(result.contains("流未正常完成"));
        assertTrue(agentEnd().getBooleanValue("ok"));

        // 子智能体传 null conversationId,但 ai_chat_message.conversation_id 是 NOT NULL,
        // AgentToolLoop 兜底为 sessionId:agentId —— 与下面 THINKING 行同口径(c3d5d8a)
        verify(recorder).recordAssistantToolCalls(
                eq(ConversationIds.of(SESSION_ID, 6L)), eq(SESSION_ID), eq(6L), any(), anyList(),
                eq("run-9"), startsWith("tool-batch:"), isNull());
        verify(recorder, times(2)).recordThinking(
                eq(ConversationIds.of(SESSION_ID, 6L)), eq(SESSION_ID), eq(6L), anyString(),
                eq("run-9"), startsWith("reasoning:"), eq("parent_call"));

        assertNotNull(echo.matcher, "子工具必须看到本批次 matcher");
        assertNotSame(parentMatcher, echo.matcher, "不得把父 matcher 漏进子循环");
        assertEquals("call_echo_1", echo.callId);
        assertSame(parentMatcher,
                ToolCallReactiveContextHolder.getContext().get(ToolCallIdMatcher.CTX_KEY),
                "子循环结束后应把父批次上下文还回去");
    }

    private ToolCallIdMatcher bindParentMatcher()
    {
        ToolCallIdMatcher parent = new ToolCallIdMatcher(List.of(
                new AssistantMessage.ToolCall("parent_call", "function", "test_sub", PARENT_INPUT)));
        ToolCallReactiveContextHolder.setContext(Context.empty()
                .put(ToolCallIdMatcher.CTX_KEY, parent)
                .put(TraceSpanRecorder.CTX_RUN_ID, "run-9"));
        return parent;
    }

    private static AgentToolLoop realLoop(ChatMessageRecorder recorder) throws Exception
    {
        AgentToolLoop loop = new AgentToolLoop();
        setField(loop, "messageRecorder", recorder);
        return loop;
    }

    private static void setField(Object target, String name, Object value) throws Exception
    {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    /** 找到 agent_end 事件(子 agent 调用 finally 里发的)。 */
    private JSONObject agentEnd()
    {
        for (String e : events)
        {
            JSONObject o = JSON.parseObject(e);
            if ("agent_end".equals(o.getString("type")))
            {
                return o;
            }
        }
        return null;
    }

    /** 构造带文本/finish_reason/usage 的 ChatResponse(usage 给 LlmCallCollector 真实记账,避开估算路径)。 */
    private static ChatResponse resp(String text, String finishReason, int prompt, int completion)
    {
        DefaultUsage usage = new DefaultUsage(prompt, completion, prompt + completion);
        ChatResponseMetadata meta = ChatResponseMetadata.builder().model("test-model").usage(usage).build();
        ChatGenerationMetadata genMeta = finishReason != null
                ? ChatGenerationMetadata.builder().finishReason(finishReason).build()
                : ChatGenerationMetadata.NULL;
        Generation gen = new Generation(new AssistantMessage(text), genMeta);
        return new ChatResponse(List.of(gen), meta);
    }

    /** 先 tool_calls+思考,再 STOP 文本+思考。 */
    static final class TwoRoundChatModel implements ChatModel
    {
        private final AtomicInteger n = new AtomicInteger();

        @Override
        public ChatResponse call(Prompt prompt)
        {
            return stream(prompt).blockLast();
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt)
        {
            if (n.incrementAndGet() == 1)
            {
                AssistantMessage out = AssistantMessage.builder()
                        .content("先调一下")
                        .properties(Map.of("reasoningContent", "第一轮思考"))
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "call_echo_1", "function", "echo", "{}")))
                        .build();
                return Flux.just(chatResp(out, "TOOL_CALLS", 10, 4));
            }
            AssistantMessage out = AssistantMessage.builder()
                    .content("任务完成。")
                    .properties(Map.of("reasoningContent", "第二轮思考"))
                    .build();
            return Flux.just(chatResp(out, "STOP", 20, 6));
        }
    }

    private static ChatResponse chatResp(AssistantMessage out, String finish, int prompt, int completion)
    {
        DefaultUsage usage = new DefaultUsage(prompt, completion, prompt + completion);
        ChatResponseMetadata meta = ChatResponseMetadata.builder().model("test-model").usage(usage).build();
        ChatGenerationMetadata genMeta = ChatGenerationMetadata.builder().finishReason(finish).build();
        return new ChatResponse(List.of(new Generation(out, genMeta)), meta);
    }

    static final class ObservingTool implements ToolCallback
    {
        volatile ToolCallIdMatcher matcher;
        volatile String callId;

        @Override
        public ToolDefinition getToolDefinition()
        {
            return ToolDefinition.builder().name("echo").description("echo").inputSchema("{}").build();
        }

        @Override
        public String call(String toolInput)
        {
            var ctx = ToolCallReactiveContextHolder.getContext();
            if (ctx != null)
            {
                matcher = ctx.getOrDefault(ToolCallIdMatcher.CTX_KEY, null);
                if (matcher != null)
                {
                    callId = matcher.take("echo", toolInput);
                }
            }
            return "echo-ok";
        }
    }
}
