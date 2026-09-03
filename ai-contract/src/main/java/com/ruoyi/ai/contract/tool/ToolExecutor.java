package com.ruoyi.ai.contract.tool;

import com.ruoyi.ai.contract.core.InvocationContext;

public interface ToolExecutor
{
    ToolDescriptor descriptor();

    ToolResult execute(ToolCall call, InvocationContext context);
}
