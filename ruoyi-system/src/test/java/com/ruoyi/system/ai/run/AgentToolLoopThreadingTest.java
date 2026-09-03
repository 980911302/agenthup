package com.ruoyi.system.ai.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;

import com.ruoyi.system.ai.agent.ModelInputModalities;
import com.ruoyi.system.ai.event.ChatEventSink;
import com.ruoyi.system.ai.memory.ChatMessageMapperTestSupport;
import com.ruoyi.system.ai.metering.LlmCallCollector;

import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * chunk 消费必须离开上游信号线程:chatModel.stream 的信号在 WebClient 共享的
 * reactor-http-nio 事件循环上,而 emit 链路(→ broker.publish 的 Redis/JDBC、
 * collector 轮界 flush)一旦变慢会拖累全实例所有流式连接。
 * 锁住 publishOn(boundedElastic) 这一线程模型决策。
 */
class AgentToolLoopThreadingTest
{
    @Test
    void chunkConsumption_runsOffEmittingThread() throws Exception
    {
        AgentToolLoop loop = new AgentToolLoop();
        ChatMessageMapperTestSupport.setField(loop, "llmIdleTimeoutSeconds", 60L);
        // 模拟 nio 事件循环:上游信号固定在专用单线程上发射
        Scheduler nioSim = Schedulers.newSingle("nio-sim");
        try
        {
            ChatModel model = mock(ChatModel.class);
            when(model.stream(any(Prompt.class))).thenReturn(
                    Flux.just(textResponse("甲"), textResponse("乙")).subscribeOn(nioSim));

            Set<String> emitThreads = ConcurrentHashMap.newKeySet();
            ChatEventSink sink = eventJson -> emitThreads.add(Thread.currentThread().getName());
            StringBuilder reply = new StringBuilder();
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<Throwable> error = new AtomicReference<>();

            loop.run(spec(model, reply, sink)).subscribe(r -> { }, e -> {
                error.set(e);
                latch.countDown();
            }, latch::countDown);

            assertTrue(latch.await(5, TimeUnit.SECONDS), "正常流应完成");
            assertNull(error.get());
            assertEquals("甲乙", reply.toString(), "切线程不得改变 append 顺序");
            assertTrue(!emitThreads.isEmpty(), "两个 chunk 都应触发 emit");
            for (String thread : emitThreads)
            {
                assertNotEquals("nio-sim-1", thread, "emit 必须离开上游信号线程: " + thread);
                assertTrue(thread.contains("boundedElastic"),
                        "chunk 消费应落在 boundedElastic: " + thread);
            }
        }
        finally
        {
            nioSim.dispose();
        }
    }

    @Test
    void reasoningEvents_followModelConfiguration() throws Exception
    {
        ChatModel model = mock(ChatModel.class);
        AssistantMessage message = AssistantMessage.builder()
                .content("最终回答")
                .properties(java.util.Map.of("reasoningContent", "隐藏或展示的思考"))
                .build();
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(ChatResponse.builder()
                .generations(List.of(new Generation(message)))
                .build()));

        List<String> disabledEvents = new CopyOnWriteArrayList<>();
        new AgentToolLoop().run(spec(model, new StringBuilder(), disabledEvents::add, false))
                .blockLast();
        assertTrue(disabledEvents.stream().noneMatch(e -> e.contains("\"type\":\"reasoning\"")),
                "模型管理关闭思考后，不得向前端发布 reasoning 事件");

        List<String> enabledEvents = new CopyOnWriteArrayList<>();
        new AgentToolLoop().run(spec(model, new StringBuilder(), enabledEvents::add, true))
                .blockLast();
        assertTrue(enabledEvents.stream().anyMatch(e -> e.contains("\"type\":\"reasoning\"")),
                "模型管理开启思考后，应发布上游返回的 reasoning 内容");
    }

    // ---------- helpers ----------

    private AgentToolLoopSpec spec(ChatModel model, StringBuilder reply, ChatEventSink sink)
    {
        return spec(model, reply, sink, true);
    }

    private AgentToolLoopSpec spec(ChatModel model, StringBuilder reply, ChatEventSink sink,
                                   boolean reasoningEnabled)
    {
        return new AgentToolLoopSpec(
                model, ChatOptions.builder().build(),
                List.of(new UserMessage("hi")), List.<ToolCallback>of(),
                0, mock(LlmCallCollector.class), sink, ChatTurnCallbacks.noop(),
                null, "s1", null, 1L, null, null,
                reply, new StringBuilder(), () -> { }, reasoningEnabled,
                ModelInputModalities.parse("image"));
    }

    private static ChatResponse textResponse(String text)
    {
        return ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage(text))))
                .build();
    }
}
