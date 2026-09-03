package com.ruoyi.system.ai;

/**
 * 工具调用记录
 * <p>
 * 一次工具调用 = name + args + result,用于审计、前端展示、调试回放。
 * 不参与 LLM 拼 prompt(LLM 那边走 Spring AI 的 ToolCall 机制,这里是留痕)。
 *
 * @author ruoyi
 */
public class ToolCall
{
    /** 工具名称(如 search、executeCode、或 MCP 工具名) */
    private String name;

    /** 调用参数(JSON 字符串,原样保留) */
    private String args;

    /** 执行结果(文本,超长可截断) */
    private String result;

    /** 调用时间(ISO 格式字符串) */
    private String timestamp;

    /** 工具来源:builtin(内置 @Tool) / mcp(MCP 工具) */
    private String source;

    public ToolCall() {}

    public ToolCall(String name, String args, String result, String timestamp, String source)
    {
        this.name = name;
        this.args = args;
        this.result = result;
        this.timestamp = timestamp;
        this.source = source;
    }

    public String getName()
    {
        return name;
    }

    public void setName(String name)
    {
        this.name = name;
    }

    public String getArgs()
    {
        return args;
    }

    public void setArgs(String args)
    {
        this.args = args;
    }

    public String getResult()
    {
        return result;
    }

    public void setResult(String result)
    {
        this.result = result;
    }

    public String getTimestamp()
    {
        return timestamp;
    }

    public void setTimestamp(String timestamp)
    {
        this.timestamp = timestamp;
    }

    public String getSource()
    {
        return source;
    }

    public void setSource(String source)
    {
        this.source = source;
    }
}
