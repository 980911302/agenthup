package com.ruoyi.system.ai.run;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;

import com.ruoyi.system.ai.agent.AgentContext;
import com.ruoyi.system.ai.agent.AgentContextFactory;
import com.ruoyi.system.ai.event.ChatEventSink;
import com.ruoyi.system.ai.memory.ChatMessageKind;
import com.ruoyi.system.ai.memory.ChatMessageMapperTestSupport;
import com.ruoyi.system.ai.memory.ChatMessageRecorder;
import com.ruoyi.system.ai.memory.ContextBudget;
import com.ruoyi.system.ai.memory.TokenEstimator;
import com.ruoyi.system.ai.metering.LlmCallCollector;
import com.ruoyi.system.mapper.AiLlmCallMapper;

import reactor.core.publisher.Flux;

class ChatTurnRunnerCancelFinalTest
{
    @Test
    void cancelledComplete_doesNotInsertAssistantFinal() throws Exception
    {
        ChatTurnRunner runner = new ChatTurnRunner();
        AgentContextFactory factory = mock(AgentContextFactory.class);
        AgentToolLoop loop = mock(AgentToolLoop.class);
        ChatMessageRecorder recorder = mock(ChatMessageRecorder.class);
        ChatMemory memory = mock(ChatMemory.class);
        ContextBudget budget = mock(ContextBudget.class);

        AgentContext ctx = new AgentContext(1L, "a", mock(ChatModel.class),
                ChatOptions.builder().build(), List.of(), "sys", "s1:1", 1L, null, 1000);
        // 5 参重载:第 5 位是 AgentRunOverrides(本轮模型/技能覆盖),assembleContext 走的是这条
        when(factory.buildForRun(eq(1L), eq("s1"), any(), any(), any(), any())).thenReturn(ctx);
        when(memory.get(anyString())).thenReturn(List.of());
        when(budget.inputBudget(any(), any())).thenReturn(8000);
        when(loop.applyOverflowGuard(any(), anyInt(), any())).thenAnswer(inv -> inv.getArgument(0));
        CountDownLatch done = new CountDownLatch(1);
        when(loop.run(any())).thenReturn(Flux.<org.springframework.ai.chat.model.ChatResponse>empty()
                .doOnTerminate(done::countDown));
        when(recorder.insert(anyString(), anyString(), any(), eq("USER"), any(), any(), any(),
                any(), any(), any(), any())).thenReturn(11L);

        ChatMessageMapperTestSupport.setField(runner, "agentContextFactory", factory);
        ChatMessageMapperTestSupport.setField(runner, "agentToolLoop", loop);
        ChatMessageMapperTestSupport.setField(runner, "messageRecorder", recorder);
        ChatMessageMapperTestSupport.setField(runner, "chatMemory", memory);
        ChatMessageMapperTestSupport.setField(runner, "contextBudget", budget);
        ChatMessageMapperTestSupport.setField(runner, "llmCallMapper", mock(AiLlmCallMapper.class));
        ChatMessageMapperTestSupport.setField(runner, "tokenEstimator", new TokenEstimator());

        AtomicBoolean persist = new AtomicBoolean(false);
        ChatTurnCallbacks cb = new ChatTurnCallbacks()
        {
            @Override
            public boolean shouldPersistFinalAnswer()
            {
                return persist.get();
            }

            @Override
            public void onSucceeded(String reply, LlmCallCollector.TurnUsage usage,
                                    Map<String, Object> contextUsage,
                                    Long requestMessageId, Long responseMessageId) { }

            @Override
            public void onFailed(Throwable error) { }
        };

        runner.run(new ChatTurnRequest("run-1", "s1", 1L, "hi", List.of(), null),
                ChatEventSink.noop(), cb);

        assertTrue(done.await(2, TimeUnit.SECONDS), "empty complete 应结束");
        verify(recorder, never()).insert(anyString(), anyString(), any(), eq("ASSISTANT"), any(), any(), any(),
                any(), eq(ChatMessageKind.ASSISTANT_FINAL), any(), any());
    }
}
