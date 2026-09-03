package com.ruoyi.system.tool;

/**
 * {@link ToolCallbackRegistry#refresh()} 完成后发布。
 * 监听方(如上下文计量缓存)据此失效按 toolCode 缓存的定义 token 数。
 */
public record ToolRegistryRefreshedEvent()
{
}
