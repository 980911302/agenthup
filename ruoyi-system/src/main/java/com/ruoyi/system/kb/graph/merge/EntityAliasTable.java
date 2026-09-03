package com.ruoyi.system.kb.graph.merge;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 层 2：别名表（原名/简称/英文名 → 规范展示名）。
 * <p>保守内置常见技术别名；知识库级扩展可后续挂配置。
 */
public final class EntityAliasTable
{
    private static final Map<String, String> BUILTIN;

    static
    {
        Map<String, String> m = new LinkedHashMap<>();
        put(m, "pg", "PostgreSQL");
        put(m, "postgres", "PostgreSQL");
        put(m, "postgresql", "PostgreSQL");
        put(m, "pgvector", "pgvector");
        put(m, "neo4j", "Neo4j");
        put(m, "llm", "大语言模型");
        put(m, "大模型", "大语言模型");
        put(m, "rag", "RAG");
        put(m, "graphrag", "GraphRAG");
        put(m, "kb", "知识库");
        put(m, "知识库系统", "知识库");
        BUILTIN = Collections.unmodifiableMap(m);
    }

    private EntityAliasTable()
    {
    }

    private static void put(Map<String, String> m, String alias, String canonical)
    {
        m.put(EntityNormalizer.normalizeName(alias), canonical);
    }

    /**
     * @return 规范展示名；无别名时返回原始 trim
     */
    public static String resolveDisplayName(String raw)
    {
        if (raw == null)
        {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty())
        {
            return "";
        }
        String key = EntityNormalizer.normalizeName(trimmed);
        String canon = BUILTIN.get(key);
        return canon != null ? canon : trimmed;
    }

    /** 归一化键空间下的别名解析（用于 candidateKey） */
    public static String resolveNormalizedKey(String raw)
    {
        String display = resolveDisplayName(raw);
        return EntityNormalizer.normalizeName(display);
    }

    public static Map<String, String> builtinAliases()
    {
        return BUILTIN;
    }
}
