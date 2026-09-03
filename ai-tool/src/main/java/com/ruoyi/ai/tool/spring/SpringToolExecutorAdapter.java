package com.ruoyi.ai.tool.spring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.ai.contract.core.ContractError;
import com.ruoyi.ai.contract.core.ErrorCode;
import com.ruoyi.ai.contract.core.InvocationContext;
import com.ruoyi.ai.contract.core.Usage;
import com.ruoyi.ai.contract.tool.ToolCall;
import com.ruoyi.ai.contract.tool.ToolDescriptor;
import com.ruoyi.ai.contract.tool.ToolError;
import com.ruoyi.ai.contract.tool.ToolExecutor;
import com.ruoyi.ai.contract.tool.ToolResult;
import com.ruoyi.ai.contract.tool.ToolSafety;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.ai.tool.ToolCallback;

/** Spring AI ToolCallback 到无框架 ToolExecutor 的双向边界适配器。 */
public final class SpringToolExecutorAdapter implements ToolExecutor
{
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ToolCallback callback;
    private final ToolDescriptor descriptor;

    public SpringToolExecutorAdapter(ToolCallback callback, boolean confirmationRequired)
    {
        this.callback = callback;
        var definition = callback.getToolDefinition();
        ToolSafety.RiskLevel riskLevel = riskLevel(definition.name(), confirmationRequired);
        this.descriptor = new ToolDescriptor(definition.name(), "1", definition.description(),
                parse(definition.inputSchema()), null,
                new ToolSafety(riskLevel, confirmationRequired), Set.of("spring-ai"));
    }

    @Override
    public ToolDescriptor descriptor()
    {
        return descriptor;
    }

    @Override
    public ToolResult execute(ToolCall call, InvocationContext context)
    {
        try
        {
            String args = call.arguments() == null ? "{}" : call.arguments().toString();
            String result = callback.call(args);
            JsonNode output = parseValue(result);
            return new ToolResult(true, output, List.of(), List.of(), null, Usage.EMPTY);
        }
        catch (Exception e)
        {
            ContractError error = new ContractError(ErrorCode.INTERNAL,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(),
                    false, Map.of("tool", descriptor.name()));
            return new ToolResult(false, null, List.of(), List.of(), new ToolError(error), Usage.EMPTY);
        }
    }

    private static JsonNode parse(String value)
    {
        try { return value == null ? null : MAPPER.readTree(value); }
        catch (Exception ignored) { return null; }
    }

    private static JsonNode parseValue(String value)
    {
        JsonNode json = parse(value);
        return json != null ? json : MAPPER.getNodeFactory().textNode(value == null ? "" : value);
    }

    /** 未知工具按可变更处理，只有明确的只读命名才降为 READ_ONLY。 */
    private static ToolSafety.RiskLevel riskLevel(String name, boolean confirmationRequired)
    {
        String value = name == null ? "" : name.toLowerCase();
        String action = value.substring(value.lastIndexOf('.') + 1);
        if (action.contains("delete") || action.contains("remove") || action.contains("drop"))
            return ToolSafety.RiskLevel.DESTRUCTIVE;
        if (!confirmationRequired && (action.startsWith("get") || action.startsWith("list")
                || action.startsWith("query") || action.startsWith("search")
                || Set.of("read", "grep", "find", "ls").contains(action)))
            return ToolSafety.RiskLevel.READ_ONLY;
        return ToolSafety.RiskLevel.MUTATING;
    }
}
