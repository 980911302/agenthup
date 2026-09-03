package com.ruoyi.adapter.tool;

import com.ruoyi.ai.tool.spring.SpringToolExecutorAdapter;
import com.ruoyi.ai.contract.tool.ToolExecutor;
import com.ruoyi.system.tool.ToolCallbackRegistry;
import com.ruoyi.system.tool.ToolPolicyService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 为内置工具与 MCP 工具提供统一的稳定契约视图。 */
@Component
public class ToolContractRegistry
{
    private final ToolCallbackRegistry callbacks;
    private final ToolPolicyService policies;

    public ToolContractRegistry(ToolCallbackRegistry callbacks, ToolPolicyService policies)
    {
        this.callbacks = callbacks;
        this.policies = policies;
    }

    public Map<String, ToolExecutor> all()
    {
        Map<String, ToolExecutor> result = new LinkedHashMap<>();
        callbacks.all().forEach((name, callback) -> result.put(name,
                new SpringToolExecutorAdapter(callback, policies.requireConfirm(name))));
        return Map.copyOf(result);
    }

    public ToolExecutor get(String name)
    {
        var callback = callbacks.get(name);
        return callback == null ? null
                : new SpringToolExecutorAdapter(callback, policies.requireConfirm(name));
    }
}
