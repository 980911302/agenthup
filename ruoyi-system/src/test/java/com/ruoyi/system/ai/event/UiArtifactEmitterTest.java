package com.ruoyi.system.ai.event;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.system.tool.UiArtifact;
import com.ruoyi.system.tool.UiArtifactNames;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiArtifactEmitterTest
{
    @Test
    void nonToolContextEmitsUniqueEventIds()
    {
        List<String> events = new ArrayList<>();
        UiArtifactEmitter emitter = new UiArtifactEmitter();
        UiArtifactContext ctx = new UiArtifactContext(
                events::add, "s1", "run-9", 11L, 2L, "owner-a", null);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("promptTokens", 10);
        UiArtifact artifact = new UiArtifact(UiArtifactNames.RUN_TOKEN_USAGE, 1, payload);

        emitter.emit(ctx, artifact);
        emitter.emit(ctx, artifact);

        assertEquals(2, events.size());
        JSONObject first = JSON.parseObject(events.get(0));
        JSONObject second = JSON.parseObject(events.get(1));
        assertEquals("ui", first.getString("type"));
        assertEquals(UiArtifactNames.RUN_TOKEN_USAGE, first.getString("name"));
        assertTrue(first.getString("eventId").startsWith("run-9:" + UiArtifactNames.RUN_TOKEN_USAGE + ":"));
        assertNotEquals(first.getString("eventId"), second.getString("eventId"));
        assertEquals(10, first.getJSONObject("payload").getIntValue("promptTokens"));
    }

    @Test
    void globalScopeThrowsUnsupportedOperationException()
    {
        UiArtifactEmitter emitter = new UiArtifactEmitter()
        {
            @Override
            UiArtifactNames.Spec lookup(String name)
            {
                return new UiArtifactNames.Spec(name, 1, 1024,
                        UiArtifactNames.MergePolicy.REPLACE,
                        UiArtifactNames.Persistence.NONE,
                        UiArtifactNames.Scope.GLOBAL, 0);
            }
        };
        UiArtifactContext ctx = new UiArtifactContext(
                json -> { }, "s1", "run-1", null, 1L, "owner", null);

        assertThrows(UnsupportedOperationException.class,
                () -> emitter.emit(ctx, new UiArtifact("broadcast.any", 1, Map.of("ok", true))));
    }
}
