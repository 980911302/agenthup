package com.ruoyi.system.kb.policy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

/**
 * 知识引擎策略 payload 工具（纯函数）。
 */
public final class KbIndexPolicyPayload
{
    private KbIndexPolicyPayload() {}

    public static Map<String, Object> of(String embeddingModel, String extractModel,
                                         String chunkStrategy, Integer chunkSize,
                                         Integer chunkOverlap, String graphEnabled)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("embeddingModel", embeddingModel == null ? "" : embeddingModel);
        m.put("extractModel", extractModel == null ? "" : extractModel);
        m.put("chunkStrategy", chunkStrategy == null || chunkStrategy.isBlank() ? "P" : chunkStrategy);
        m.put("chunkSize", chunkSize == null ? 800 : chunkSize);
        m.put("chunkOverlap", chunkOverlap == null ? 100 : chunkOverlap);
        m.put("graphEnabled", "1".equals(graphEnabled) ? "1" : "0");
        return m;
    }

    public static String toJson(Map<String, Object> payload)
    {
        return JSON.toJSONString(payload == null ? Map.of() : payload);
    }

    public static Map<String, Object> fromJson(String json)
    {
        if (json == null || json.isBlank())
        {
            return of("", "", "P", 800, 100, "0");
        }
        JSONObject o = JSON.parseObject(json);
        return of(
            o.getString("embeddingModel"),
            o.getString("extractModel"),
            o.getString("chunkStrategy"),
            o.getInteger("chunkSize"),
            o.getInteger("chunkOverlap"),
            o.getString("graphEnabled"));
    }

    public static String fingerprint(Map<String, Object> payload)
    {
        String raw = toJson(payload);
        try
        {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(dig).substring(0, 32);
        }
        catch (Exception e)
        {
            return Integer.toHexString(raw.hashCode());
        }
    }

    public static String embedding(Map<String, Object> p)
    {
        return str(p, "embeddingModel");
    }

    public static String extract(Map<String, Object> p)
    {
        return str(p, "extractModel");
    }

    public static String strategy(Map<String, Object> p)
    {
        String s = str(p, "chunkStrategy");
        return s.isEmpty() ? "P" : s;
    }

    public static int size(Map<String, Object> p)
    {
        Object v = p == null ? null : p.get("chunkSize");
        if (v instanceof Number n) return n.intValue();
        return 800;
    }

    public static int overlap(Map<String, Object> p)
    {
        Object v = p == null ? null : p.get("chunkOverlap");
        if (v instanceof Number n) return n.intValue();
        return 100;
    }

    public static String graph(Map<String, Object> p)
    {
        return "1".equals(str(p, "graphEnabled")) ? "1" : "0";
    }

    private static String str(Map<String, Object> p, String k)
    {
        if (p == null || p.get(k) == null) return "";
        return String.valueOf(p.get(k)).trim();
    }
}
