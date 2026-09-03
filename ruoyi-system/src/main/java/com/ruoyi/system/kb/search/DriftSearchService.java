package com.ruoyi.system.kb.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.domain.KbKnowledge;
import com.ruoyi.system.kb.chunker.TokenCounter;
import com.ruoyi.system.kb.vector.KbSearchHit;

/**
 * DRIFT：Local/Hybrid 起点 + 可选社区上下文 + 有限规则追问。
 * <p>硬预算保证终止；任一步失败不抛穿，返回已有证据或由上层降级。
 */
@Service
public class DriftSearchService
{
    private static final Logger log = LoggerFactory.getLogger(DriftSearchService.class);

    @Autowired(required = false)
    private GlobalCommunitySearch globalCommunitySearch;

    @Value("${ai.kb.search.drift.max-depth:2}")
    private int maxDepth;

    @Value("${ai.kb.search.drift.max-followups-per-level:3}")
    private int maxFollowupsPerLevel;

    @Value("${ai.kb.search.drift.max-retrievals:12}")
    private int maxRetrievals;

    @Value("${ai.kb.search.drift.max-tokens:8000}")
    private int maxTokens;

    @Value("${ai.kb.search.drift.timeout-ms:30000}")
    private long timeoutMs;

    @Value("${ai.kb.search.drift.dedupe-threshold:0.75}")
    private double dedupeThreshold;

    @Value("${ai.kb.search.drift.include-community:true}")
    private boolean includeCommunity;

    @Value("${ai.kb.search.drift.seed-mode:hybrid}")
    private String seedMode;

    /**
     * @param localFn  (query, topK) -> local hits；由 KbSearchService 注入，避免循环依赖细节
     * @param hybridFn (query, topK) -> hybrid hits
     */
    public DriftResult search(
        KbKnowledge kb,
        String query,
        int topK,
        BiFunction<String, Integer, List<KbSearchHit>> localFn,
        BiFunction<String, Integer, List<KbSearchHit>> hybridFn)
    {
        DriftSearchBudget budget = new DriftSearchBudget(
            maxDepth, maxFollowupsPerLevel, maxRetrievals, maxTokens, timeoutMs);
        DriftResult result = new DriftResult();
        result.budget = budget;

        if (kb == null || StringUtils.isEmpty(query))
        {
            budget.stop("invalid_args");
            return result;
        }

        Map<Long, KbSearchHit> byChunk = new LinkedHashMap<>();
        Set<String> asked = new LinkedHashSet<>();
        asked.add(DriftFollowupPlanner.normalize(query));

        // --- seed: Local/Hybrid ---
        List<KbSearchHit> seed = List.of();
        try
        {
            if (!budget.consumeRetrieval())
            {
                return result;
            }
            boolean preferHybrid = !"local".equalsIgnoreCase(seedMode);
            if (preferHybrid && hybridFn != null)
            {
                seed = nullToEmpty(hybridFn.apply(query, topK));
            }
            if (seed.isEmpty() && localFn != null)
            {
                seed = nullToEmpty(localFn.apply(query, topK));
            }
            mergeHits(byChunk, seed, "drift");
            budget.consumeTokens(estimateTokens(seed));
            result.steps.add("seed:" + (preferHybrid ? "hybrid" : "local") + " hits=" + seed.size());
        }
        catch (Exception e)
        {
            log.debug("DRIFT seed 失败: {}", e.getMessage());
            result.steps.add("seed_failed:" + e.getMessage());
            budget.stop("seed_failed");
            // 上层可再降级；此处返回已有（可能空）
            result.hits = ranked(byChunk, topK);
            attachTrace(result);
            return result;
        }

        // --- optional community context (1 retrieval) ---
        if (includeCommunity && globalCommunitySearch != null && budget.canContinue())
        {
            try
            {
                if (budget.consumeRetrieval())
                {
                    List<KbSearchHit> comm = globalCommunitySearch.search(kb, query,
                        Math.min(3, topK));
                    int before = byChunk.size();
                    mergeHits(byChunk, comm, "drift");
                    budget.consumeTokens(estimateTokens(comm));
                    result.steps.add("community_hits=" + (comm != null ? comm.size() : 0)
                        + " new=" + (byChunk.size() - before));
                }
            }
            catch (Exception e)
            {
                result.steps.add("community_skip:" + e.getMessage());
            }
        }

        // --- follow-up levels ---
        while (budget.canContinue() && budget.enterDepth())
        {
            List<KbSearchHit> current = ranked(byChunk, Math.max(topK, 8));
            List<String> followups = DriftFollowupPlanner.plan(
                query, current, asked, budget.getMaxFollowupsPerLevel(), dedupeThreshold);
            if (followups.isEmpty())
            {
                budget.stop("no_followups");
                result.steps.add("depth" + budget.getDepthUsed() + ":no_followups");
                break;
            }

            int newEvidence = 0;
            for (String fq : followups)
            {
                if (!budget.allowFollowup() || !budget.canContinue())
                {
                    break;
                }
                budget.consumeFollowup();
                asked.add(DriftFollowupPlanner.normalize(fq));
                if (!budget.consumeRetrieval())
                {
                    break;
                }
                try
                {
                    List<KbSearchHit> more = List.of();
                    if (localFn != null)
                    {
                        more = nullToEmpty(localFn.apply(fq, Math.min(topK, 5)));
                    }
                    if (more.isEmpty() && hybridFn != null)
                    {
                        more = nullToEmpty(hybridFn.apply(fq, Math.min(topK, 5)));
                    }
                    int before = byChunk.size();
                    mergeHits(byChunk, more, "drift");
                    int added = byChunk.size() - before;
                    newEvidence += added;
                    budget.consumeTokens(estimateTokens(more));
                    result.steps.add("followup[" + budget.getDepthUsed() + "]:" + fq
                        + " +=" + added);
                }
                catch (Exception e)
                {
                    result.steps.add("followup_fail:" + e.getMessage());
                    // 单步失败继续，不整次失败
                }
            }

            if (newEvidence == 0)
            {
                budget.stop("no_new_evidence");
                result.steps.add("depth" + budget.getDepthUsed() + ":no_new_evidence");
                break;
            }
        }

        if (budget.getStopReason() == null)
        {
            budget.stop("completed");
        }

        result.hits = ranked(byChunk, topK);
        // 标记 channel
        for (int i = 0; i < result.hits.size(); i++)
        {
            KbSearchHit h = result.hits.get(i);
            h.setChannel("drift");
            h.setRankByChannel(i + 1);
        }
        attachTrace(result);
        log.debug("DRIFT done kbId={} hits={} retrievals={} stop={}",
            kb.getKbId(), result.hits.size(), budget.getRetrievalsUsed(), budget.getStopReason());
        return result;
    }

    private static void attachTrace(DriftResult result)
    {
        if (result.hits == null || result.hits.isEmpty())
        {
            return;
        }
        DriftSearchBudget b = result.budget;
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"channel\":\"drift\",");
        sb.append("\"stopReason\":\"").append(esc(b != null ? b.getStopReason() : null)).append("\",");
        sb.append("\"depthUsed\":").append(b != null ? b.getDepthUsed() : 0).append(',');
        sb.append("\"retrievalsUsed\":").append(b != null ? b.getRetrievalsUsed() : 0).append(',');
        sb.append("\"tokensUsed\":").append(b != null ? b.getTokensUsed() : 0).append(',');
        sb.append("\"steps\":[");
        for (int i = 0; i < result.steps.size(); i++)
        {
            if (i > 0)
            {
                sb.append(',');
            }
            sb.append('"').append(esc(result.steps.get(i))).append('"');
        }
        sb.append("]}");
        result.hits.get(0).setDebugTrace(sb.toString());
    }

    private static void mergeHits(Map<Long, KbSearchHit> byChunk, List<KbSearchHit> hits, String channel)
    {
        if (hits == null)
        {
            return;
        }
        for (KbSearchHit h : hits)
        {
            if (h == null)
            {
                continue;
            }
            // 无 chunkId 的社区报告：用负 report 伪键合并（docName+content hash）
            Long key = h.getChunkId();
            if (key == null)
            {
                key = (long) ("R|" + nz(h.getDocName()) + "|" + nz(h.getContent())).hashCode();
                // 避免 0
                if (key == 0L)
                {
                    key = -1L;
                }
            }
            KbSearchHit existing = byChunk.get(key);
            if (existing == null || h.getScore() > existing.getScore())
            {
                h.setChannel(channel);
                byChunk.put(key, h);
            }
            else if (existing.getEntityNames() != null && h.getEntityNames() != null)
            {
                for (String e : h.getEntityNames())
                {
                    if (e != null && !existing.getEntityNames().contains(e))
                    {
                        existing.getEntityNames().add(e);
                    }
                }
            }
        }
    }

    private static List<KbSearchHit> ranked(Map<Long, KbSearchHit> byChunk, int topK)
    {
        List<KbSearchHit> list = new ArrayList<>(byChunk.values());
        list.sort(Comparator.comparingDouble(KbSearchHit::getScore).reversed());
        if (topK > 0 && list.size() > topK)
        {
            return new ArrayList<>(list.subList(0, topK));
        }
        return list;
    }

    private static int estimateTokens(List<KbSearchHit> hits)
    {
        if (hits == null)
        {
            return 0;
        }
        int t = 0;
        for (KbSearchHit h : hits)
        {
            if (h != null && h.getContent() != null)
            {
                t += TokenCounter.estimate(h.getContent());
            }
        }
        return t;
    }

    private static List<KbSearchHit> nullToEmpty(List<KbSearchHit> list)
    {
        return list == null ? List.of() : list;
    }

    private static String nz(String s)
    {
        return s == null ? "" : s;
    }

    private static String esc(String s)
    {
        if (s == null)
        {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public static final class DriftResult
    {
        public List<KbSearchHit> hits = List.of();
        public DriftSearchBudget budget;
        public final List<String> steps = new ArrayList<>();
    }
}
