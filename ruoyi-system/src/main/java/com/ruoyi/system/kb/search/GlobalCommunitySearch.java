package com.ruoyi.system.kb.search;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.ruoyi.common.datasource.DataSourceScope;
import com.ruoyi.common.enums.DataSourceType;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.ai.EmbeddingModelFactory;
import com.ruoyi.system.domain.AiModel;
import com.ruoyi.system.domain.KbChunk;
import com.ruoyi.system.domain.KbGraphCommunity;
import com.ruoyi.system.domain.KbGraphCommunityReport;
import com.ruoyi.system.domain.KbGraphCommunityReportSource;
import com.ruoyi.system.domain.KbGraphIndex;
import com.ruoyi.system.domain.KbKnowledge;
import com.ruoyi.system.kb.chunker.ChunkLevels;
import com.ruoyi.system.kb.graph.community.CommunityReportVectorStore;
import com.ruoyi.system.kb.search.DynamicCommunitySelector.Options;
import com.ruoyi.system.kb.search.DynamicCommunitySelector.Result;
import com.ruoyi.system.kb.search.DynamicCommunitySelector.ScoredReport;
import com.ruoyi.system.kb.vector.KbSearchHit;
import com.ruoyi.system.mapper.KbChunkMapper;
import com.ruoyi.system.mapper.KbGraphCommunityMapper;
import com.ruoyi.system.service.IAiModelService;

/**
 * Global Search：动态社区选择 + 报告 map（第一版 extractive，不无条件塞全库报告）。
 */
@Service
public class GlobalCommunitySearch
{
    private static final Logger log = LoggerFactory.getLogger(GlobalCommunitySearch.class);

    @Autowired
    private KbGraphCommunityMapper communityMapper;
    @Autowired
    private KbChunkMapper kbChunkMapper;
    @Autowired(required = false)
    private CommunityReportVectorStore reportVectorStore;
    @Autowired
    private IAiModelService aiModelService;
    @Autowired
    private EmbeddingModelFactory embeddingModelFactory;

    @Value("${ai.kb.search.global.min-score:0.15}")
    private double minScore;

    @Value("${ai.kb.search.global.max-selected:12}")
    private int maxSelected;

    @Value("${ai.kb.search.global.max-visited:80}")
    private int maxVisited;

    @Value("${ai.kb.search.global.use-report-vector:true}")
    private boolean useReportVector;

    @Value("${ai.kb.search.global.include-source-chunks:true}")
    private boolean includeSourceChunks;

    @Value("${ai.kb.search.global.max-source-chunks-per-report:3}")
    private int maxSourceChunksPerReport;

    /**
     * @return 命中列表；空列表表示能力不可用（调用方应降级 Basic/Hybrid）
     */
    public List<KbSearchHit> search(KbKnowledge kb, String query, int topK)
    {
        GlobalSearchResult packed = searchWithTrace(kb, query, topK);
        return packed.hits;
    }

    public GlobalSearchResult searchWithTrace(KbKnowledge kb, String query, int topK)
    {
        GlobalSearchResult out = new GlobalSearchResult();
        out.trace = new GlobalSearchTrace();
        if (kb == null || kb.getKbId() == null || StringUtils.isEmpty(query))
        {
            out.trace.setDegradeReason("invalid_args");
            return out;
        }

        KbGraphIndex idx = DataSourceScope.runOn(DataSourceType.SLAVE,
            () -> communityMapper.selectIndex(kb.getKbId()));
        if (idx == null || StringUtils.isEmpty(idx.getGraphVersion()))
        {
            out.trace.setDegradeReason("no_graph_index");
            return out;
        }
        if (!"READY".equalsIgnoreCase(idx.getStatus()) && !"DIRTY".equalsIgnoreCase(idx.getStatus()))
        {
            // RUNNING/FAILED/SKIPPED 也允许读已有报告；仅完全无版本才失败
            out.trace.getNotes().add("index_status=" + idx.getStatus());
        }
        String gv = idx.getGraphVersion();
        out.trace.setGraphVersion(gv);

        List<KbGraphCommunityReport> reports = DataSourceScope.runOn(DataSourceType.SLAVE,
            () -> communityMapper.selectReports(kb.getKbId(), gv));
        List<KbGraphCommunity> communities = DataSourceScope.runOn(DataSourceType.SLAVE,
            () -> communityMapper.selectCommunities(kb.getKbId(), gv));
        if (reports == null || reports.isEmpty())
        {
            out.trace.setDegradeReason("no_reports");
            return out;
        }

        Map<Long, Double> vectorScores = loadVectorScores(kb, query, Math.max(topK * 4, 20));

        Options opt = new Options();
        opt.minScore = minScore;
        opt.maxSelected = Math.max(1, maxSelected);
        opt.maxVisited = Math.max(10, maxVisited);

        Result selected = DynamicCommunitySelector.select(
            communities, reports, query, vectorScores, opt);
        out.trace = selected.trace;
        out.trace.setGraphVersion(gv);

        if (selected.selected.isEmpty())
        {
            if (out.trace.getDegradeReason() == null)
            {
                out.trace.setDegradeReason("selector_empty");
            }
            return out;
        }

        // Map：选中报告 → community 通道命中
        int k = topK > 0 ? topK : 8;
        List<KbSearchHit> hits = new ArrayList<>();
        int rank = 1;
        for (ScoredReport sr : selected.selected)
        {
            if (hits.size() >= k)
            {
                break;
            }
            KbGraphCommunityReport r = sr.report;
            KbSearchHit h = new KbSearchHit();
            h.setKbId(kb.getKbId());
            h.setChunkId(null); // 社区报告非 chunk
            h.setDocName("社区报告 L" + r.getLevel() + "-" + r.getCommunityId());
            h.setHeadingPath(r.getTitle());
            h.setContent(buildMapContent(r));
            h.setScore(sr.score);
            h.setChannel("community");
            h.setRankByChannel(rank++);
            h.setCommunityIds(List.of(r.getCommunityId()));
            hits.add(h);

            // 可选：附带来源 LEAF chunk（证据）
            if (includeSourceChunks && r.getReportId() != null && hits.size() < k)
            {
                hits.addAll(loadSourceHits(kb.getKbId(), r, sr.score * 0.95,
                    Math.min(maxSourceChunksPerReport, k - hits.size())));
            }
        }

        // 截断到 topK
        if (hits.size() > k)
        {
            hits = new ArrayList<>(hits.subList(0, k));
        }

        // 挂 trace 到首条
        if (!hits.isEmpty())
        {
            hits.get(0).setDebugTrace(out.trace.toJson());
        }
        out.hits = hits;
        log.debug("Global search kbId={} selected={} hits={} visited={} pruned={}",
            kb.getKbId(), selected.selected.size(), hits.size(),
            out.trace.getVisited().size(), out.trace.getPruned().size());
        return out;
    }

    private Map<Long, Double> loadVectorScores(KbKnowledge kb, String query, int topK)
    {
        Map<Long, Double> map = new HashMap<>();
        if (!useReportVector || reportVectorStore == null
            || StringUtils.isEmpty(kb.getEmbeddingModelCode()))
        {
            return map;
        }
        try
        {
            EmbeddingModel emb = DataSourceScope.runOn(DataSourceType.MASTER, () -> {
                AiModel model = aiModelService.selectByModelCode(kb.getEmbeddingModelCode());
                if (model == null)
                {
                    return null;
                }
                return embeddingModelFactory.get(model.getModelId());
            });
            if (emb == null)
            {
                return map;
            }
            float[] qv = emb.embed(query);
            if (qv == null || qv.length == 0)
            {
                return map;
            }
            List<Map<String, Object>> rows = reportVectorStore.search(kb.getKbId(), qv, topK);
            if (rows == null)
            {
                return map;
            }
            for (Map<String, Object> row : rows)
            {
                Long reportId = toLong(row.get("reportId"));
                if (reportId == null)
                {
                    reportId = toLong(row.get("reportid"));
                }
                double score = toDouble(row.get("score"));
                if (reportId != null)
                {
                    map.put(reportId, score);
                }
            }
        }
        catch (Exception e)
        {
            log.debug("社区报告向量检索跳过: {}", e.getMessage());
        }
        return map;
    }

    private List<KbSearchHit> loadSourceHits(Long kbId, KbGraphCommunityReport report,
        double baseScore, int limit)
    {
        if (limit <= 0 || report.getReportId() == null)
        {
            return List.of();
        }
        List<KbGraphCommunityReportSource> sources = DataSourceScope.runOn(DataSourceType.SLAVE,
            () -> communityMapper.selectReportSources(report.getReportId()));
        if (sources == null || sources.isEmpty())
        {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        for (KbGraphCommunityReportSource s : sources)
        {
            if (s.getChunkId() != null)
            {
                ids.add(s.getChunkId());
            }
            if (ids.size() >= limit * 2)
            {
                break;
            }
        }
        if (ids.isEmpty())
        {
            return List.of();
        }
        List<KbChunk> chunks = DataSourceScope.runOn(DataSourceType.SLAVE,
            () -> kbChunkMapper.selectByChunkIds(ids));
        if (chunks == null)
        {
            return List.of();
        }
        Map<Long, KbChunk> byId = new LinkedHashMap<>();
        for (KbChunk c : chunks)
        {
            if (c != null && c.getChunkId() != null)
            {
                byId.put(c.getChunkId(), c);
            }
        }
        List<KbSearchHit> hits = new ArrayList<>();
        int i = 0;
        for (Long id : ids)
        {
            KbChunk c = byId.get(id);
            if (c == null)
            {
                continue;
            }
            if (c.getChunkLevel() != null && !ChunkLevels.isLeaf(c.getChunkLevel()))
            {
                continue;
            }
            KbSearchHit h = new KbSearchHit();
            h.setChunkId(c.getChunkId());
            h.setKbId(kbId);
            h.setDocId(c.getDocId());
            h.setDocName(c.getDocName());
            h.setHeadingPath(c.getHeadingPath());
            h.setChunkIndex(c.getChunkIndex());
            h.setContent(c.getContent());
            h.setParentChunkId(c.getParentChunkId());
            h.setScore(baseScore * (1.0 - i * 0.03));
            h.setChannel("community");
            h.setCommunityIds(List.of(report.getCommunityId()));
            hits.add(h);
            i++;
            if (hits.size() >= limit)
            {
                break;
            }
        }
        return hits;
    }

    private static String buildMapContent(KbGraphCommunityReport r)
    {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.isNotEmpty(r.getTitle()))
        {
            sb.append("【").append(r.getTitle()).append("】\n");
        }
        if (StringUtils.isNotEmpty(r.getSummary()))
        {
            sb.append(r.getSummary());
        }
        else if (StringUtils.isNotEmpty(r.getFullContent()))
        {
            String fc = r.getFullContent();
            sb.append(fc.length() > 800 ? fc.substring(0, 800) + "…" : fc);
        }
        return sb.toString().trim();
    }

    private static Long toLong(Object o)
    {
        if (o == null)
        {
            return null;
        }
        if (o instanceof Number n)
        {
            return n.longValue();
        }
        try
        {
            return Long.parseLong(o.toString());
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static double toDouble(Object o)
    {
        if (o == null)
        {
            return 0.0;
        }
        if (o instanceof Number n)
        {
            return n.doubleValue();
        }
        try
        {
            return Double.parseDouble(o.toString());
        }
        catch (Exception e)
        {
            return 0.0;
        }
    }

    public static final class GlobalSearchResult
    {
        public List<KbSearchHit> hits = List.of();
        public GlobalSearchTrace trace = new GlobalSearchTrace();
    }
}
