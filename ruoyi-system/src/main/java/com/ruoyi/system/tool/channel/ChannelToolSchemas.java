package com.ruoyi.system.tool.channel;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 渠道工具清单的解析、规范化与序列化。纯函数，不碰 Spring。
 *
 * <p>按 name 字典序排序、schema key 字典序重写，保证写入会话行的字节稳定，
 * 避免 tools 数组尾巴每轮抖动。
 */
public final class ChannelToolSchemas
{
    public static final int DEFAULT_MAX_TOOLS = 32;
    public static final int MAX_SCHEMA_BYTES = 8 * 1024;
    public static final int MAX_TOOLS_JSON_BYTES = 64 * 1024;
    public static final String DEFAULT_SCHEMA = "{\"properties\":{},\"type\":\"object\"}";

    private ChannelToolSchemas() { }

    /**
     * 解析客户端提交的工具数组。非法项写入 {@code skippedOut} 并丢弃。
     * 输出按 name 字典序、同名只留第一条。
     */
    public static List<ChannelToolDef> parseAndNormalize(String json, List<String> skippedOut)
    {
        return parseAndNormalize(json, skippedOut, DEFAULT_MAX_TOOLS);
    }

    public static List<ChannelToolDef> parseAndNormalize(String json, List<String> skippedOut, int maxTools)
    {
        List<String> skipped = skippedOut != null ? skippedOut : new ArrayList<>();
        int cap = maxTools > 0 ? maxTools : DEFAULT_MAX_TOOLS;
        if (json == null || json.isBlank())
        {
            return List.of();
        }
        Object parsed;
        try
        {
            parsed = JSON.parse(json);
        }
        catch (RuntimeException e)
        {
            skipped.add("*");
            return List.of();
        }
        if (!(parsed instanceof JSONArray array))
        {
            skipped.add("*");
            return List.of();
        }

        List<ChannelToolDef> acc = new ArrayList<>();
        for (int i = 0; i < array.size(); i++)
        {
            Object raw = array.get(i);
            if (!(raw instanceof JSONObject item))
            {
                skipped.add("[" + i + "]");
                continue;
            }
            String name = item.getString("name");
            if (name == null || !ChannelToolDef.NAME.matcher(name).matches())
            {
                skipped.add(name != null ? name : "[" + i + "]");
                continue;
            }
            String description = item.getString("description");
            if (description == null || description.isBlank())
            {
                skipped.add(name);
                continue;
            }
            if (acc.stream().anyMatch(d -> d.name().equals(name)))
            {
                skipped.add(name);
                continue;
            }
            String schema;
            try
            {
                schema = canonicalize(item.containsKey("parameters") ? item.get("parameters") : null);
            }
            catch (RuntimeException e)
            {
                skipped.add(name);
                continue;
            }
            if (schema.getBytes(StandardCharsets.UTF_8).length > MAX_SCHEMA_BYTES)
            {
                skipped.add(name);
                continue;
            }
            if (acc.size() >= cap)
            {
                skipped.add(name);
                continue;
            }
            acc.add(new ChannelToolDef(name, description.trim(), schema));
        }
        acc.sort(Comparator.comparing(ChannelToolDef::name));
        String serialized = serialize(acc);
        if (serialized.getBytes(StandardCharsets.UTF_8).length > MAX_TOOLS_JSON_BYTES)
        {
            skipped.add("*");
            return List.of();
        }
        return List.copyOf(acc);
    }

    /** 装配期读会话行：坏数据当没有，不要炸 run。 */
    public static List<ChannelToolDef> parseQuietly(String json)
    {
        if (json == null || json.isBlank())
        {
            return List.of();
        }
        try
        {
            return parseAndNormalize(json, new ArrayList<>());
        }
        catch (RuntimeException e)
        {
            return List.of();
        }
    }

    /**
     * JSON Schema 按 key 字典序重新序列化。非对象时回退到空 object schema。
     */
    public static String canonicalize(Object rawParameters)
    {
        // 顶层可能是 JSON 字符串(客户端把 schema 当字符串传),这里解一次。
        // 解析只能发生在顶层:schema 内部的字符串是 "string"/"integer"/required 字段名
        // 这类字面量,拿去 JSON.parse 会抛异常并被当成坏数据替换掉,整份 schema 就废了。
        Object root = rawParameters;
        if (root instanceof String s)
        {
            if (s.isBlank())
            {
                return DEFAULT_SCHEMA;
            }
            try
            {
                root = JSON.parse(s);
            }
            catch (RuntimeException e)
            {
                return DEFAULT_SCHEMA;
            }
        }
        Object sorted = sortKeys(root);
        if (!(sorted instanceof Map<?, ?> map) || map.isEmpty())
        {
            return DEFAULT_SCHEMA;
        }
        if (!map.containsKey("type"))
        {
            Map<String, Object> withType = new TreeMap<>();
            for (Map.Entry<?, ?> e : map.entrySet())
            {
                if (e.getKey() != null)
                {
                    withType.put(String.valueOf(e.getKey()), e.getValue());
                }
            }
            withType.putIfAbsent("type", "object");
            withType.putIfAbsent("properties", new TreeMap<>());
            sorted = withType;
        }
        return JSON.toJSONString(sorted, JSONWriter.Feature.MapSortField);
    }

    public static String serialize(List<ChannelToolDef> defs)
    {
        JSONArray array = new JSONArray();
        if (defs != null)
        {
            for (ChannelToolDef def : defs)
            {
                JSONObject o = new JSONObject();
                o.put("name", def.name());
                o.put("description", def.description());
                o.put("parameters", JSON.parse(def.inputSchemaJson()));
                array.add(o);
            }
        }
        return array.toJSONString();
    }

    private static Object sortKeys(Object raw)
    {
        if (raw == null)
        {
            return defaultSchemaMap();
        }
        // 字符串原样保留:schema 里的字符串是 "string"/"object"/required 字段名/description,
        // 都不是嵌套 JSON。顶层字符串已在 canonicalize 里解过。
        if (raw instanceof String s)
        {
            return s;
        }
        if (raw instanceof JSONObject obj)
        {
            Map<String, Object> sorted = new TreeMap<>();
            for (String key : obj.keySet())
            {
                sorted.put(key, sortKeys(obj.get(key)));
            }
            return sorted;
        }
        if (raw instanceof Map<?, ?> map)
        {
            Map<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> e : map.entrySet())
            {
                if (e.getKey() == null)
                {
                    continue;
                }
                sorted.put(String.valueOf(e.getKey()), sortKeys(e.getValue()));
            }
            return sorted;
        }
        if (raw instanceof JSONArray arr)
        {
            List<Object> list = new ArrayList<>(arr.size());
            for (Object o : arr)
            {
                list.add(sortKeys(o));
            }
            return list;
        }
        if (raw instanceof List<?> list)
        {
            List<Object> out = new ArrayList<>(list.size());
            for (Object o : list)
            {
                out.add(sortKeys(o));
            }
            return out;
        }
        if (raw instanceof JSONObject || raw instanceof Map)
        {
            return raw;
        }
        // 标量 / 非法 schema：回退
        if (!(raw instanceof Map) && !(raw instanceof List) && !(raw instanceof JSONArray)
                && !(raw instanceof JSONObject))
        {
            if (raw instanceof Number || raw instanceof Boolean || raw instanceof String)
            {
                return raw;
            }
            return defaultSchemaMap();
        }
        return raw;
    }

    private static Map<String, Object> defaultSchemaMap()
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("properties", new TreeMap<>());
        m.put("type", "object");
        return m;
    }
}
