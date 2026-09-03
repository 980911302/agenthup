package com.ruoyi.system.kb.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.ruoyi.system.domain.KbGraphCommunity;
import com.ruoyi.system.domain.KbGraphCommunityReport;

/**
 * 自顶向下动态社区选择（纯函数，可单测）。
 * <p>从最高 level 根社区开始打分；低于阈值剪枝；相关则下钻子社区；
 * 最终采用叶子层（或无法再下钻的相关节点）的报告，并受 maxSelected 预算限制。
 */
public final class DynamicCommunitySelector
{
    private DynamicCommunitySelector() {}

    public static final class Options
    {
        public double minScore = 0.15;
        public int maxSelected = 12;
        public int maxVisited = 80;
        /** level 越高越「根」；若 level 0 是叶子，根为 max level */
        public boolean higherLevelIsRoot = true;
    }

    public static final class ScoredReport
    {
        public final KbGraphCommunityReport report;
        public final double score;

        public ScoredReport(KbGraphCommunityReport report, double score)
        {
            this.report = report;
            this.score = score;
        }
    }

    public static final class Result
    {
        public final List<ScoredReport> selected = new ArrayList<>();
        public final GlobalSearchTrace trace = new GlobalSearchTrace();
    }

    /**
     * @param communities 社区节点（含 parent）
     * @param reports     报告（level+communityId）
     * @param query       查询
     * @param vectorByReportId reportId → 向量相似度（可空）
     */
    public static Result select(
        List<KbGraphCommunity> communities,
        List<KbGraphCommunityReport> reports,
        String query,
        Map<Long, Double> vectorByReportId,
        Options options)
    {
        Options opt = options != null ? options : new Options();
        Result out = new Result();
        if (reports == null || reports.isEmpty())
        {
            out.trace.setDegradeReason("no_reports");
            return out;
        }

        Map<String, KbGraphCommunityReport> reportByKey = new HashMap<>();
        for (KbGraphCommunityReport r : reports)
        {
            if (r == null || r.getLevel() == null || r.getCommunityId() == null)
            {
                continue;
            }
            // 仅 READY/PARTIAL
            if (r.getStatus() != null
                && !"READY".equalsIgnoreCase(r.getStatus())
                && !"PARTIAL".equalsIgnoreCase(r.getStatus()))
            {
                continue;
            }
            reportByKey.put(GlobalSearchTrace.key(r.getLevel(), r.getCommunityId()), r);
        }
        if (reportByKey.isEmpty())
        {
            out.trace.setDegradeReason("no_ready_reports");
            return out;
        }

        // children: parentKey -> child communities
        Map<String, List<KbGraphCommunity>> childrenOf = new HashMap<>();
        Map<String, KbGraphCommunity> communityByKey = new HashMap<>();
        int maxLevel = 0;
        int minLevel = Integer.MAX_VALUE;
        if (communities != null)
        {
            for (KbGraphCommunity c : communities)
            {
                if (c == null || c.getLevel() == null || c.getCommunityId() == null)
                {
                    continue;
                }
                String key = GlobalSearchTrace.key(c.getLevel(), c.getCommunityId());
                communityByKey.put(key, c);
                maxLevel = Math.max(maxLevel, c.getLevel());
                minLevel = Math.min(minLevel, c.getLevel());
            }
            for (KbGraphCommunity c : communities)
            {
                if (c == null || c.getParentCommunityId() == null || c.getLevel() == null)
                {
                    continue;
                }
                // parent 通常在 level+1
                String parentKey = findParentKey(communityByKey, c.getParentCommunityId(), c.getLevel() + 1);
                if (parentKey != null)
                {
                    childrenOf.computeIfAbsent(parentKey, x -> new ArrayList<>()).add(c);
                }
            }
        }
        else
        {
            for (KbGraphCommunityReport r : reportByKey.values())
            {
                maxLevel = Math.max(maxLevel, r.getLevel());
                minLevel = Math.min(minLevel, r.getLevel());
            }
        }
        if (minLevel == Integer.MAX_VALUE)
        {
            minLevel = 0;
        }

        // 根：无 parent 或最高 level
        List<String> roots = new ArrayList<>();
        if (!communityByKey.isEmpty())
        {
            for (Map.Entry<String, KbGraphCommunity> e : communityByKey.entrySet())
            {
                KbGraphCommunity c = e.getValue();
                if (c.getParentCommunityId() == null)
                {
                    roots.add(e.getKey());
                }
            }
            if (roots.isEmpty())
            {
                // 回退：最高 level 全部作为根
                for (Map.Entry<String, KbGraphCommunity> e : communityByKey.entrySet())
                {
                    if (Objects.equals(e.getValue().getLevel(), maxLevel))
                    {
                        roots.add(e.getKey());
                    }
                }
            }
        }
        else
        {
            for (String key : reportByKey.keySet())
            {
                if (key.startsWith(maxLevel + "|"))
                {
                    roots.add(key);
                }
            }
            if (roots.isEmpty())
            {
                roots.addAll(reportByKey.keySet());
            }
        }

        // 按分数优先探索根
        List<ScoredNode> rootScored = new ArrayList<>();
        for (String rk : roots)
        {
            rootScored.add(scoreNode(rk, reportByKey, query, vectorByReportId));
        }
        rootScored.sort(Comparator.comparingDouble((ScoredNode s) -> s.score).reversed());

        int visited = 0;
        for (ScoredNode root : rootScored)
        {
            if (out.selected.size() >= opt.maxSelected || visited >= opt.maxVisited)
            {
                break;
            }
            visited = dfsSelect(root, reportByKey, childrenOf, query, vectorByReportId,
                opt, out, visited, minLevel);
        }

        // 若全部剪枝导致空：降级取全局 top 词面报告（仍不塞全部）
        if (out.selected.isEmpty())
        {
            out.trace.getNotes().add("all_pruned_fallback_top");
            List<ScoredReport> all = new ArrayList<>();
            for (KbGraphCommunityReport r : reportByKey.values())
            {
                Double vs = vectorByReportId != null && r.getReportId() != null
                    ? vectorByReportId.get(r.getReportId()) : null;
                double sc = CommunityRelevanceScorer.score(query, r.getTitle(), r.getSummary(), vs);
                all.add(new ScoredReport(r, sc));
            }
            all.sort(Comparator.comparingDouble((ScoredReport s) -> s.score).reversed());
            int n = Math.min(opt.maxSelected, Math.max(1, Math.min(5, all.size())));
            for (int i = 0; i < n; i++)
            {
                ScoredReport s = all.get(i);
                if (s.score <= 0 && i > 0)
                {
                    break;
                }
                out.selected.add(s);
                out.trace.select(s.report.getLevel(), s.report.getCommunityId());
            }
            if (out.selected.isEmpty())
            {
                out.trace.setDegradeReason("no_relevant_reports");
            }
            else if (out.trace.getDegradeReason() == null)
            {
                out.trace.setDegradeReason("topdown_empty_used_topk");
            }
        }

        // 最终按分数排序
        out.selected.sort(Comparator.comparingDouble((ScoredReport s) -> s.score).reversed());
        if (out.selected.size() > opt.maxSelected)
        {
            List<ScoredReport> trimmed = new ArrayList<>(out.selected.subList(0, opt.maxSelected));
            out.selected.clear();
            out.selected.addAll(trimmed);
        }
        return out;
    }

    private static int dfsSelect(
        ScoredNode node,
        Map<String, KbGraphCommunityReport> reportByKey,
        Map<String, List<KbGraphCommunity>> childrenOf,
        String query,
        Map<Long, Double> vectorByReportId,
        Options opt,
        Result out,
        int visited,
        int leafLevel)
    {
        if (node == null || out.selected.size() >= opt.maxSelected || visited >= opt.maxVisited)
        {
            return visited;
        }
        visited++;
        int level = node.level;
        long cid = node.communityId;
        out.trace.visit(level, cid);

        if (node.score < opt.minScore)
        {
            out.trace.prune(level, cid, "score=" + round(node.score));
            return visited;
        }

        List<KbGraphCommunity> children = childrenOf.getOrDefault(node.key, List.of());
        if (children.isEmpty() || level <= leafLevel)
        {
            // 叶或无可下钻：采用本节点报告
            KbGraphCommunityReport r = reportByKey.get(node.key);
            if (r != null)
            {
                out.selected.add(new ScoredReport(r, node.score));
                out.trace.select(level, cid);
            }
            else
            {
                out.trace.prune(level, cid, "no_report");
            }
            return visited;
        }

        // 相关父节点：下钻子节点
        List<ScoredNode> childScored = new ArrayList<>();
        for (KbGraphCommunity ch : children)
        {
            String ck = GlobalSearchTrace.key(ch.getLevel(), ch.getCommunityId());
            childScored.add(scoreNode(ck, reportByKey, query, vectorByReportId));
        }
        childScored.sort(Comparator.comparingDouble((ScoredNode s) -> s.score).reversed());

        boolean anyChildSelected = false;
        int before = out.selected.size();
        for (ScoredNode ch : childScored)
        {
            if (out.selected.size() >= opt.maxSelected || visited >= opt.maxVisited)
            {
                break;
            }
            visited = dfsSelect(ch, reportByKey, childrenOf, query, vectorByReportId,
                opt, out, visited, leafLevel);
        }
        anyChildSelected = out.selected.size() > before;
        if (!anyChildSelected)
        {
            // 子全剪：回退采用父报告（map 仍有材料）
            KbGraphCommunityReport r = reportByKey.get(node.key);
            if (r != null)
            {
                out.selected.add(new ScoredReport(r, node.score));
                out.trace.select(level, cid);
                out.trace.getNotes().add("parent_fallback " + node.key);
            }
        }
        return visited;
    }

    private static ScoredNode scoreNode(
        String key,
        Map<String, KbGraphCommunityReport> reportByKey,
        String query,
        Map<Long, Double> vectorByReportId)
    {
        String[] parts = key.split("\\|", 2);
        int level = Integer.parseInt(parts[0]);
        long cid = Long.parseLong(parts[1]);
        KbGraphCommunityReport r = reportByKey.get(key);
        double score = 0.0;
        if (r != null)
        {
            Double vs = vectorByReportId != null && r.getReportId() != null
                ? vectorByReportId.get(r.getReportId()) : null;
            score = CommunityRelevanceScorer.score(query, r.getTitle(), r.getSummary(), vs);
        }
        return new ScoredNode(key, level, cid, score);
    }

    private static String findParentKey(Map<String, KbGraphCommunity> byKey, Long parentId, int preferredLevel)
    {
        if (parentId == null)
        {
            return null;
        }
        String pref = preferredLevel + "|" + parentId;
        if (byKey.containsKey(pref))
        {
            return pref;
        }
        for (String k : byKey.keySet())
        {
            if (k.endsWith("|" + parentId))
            {
                return k;
            }
        }
        return null;
    }

    private static double round(double v)
    {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static final class ScoredNode
    {
        final String key;
        final int level;
        final long communityId;
        final double score;

        ScoredNode(String key, int level, long communityId, double score)
        {
            this.key = key;
            this.level = level;
            this.communityId = communityId;
            this.score = score;
        }
    }
}
