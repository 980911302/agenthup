package com.ruoyi.system.kb.graph.extract;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.kb.graph.GraphEntity;
import com.ruoyi.system.kb.graph.GraphRelation;
import com.ruoyi.system.kb.graph.extract.ExtractResultParser.ExtractResult;
import com.ruoyi.system.kb.graph.provenance.GraphExtractOutcome;

/**
 * 抽取结果 schema 校验与保守修复。
 */
public final class ExtractResultValidator
{
    private ExtractResultValidator()
    {
    }

    /**
     * 校验并可能补全缺失端点实体；设置 outcome。
     */
    public static ExtractResult validate(ExtractResult input, GraphExtractionProfile profile)
    {
        ExtractResult r = input != null ? input : new ExtractResult();
        if (r.getOutcome() != null && GraphExtractOutcome.PARSE_FAILED.equals(r.getOutcome()))
        {
            return r;
        }
        if (r.getOutcome() != null && GraphExtractOutcome.LLM_FAILED.equals(r.getOutcome()))
        {
            return r;
        }

        GraphExtractionProfile p = profile != null ? profile : GraphExtractionProfile.defaults();

        // 裁剪上限
        if (r.getEntities().size() > p.getMaxEntitiesPerUnit())
        {
            r.setEntities(r.getEntities().subList(0, p.getMaxEntitiesPerUnit()));
        }
        if (r.getRelations().size() > p.getMaxRelationsPerUnit())
        {
            r.setRelations(r.getRelations().subList(0, p.getMaxRelationsPerUnit()));
        }

        // 去空名
        r.getEntities().removeIf(e -> e == null || StringUtils.isEmpty(e.getName()));
        r.getRelations().removeIf(rel -> rel == null
            || StringUtils.isEmpty(rel.getSourceName())
            || StringUtils.isEmpty(rel.getTargetName()));

        Set<String> names = new HashSet<>();
        for (GraphEntity e : r.getEntities())
        {
            names.add(norm(e.getName()));
        }

        int repaired = 0;
        Iterator<GraphRelation> it = r.getRelations().iterator();
        while (it.hasNext())
        {
            GraphRelation rel = it.next();
            String s = norm(rel.getSourceName());
            String t = norm(rel.getTargetName());
            if (s.equals(t))
            {
                it.remove();
                continue;
            }
            if (!names.contains(s))
            {
                GraphEntity e = new GraphEntity();
                e.setName(rel.getSourceName().trim());
                e.setType("UNKNOWN");
                e.setDescription("自关系端点补全");
                r.getEntities().add(e);
                names.add(s);
                repaired++;
            }
            if (!names.contains(t))
            {
                GraphEntity e = new GraphEntity();
                e.setName(rel.getTargetName().trim());
                e.setType("UNKNOWN");
                e.setDescription("自关系端点补全");
                r.getEntities().add(e);
                names.add(t);
                repaired++;
            }
        }
        r.setRepairedEndpoints(repaired);

        // 允许类型过滤（仅在 profile 配置了白名单时）
        if (!p.getAllowedEntityTypes().isEmpty())
        {
            Set<String> allow = new HashSet<>();
            for (String t : p.getAllowedEntityTypes())
            {
                if (t != null)
                {
                    allow.add(t.trim().toLowerCase(Locale.ROOT));
                }
            }
            // 不硬删：类型不在白名单时打标，避免合法实体被误杀
            for (GraphEntity e : r.getEntities())
            {
                if (StringUtils.isEmpty(e.getType()))
                {
                    continue;
                }
                if (!allow.contains(e.getType().trim().toLowerCase(Locale.ROOT))
                    && !"unknown".equalsIgnoreCase(e.getType()))
                {
                    // 保留实体，类型降为 OTHER
                    e.setType("OTHER");
                }
            }
        }

        // 最终端点完整性
        names.clear();
        for (GraphEntity e : r.getEntities())
        {
            names.add(norm(e.getName()));
        }
        boolean dangling = false;
        for (GraphRelation rel : r.getRelations())
        {
            if (!names.contains(norm(rel.getSourceName())) || !names.contains(norm(rel.getTargetName())))
            {
                dangling = true;
                break;
            }
        }
        if (dangling)
        {
            r.setOutcome(GraphExtractOutcome.VALIDATION_FAILED);
            r.setErrorDetail("存在无法解析的关系端点");
            return r;
        }

        if (r.getEntities().isEmpty() && r.getRelations().isEmpty())
        {
            r.setOutcome(GraphExtractOutcome.VALID_EMPTY);
        }
        else
        {
            r.setOutcome(GraphExtractOutcome.SUCCESS);
        }
        return r;
    }

    private static String norm(String name)
    {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }
}
