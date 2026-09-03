package com.ruoyi.system.kb.graph.community;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import com.ruoyi.system.kb.chunker.TokenCounter;
import com.ruoyi.system.kb.graph.GraphEntity;
import com.ruoyi.system.kb.graph.GraphRelation;

/**
 * 无 LLM 的可复现社区报告（合约测 / 降级）。自底向上 map 材料 → 模板摘要。
 */
public final class ExtractiveCommunityReportBuilder
{
    public static final String MODEL_CODE = "extractive";
    public static final String PROMPT_VERSION = CommunityReportPrompt.PROMPT_VERSION;

    private ExtractiveCommunityReportBuilder() {}

    public static CommunityReportDraft build(
        Long kbId, String graphVersion, int level, long communityId,
        List<GraphEntity> entities, List<GraphRelation> relations,
        List<CommunityReportDraft> childReports, List<EvidenceSnippet> evidences)
    {
        CommunityReportDraft draft = new CommunityReportDraft();
        draft.setKbId(kbId);
        draft.setGraphVersion(graphVersion);
        draft.setLevel(level);
        draft.setCommunityId(communityId);
        draft.setModelCode(MODEL_CODE);
        draft.setPromptVersion(PROMPT_VERSION);
        draft.setStatus("READY");

        List<GraphEntity> ents = entities != null ? entities : List.of();
        List<GraphRelation> rels = relations != null ? relations : List.of();
        List<CommunityReportDraft> children = childReports != null ? childReports : List.of();
        List<EvidenceSnippet> evs = evidences != null ? evidences : List.of();

        for (GraphEntity e : ents)
        {
            if (e != null && e.getName() != null && !e.getName().isBlank())
            {
                draft.getEntityNames().add(e.getName().trim());
            }
        }
        for (CommunityReportDraft c : children)
        {
            if (c != null)
            {
                draft.getEntityNames().addAll(c.getEntityNames());
                if (c.isIncomplete())
                {
                    draft.setIncomplete(true);
                }
            }
        }

        // 出处：实体 sourceIds + 证据 + 子报告
        LinkedHashSet<Long> sources = new LinkedHashSet<>();
        for (GraphEntity e : ents)
        {
            if (e != null && e.getSourceIds() != null)
            {
                for (Long sid : e.getSourceIds())
                {
                    if (sid != null && sid > 0)
                    {
                        sources.add(sid);
                    }
                }
            }
        }
        for (GraphRelation r : rels)
        {
            if (r != null && r.getSourceIds() != null)
            {
                for (Long sid : r.getSourceIds())
                {
                    if (sid != null && sid > 0)
                    {
                        sources.add(sid);
                    }
                }
            }
        }
        for (EvidenceSnippet sn : evs)
        {
            if (sn != null && sn.chunkId() != null && sn.chunkId() > 0)
            {
                sources.add(sn.chunkId());
            }
        }
        for (CommunityReportDraft c : children)
        {
            if (c != null)
            {
                sources.addAll(c.getSourceChunkIds());
            }
        }
        draft.getSourceChunkIds().addAll(sources);

        List<String> topEntities = draft.getEntityNames().stream()
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .limit(12)
            .collect(Collectors.toList());

        String title = topEntities.isEmpty()
            ? ("社区 L" + level + "-" + communityId)
            : String.join("、", topEntities.subList(0, Math.min(3, topEntities.size())));
        draft.setTitle(title);

        StringBuilder summary = new StringBuilder();
        summary.append("本社区含 ").append(draft.getEntityNames().size()).append(" 个实体");
        if (!rels.isEmpty())
        {
            summary.append("、").append(rels.size()).append(" 条关系");
        }
        if (!children.isEmpty())
        {
            summary.append("、").append(children.size()).append(" 个子社区");
        }
        if (!topEntities.isEmpty())
        {
            summary.append("。关键实体：").append(String.join("、", topEntities));
        }
        if (draft.isIncomplete())
        {
            summary.append("（部分子报告不完整）");
        }
        summary.append("。");
        draft.setSummary(truncate(summary.toString(), 400));

        // findings JSON（稳定序列化）
        List<String> findings = new ArrayList<>();
        for (GraphRelation r : rels.stream()
            .sorted(Comparator.comparing((GraphRelation x) -> nz(x.getSourceName()))
                .thenComparing(x -> nz(x.getTargetName()))
                .thenComparing(x -> nz(x.getKeywords())))
            .limit(8)
            .toList())
        {
            findings.add(escapeJson(nz(r.getSourceName()) + " -[" + nz(r.getKeywords()) + "]-> "
                + nz(r.getTargetName())));
        }
        for (CommunityReportDraft c : children)
        {
            if (c != null && c.getSummary() != null && !c.getSummary().isBlank() && findings.size() < 8)
            {
                findings.add(escapeJson("子社区: " + c.getTitle() + " — " + truncate(c.getSummary(), 80)));
            }
        }
        String findingsJson = "[" + findings.stream()
            .map(f -> "{\"claim\":\"" + f + "\",\"importance\":1}")
            .collect(Collectors.joining(",")) + "]";
        draft.setFindingsJson(findingsJson);

        StringBuilder full = new StringBuilder();
        full.append("# ").append(title).append("\n\n");
        full.append(draft.getSummary()).append("\n\n");
        full.append("## 关键实体\n");
        for (String n : topEntities)
        {
            full.append("- ").append(n).append("\n");
        }
        full.append("\n## 关系\n");
        for (GraphRelation r : rels.stream()
            .sorted(Comparator.comparing((GraphRelation x) -> nz(x.getSourceName()))
                .thenComparing(x -> nz(x.getTargetName())))
            .limit(20)
            .toList())
        {
            full.append("- ").append(nz(r.getSourceName())).append(" --")
                .append(nz(r.getKeywords())).append("--> ").append(nz(r.getTargetName())).append("\n");
        }
        if (!children.isEmpty())
        {
            full.append("\n## 子社区\n");
            for (CommunityReportDraft c : children)
            {
                if (c == null)
                {
                    continue;
                }
                full.append("### ").append(c.getTitle()).append("\n");
                full.append(c.getSummary() != null ? c.getSummary() : "").append("\n");
            }
        }
        if (!sources.isEmpty())
        {
            full.append("\n## 来源 chunk\n");
            int i = 0;
            for (Long sid : sources)
            {
                if (i++ >= 20)
                {
                    break;
                }
                full.append("- ").append(sid).append("\n");
            }
        }
        draft.setFullContent(full.toString());

        String material = materialFingerprint(level, communityId, topEntities, rels, children, sources);
        draft.setContentHash(sha256(material + "|" + PROMPT_VERSION + "|" + MODEL_CODE));
        return draft;
    }

    /** 估算 token，便于超限时 map-reduce 分批 */
    public static int estimateTokens(CommunityReportDraft draft)
    {
        if (draft == null)
        {
            return 0;
        }
        return TokenCounter.estimate(
            nz(draft.getTitle()) + "\n" + nz(draft.getSummary()) + "\n" + nz(draft.getFullContent()));
    }

    public static String materialFingerprint(int level, long communityId,
        List<String> entities, List<GraphRelation> rels,
        List<CommunityReportDraft> children, Set<Long> sources)
    {
        StringBuilder sb = new StringBuilder();
        sb.append(level).append('|').append(communityId).append('|');
        if (entities != null)
        {
            entities.stream().sorted(String.CASE_INSENSITIVE_ORDER).forEach(e -> sb.append(e).append(','));
        }
        sb.append('|');
        if (rels != null)
        {
            rels.stream()
                .sorted(Comparator.comparing((GraphRelation x) -> nz(x.getSourceName()))
                    .thenComparing(x -> nz(x.getTargetName()))
                    .thenComparing(x -> nz(x.getKeywords())))
                .forEach(r -> sb.append(nz(r.getSourceName())).append('>')
                    .append(nz(r.getKeywords())).append('>')
                    .append(nz(r.getTargetName())).append(';'));
        }
        sb.append('|');
        if (children != null)
        {
            children.stream()
                .filter(c -> c != null && c.getContentHash() != null)
                .sorted(Comparator.comparing(CommunityReportDraft::getCommunityId))
                .forEach(c -> sb.append(c.getContentHash()).append(','));
        }
        sb.append('|');
        if (sources != null)
        {
            sources.stream().sorted().forEach(s -> sb.append(s).append(','));
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    public static String sha256(String s)
    {
        try
        {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        }
        catch (Exception e)
        {
            return Integer.toHexString(s != null ? s.hashCode() : 0);
        }
    }

    public static long stableReportId(Long kbId, String graphVersion, int level, long communityId)
    {
        String raw = (kbId == null ? 0 : kbId) + "|" + nz(graphVersion) + "|" + level + "|" + communityId;
        try
        {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            long v = 0;
            for (int i = 0; i < 8; i++)
            {
                v = (v << 8) | (dig[i] & 0xffL);
            }
            // 正数，避免 PG bigint 符号问题
            return v == Long.MIN_VALUE ? 1L : Math.abs(v);
        }
        catch (Exception e)
        {
            return Math.abs((long) raw.hashCode());
        }
    }

    private static String nz(String s)
    {
        return s == null ? "" : s.trim();
    }

    private static String truncate(String s, int maxChars)
    {
        if (s == null)
        {
            return "";
        }
        return s.length() <= maxChars ? s : s.substring(0, maxChars) + "…";
    }

    private static String escapeJson(String s)
    {
        if (s == null)
        {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }

    /** 证据片段 */
    public record EvidenceSnippet(Long chunkId, String text) {}
}
