package com.ruoyi.system.ai.metering;

import java.util.ArrayList;
import java.util.List;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.system.ai.event.UiArtifactContext;
import com.ruoyi.system.ai.event.UiArtifactEmitter;
import com.ruoyi.system.mapper.AiLlmCallMapper;
import com.ruoyi.system.tool.UiArtifactNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class LlmCallCollectorEmitTest
{
    private LlmCallCollector collector;

    @BeforeEach
    void setUp()
    {
        collector = new LlmCallCollector("s1", 10L, "s1:10", 100L, "qwen-max", 0,
                mock(AiLlmCallMapper.class), null);
    }

    @Test
    void withoutEmitterExistingAccountingUnchanged()
    {
        collector.onResponse(responseWithUsage(100, 20, 120, "tool_calls"));
        collector.onResponse(responseWithUsage(200, 30, 230, "stop"));
        LlmCallCollector.TurnUsage turn = collector.onComplete("hi", "hello");
        assertEquals(200, turn.promptTokens());
        assertEquals(30, turn.completionTokens());
        assertEquals(230, turn.totalTokens());
        assertEquals(2, turn.callCount());
    }

    @Test
    void emitUsesDeltaTotalsMatchingTurnUsage()
    {
        List<String> events = new ArrayList<>();
        collector.setUiEmitter(new UiArtifactEmitter(), ctx(events));
        collector.onResponse(responseWithUsage(100, 20, 120, "tool_calls"));
        collector.onResponse(responseWithUsage(200, 30, 230, "stop"));
        LlmCallCollector.TurnUsage turn = collector.onComplete("hi", "hello");

        assertTrue(events.size() >= 1);
        JSONObject last = lastUi(events);
        assertEquals(UiArtifactNames.RUN_TOKEN_USAGE, last.getString("name"));
        JSONObject payload = last.getJSONObject("payload");
        assertEquals(turn.promptTokens(), payload.getIntValue("promptTokens"));
        assertEquals(turn.completionTokens(), payload.getIntValue("completionTokens"));
        assertEquals(turn.totalTokens(), payload.getIntValue("totalTokens"));
        assertEquals(turn.callCount(), payload.getIntValue("callCount"));
    }

    @Test
    void throttleDropsIntermediateEmitsWithin500msThenOnCompleteForces()
    {
        List<String> events = new ArrayList<>();
        collector.setUiEmitter(new UiArtifactEmitter(), ctx(events));
        collector.onResponse(responseWithUsage(100, 20, 120, "tool_calls"));
        collector.onResponse(responseWithUsage(200, 30, 230, "tool_calls"));
        collector.onResponse(responseWithUsage(300, 40, 340, "stop"));
        int mid = events.size();
        assertEquals(1, mid, "500ms 内多次 flush 只推一次");

        LlmCallCollector.TurnUsage turn = collector.onComplete("p", "r");
        assertEquals(2, events.size(), "onComplete 无视节流强制推末值");
        JSONObject last = lastUi(events);
        assertEquals(turn.totalTokens(), last.getJSONObject("payload").getIntValue("totalTokens"));
        assertEquals(turn.callCount(), last.getJSONObject("payload").getIntValue("callCount"));
    }

    private static UiArtifactContext ctx(List<String> events)
    {
        return new UiArtifactContext(events::add, "s1", "run-1", 11L, 10L, "owner", null);
    }

    private static JSONObject lastUi(List<String> events)
    {
        return JSON.parseObject(events.get(events.size() - 1));
    }

    private static ChatResponse responseWithUsage(int prompt, int completion, int total, String finishReason)
    {
        DefaultUsage usage = new DefaultUsage(prompt, completion, total);
        ChatResponseMetadata meta = ChatResponseMetadata.builder()
                .model("qwen-max")
                .usage(usage)
                .build();
        ChatGenerationMetadata genMeta = ChatGenerationMetadata.builder().finishReason(finishReason).build();
        Generation gen = new Generation(new AssistantMessage(""), genMeta);
        return new ChatResponse(List.of(gen), meta);
    }
}
