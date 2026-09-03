package com.ruoyi.system.kb.graph.merge;

import java.util.Locale;
import java.util.Set;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.kb.graph.GraphRelation;

/**
 * 关系键与对称/有向规范化。
 */
public final class RelationCanonicalizer
{
    /** 对称谓词：端点按字典序统一，避免 A-B / B-A 双写 */
    private static final Set<String> SYMMETRIC = Set.of(
        "related", "related_to", "关联", "相关", "合作", "协作", "相似", "等同", "等价",
        "similar", "similar_to", "equivalent", "partner", "works_with", "与...相关"
    );

    private RelationCanonicalizer()
    {
    }

    public static boolean isSymmetric(String predicate)
    {
        String p = EntityNormalizer.normalizePredicate(predicate);
        return SYMMETRIC.contains(p);
    }

    public static String relationKey(Long kbId, String sourceName, String predicate, String targetName)
    {
        String sk = EntityAliasTable.resolveNormalizedKey(sourceName);
        String tk = EntityAliasTable.resolveNormalizedKey(targetName);
        String pred = EntityNormalizer.normalizePredicate(predicate);
        if (isSymmetric(pred) && sk.compareTo(tk) > 0)
        {
            String tmp = sk;
            sk = tk;
            tk = tmp;
        }
        return (kbId == null ? "0" : kbId) + "|" + sk + "|" + pred + "|" + tk;
    }

    /**
     * 就地规范化：对称端点排序、填 relationKey、规范 predicate。
     */
    public static void applyTo(GraphRelation r, Long kbId)
    {
        if (r == null)
        {
            return;
        }
        String pred = EntityNormalizer.normalizePredicate(r.getKeywords());
        String s = r.getSourceName() != null ? r.getSourceName().trim() : "";
        String t = r.getTargetName() != null ? r.getTargetName().trim() : "";
        if (isSymmetric(pred))
        {
            String sn = EntityAliasTable.resolveNormalizedKey(s);
            String tn = EntityAliasTable.resolveNormalizedKey(t);
            if (sn.compareTo(tn) > 0)
            {
                String tmp = s;
                s = t;
                t = tmp;
            }
        }
        // 展示名走别名表
        s = EntityAliasTable.resolveDisplayName(s);
        t = EntityAliasTable.resolveDisplayName(t);
        r.setSourceName(s);
        r.setTargetName(t);
        r.setKeywords(StringUtils.isEmpty(r.getKeywords()) ? pred : r.getKeywords().trim());
        r.setPredicate(pred);
        r.setRelationKey(relationKey(kbId, s, pred, t));
    }

    /**
     * 权重：独立证据数（distinct sourceIds），而非字符串重复。
     */
    public static double weightFromEvidence(int distinctEvidenceCount, int distinctDocHint)
    {
        int e = Math.max(0, distinctEvidenceCount);
        int d = Math.max(0, distinctDocHint);
        // 证据主导，文档多样性轻加成
        return 1.0 + e + 0.25 * d;
    }
}
