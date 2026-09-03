package com.ruoyi.system.kb;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.ruoyi.system.kb.graph.GraphEntity;
import com.ruoyi.system.kb.graph.GraphRelation;

/**
 * 探索子图产品 DTO 装配（隐藏 entityKey / Neo4j 细节）。
 */
public final class KbGraphExploreAssembler
{
    public static final int DEFAULT_NODE_LIMIT = 60;
    public static final int MAX_NODE_LIMIT = 120;
    public static final int DEFAULT_EDGE_LIMIT = 150;
    public static final int MAX_EDGE_LIMIT = 300;

    private KbGraphExploreAssembler() {}

    public static int clampNodes(Integer limit)
    {
        if (limit == null || limit <= 0)
        {
            return DEFAULT_NODE_LIMIT;
        }
        return Math.min(MAX_NODE_LIMIT, Math.max(5, limit));
    }

    public static int clampEdges(Integer limit)
    {
        if (limit == null || limit <= 0)
        {
            return DEFAULT_EDGE_LIMIT;
        }
        return Math.min(MAX_EDGE_LIMIT, Math.max(5, limit));
    }

    public static int clampDepth(Integer depth)
    {
        if (depth == null || depth < 1)
        {
            return 1;
        }
        return Math.min(2, depth);
    }

    public static Map<String, Object> productNode(GraphEntity e)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        if (e == null)
        {
            return m;
        }
        // id 必须与关系 source/target 一致，使用图存储主键 name（小写稳定键）
        // 展示名用 canonicalName，否则连线会对不上、前端看起来像「空图/只有孤立点」
        String graphName = e.getName() == null ? "" : e.getName();
        String display = e.getCanonicalName() != null && !e.getCanonicalName().isBlank()
            ? e.getCanonicalName() : graphName;
        m.put("id", graphName);
        m.put("name", display);
        m.put("type", e.getType() == null || e.getType().isBlank() ? "CONCEPT" : e.getType());
        m.put("description", e.getDescription() == null ? "" : e.getDescription());
        m.put("sourceCount", e.getSourceIds() == null ? 0 : e.getSourceIds().size());
        // 不暴露 entityKey / candidateKey
        m.put("category", categoryOf(e.getType()));
        return m;
    }

    public static Map<String, Object> productEdge(GraphRelation r, int index)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        if (r == null)
        {
            return m;
        }
        String label = firstNonBlank(r.getKeywords(), r.getPredicate(), "相关");
        m.put("id", "e" + index + ":" + r.getSourceName() + "->" + r.getTargetName());
        m.put("source", r.getSourceName());
        m.put("target", r.getTargetName());
        m.put("label", label);
        m.put("description", r.getDescription() == null ? "" : r.getDescription());
        m.put("sourceCount", r.getSourceIds() == null ? 0 : r.getSourceIds().size());
        m.put("weight", r.getWeight());
        return m;
    }

    public static List<GraphEntity> pickSeeds(List<GraphEntity> candidates, int maxSeeds)
    {
        if (candidates == null || candidates.isEmpty())
        {
            return List.of();
        }
        List<GraphEntity> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator
            .comparingInt((GraphEntity e) -> e.getSourceIds() == null ? 0 : e.getSourceIds().size())
            .reversed());
        if (sorted.size() > maxSeeds)
        {
            return new ArrayList<>(sorted.subList(0, maxSeeds));
        }
        return sorted;
    }

    public static boolean typeAllowed(GraphEntity e, Set<String> types)
    {
        if (types == null || types.isEmpty())
        {
            return true;
        }
        if (e == null)
        {
            return false;
        }
        String t = e.getType() == null ? "" : e.getType().trim().toUpperCase();
        for (String want : types)
        {
            if (want != null && t.equalsIgnoreCase(want.trim()))
            {
                return true;
            }
        }
        return false;
    }

    public static boolean relationAllowed(GraphRelation r, Set<String> preds)
    {
        if (preds == null || preds.isEmpty())
        {
            return true;
        }
        if (r == null)
        {
            return false;
        }
        String p = firstNonBlank(r.getPredicate(), r.getKeywords(), "").toLowerCase();
        for (String want : preds)
        {
            if (want != null && p.contains(want.trim().toLowerCase()))
            {
                return true;
            }
        }
        return false;
    }

    public static String categoryOf(String type)
    {
        if (type == null || type.isBlank())
        {
            return "other";
        }
        String t = type.toUpperCase();
        // 兼容中文 type（抽取 prompt 默认）与英文大写
        if (t.contains("PERSON") || t.contains("人") || t.contains("角色")) return "person";
        if (t.contains("ORG") || t.contains("组织") || t.contains("公司") || t.contains("机构") || t.contains("部门")) return "org";
        if (t.contains("LOC") || t.contains("地点") || t.contains("地址") || t.contains("城市") || t.contains("园区")) return "loc";
        if (t.contains("DOC") || t.contains("文件") || t.contains("文档") || t.contains("合同") || t.contains("报告")) return "doc";
        if (t.contains("EVENT") || t.contains("事件") || t.contains("会议") || t.contains("活动")) return "event";
        if (t.contains("METHOD") || t.contains("方法") || t.contains("流程") || t.contains("算法")) return "concept";
        if (t.contains("OTHER") || t.contains("其他") || t.contains("其它")) return "other";
        return "concept";
    }

    private static String firstNonBlank(String... parts)
    {
        if (parts == null)
        {
            return "";
        }
        for (String p : parts)
        {
            if (p != null && !p.isBlank())
            {
                return p.trim();
            }
        }
        return "";
    }
}
