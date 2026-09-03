package com.ruoyi.system.tool.channel;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 客户端声明的一条渠道工具定义。
 *
 * <p>name / description / 规范化后的 JSON Schema 都是给 LLM 看的；执行体在客户端。
 */
public record ChannelToolDef(String name, String description, String inputSchemaJson)
{
    public static final Pattern NAME = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_-]{0,63}$");

    public ChannelToolDef
    {
        if (name == null || !NAME.matcher(name).matches())
        {
            throw new IllegalArgumentException("非法的渠道工具名: " + name);
        }
        if (description == null || description.isBlank())
        {
            throw new IllegalArgumentException("渠道工具 description 不能为空: " + name);
        }
        if (inputSchemaJson == null || inputSchemaJson.isBlank())
        {
            throw new IllegalArgumentException("渠道工具 schema 不能为空: " + name);
        }
        description = description.trim();
        inputSchemaJson = inputSchemaJson.trim();
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o)
        {
            return true;
        }
        if (!(o instanceof ChannelToolDef other))
        {
            return false;
        }
        return name.equals(other.name)
                && description.equals(other.description)
                && inputSchemaJson.equals(other.inputSchemaJson);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(name, description, inputSchemaJson);
    }
}
