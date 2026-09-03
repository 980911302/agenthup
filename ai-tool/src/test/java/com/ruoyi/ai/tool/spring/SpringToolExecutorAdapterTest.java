package com.ruoyi.ai.tool.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.ai.contract.core.InvocationContext;
import com.ruoyi.ai.contract.tool.ToolCall;
import com.ruoyi.ai.contract.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SpringToolExecutorAdapterTest
{
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void exposesSchemaAndReturnsImmutableContractResult()
    {
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(callback.getToolDefinition()).thenReturn(definition);
        when(definition.name()).thenReturn("demo.read");
        when(definition.description()).thenReturn("demo");
        when(definition.inputSchema()).thenReturn("{\"type\":\"object\"}");
        when(callback.call("{\"id\":1}")).thenReturn("{\"value\":2}");

        SpringToolExecutorAdapter adapter = new SpringToolExecutorAdapter(callback, false);
        ToolResult result = adapter.execute(new ToolCall("c1", "demo.read",
                mapper.createObjectNode().put("id", 1)), InvocationContext.system("t1"));

        assertEquals("demo.read", adapter.descriptor().name());
        assertFalse(adapter.descriptor().safety().confirmationRequired());
        assertEquals(com.ruoyi.ai.contract.tool.ToolSafety.RiskLevel.READ_ONLY,
                adapter.descriptor().safety().riskLevel());
        assertTrue(result.success());
        assertEquals(2, result.output().path("value").asInt());
    }
}
