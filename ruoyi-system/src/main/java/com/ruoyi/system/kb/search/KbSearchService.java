package com.ruoyi.system.kb.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.ruoyi.common.datasource.DataSourceScope;
import com.ruoyi.common.enums.DataSourceType;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.ai.EmbeddingModelFactory;
import com.ruoyi.system.domain.AiModel;
import com.ruoyi.system.domain.KbChunk;
import com.ruoyi.system.domain.KbKnowledge;
import com.ruoyi.system.kb.KbConstants;
import com.ruoyi.system.kb.chunker.ChunkLevels;
import com.ruoyi.system.kb.graph.GraphEntity;
import com.ruoyi.system.kb.graph.KbGraphConstants;
import com.ruoyi.system.kb.graph.KbGraphStore;
import com.ruoyi.system.kb.search.ReciprocalRankFusion.FusedHit;
import com.ruoyi.system.kb.vector.KbSearchHit;
import com.ruoyi.system.kb.vector.KbVectorStore;
import com.ruoyi.system.mapper.KbChunkMapper;
import com.ruoyi.system.mapper.KbKnowledgeMapper;
import com.ruoyi.system.service.IAiModelService;

/**
 * 跨知识库检索：Basic / Local / Hybrid / Global / DRIFT / Auto。
 * <p>非 Basic 失败时退回 Basic（或模式内降级），默认模式永远是 Basic。
 */
@Service
public class KbSearchService
{
    private static final Logger log = LoggerFactory.getLogger(KbSearchService.class);

    private static final Pattern SPLIT = Pattern.compile("[\\s,，。；;、:：!?？\\-_/\\\\|]+");

    @Autowired
    private KbKnowledgeMapper kbKnowledgeMapper;
    @Autowired
    private IAiModelService aiModelService;
    @Autowired
    private EmbeddingModelFactory embeddingModelFactory;
    @Autowired
    private KbVectorStore kbVectorStore;
    @Autowired
    private KbGraphStore kbGraphStore;
    @Autowired
    private KbChunkMapper kbChunkMapper;
    @Autowired
    private ChunkContextExpander chunkContextExpander;
    @Autowired(required = false)
    private GlobalCommunitySearch globalCommunitySearch;
    @Autowired(required = false)
    private DriftSearchService driftSearchService;
    @Autowired(required = false)
    private KbSearchModePolicy modePolicy;
    @Autowired(required = false)
    private KbSearchMetrics searchMetrics;

    @Value("${ai.kb.search.rrf-k:60}")
    private int rrfK;

    @Value("${ai.kb.search.local-hops:1}")
    private int localHops;

    @Value("${ai.kb.search.global.fallback-hybrid:true}")
    private boolean globalFallbackHybrid;

    private Semaphore searchSemaphore;

    @PostConstruct
    public void initConcurrency()
    {
        int n = modePolicy != null ? modePolicy.getMaxConcurrent() : 32;
        searchSemaphore = new Semaphore(Math.max(1, n), true);
    }

    public List<KbSearchHit> search(List<Long> kbIds, String query, int topK, double minScore)
    {
        return search(kbIds, query, topK, minScore, KbSearchMode.basic, false);
    }

    public List<KbSearchHit> search(List<Long> kbIds, String query, int topK, double minScore,
                                    KbSearchMode mode)
    {
        return search(kbIds, query, topK, minScore, mode, false);
    }

    /**
     * @param keepDebugTrace 管理台调试时可 true；生产/工具默认 false 会按策略剥离
     */
    public List<KbSearchHit> search(List<Long> kbIds, String query, int topK, double minScore,
                                    KbSearchMode mode, boolean keepDebugTrace)
    {
        if (kbIds == null || kbIds.isEmpty() || StringUtils.isEmpty(query))
        {
            return List.of();
        }
        long t0 = System.currentTimeMillis();
        boolean degraded = false;
        boolean failed = false;
        KbSearchMode requested = KbSearchMode.normalize(mode);
        KbSearchMode m = requested;
        // 灰度：未开放模式降到 default
        if (modePolicy != null)
        {
            if (m != KbSearchMode.auto && !modePolicy.isEnabled(m))
            {
                degraded = true;
                m = modePolicy.defaultMode();
            }
            else if (m == KbSearchMode.auto && !modePolicy.isEnabled(KbSearchMode.auto))
            {
                degraded = true;
                m = modePolicy.defaultMode();
            }
        }

        boolean acquired = false;
        try
        {
            if (searchSemaphore != null)
            {
                long waitMs = modePolicy != null ? Math.min(5000L, modePolicy.getPerModeTimeoutMs()) : 5000L;
                acquired = searchSemaphore.tryAcquire(waitMs, TimeUnit.MILLISECONDS);
                if (!acquired)
                {
                    failed = true;
                    log.warn("检索并发已满,拒绝 mode={}", m);
                    return List.of();
                }
            }

            int k = topK > 0 ? topK : KbConstants.DEFAULT_TOP_K;
            double min = minScore < 0 ? KbConstants.DEFAULT_MIN_SCORE : minScore;
            QueryRouter.Decision routeDecision = null;
            if (m == KbSearchMode.auto)
            {
                routeDecision = QueryRouter.route(query);
                m = KbSearchMode.normalize(routeDecision.mode);
                if (m == KbSearchMode.auto)
                {
                    m = KbSearchMode.basic;
                }
                // 路由到未开放模式 → basic
                if (modePolicy != null && !modePolicy.isEnabled(m))
                {
                    degraded = true;
                    m = modePolicy.defaultMode();
                }
                log.debug("Auto route -> {} ({})", m, routeDecision.reason);
            }

            List<KbSearchHit> all = new ArrayList<>();
            for (Long kbId : kbIds)
            {
                KbKnowledge kb = loadKb(kbId);
                if (kb == null || !"0".equals(kb.getStatus()))
                {
                    continue;
                }
                try
                {
                    List<KbSearchHit> part = switch (m)
                    {
                        case local -> searchLocal(kb, query, k);
                        case hybrid -> searchHybrid(kb, query, k, min);
                        case global -> searchGlobal(kb, query, k, min);
                        case drift -> searchDrift(kb, query, k, min);
                        default -> searchBasic(kb, query, k, min);
                    };
                    if (routeDecision != null && !part.isEmpty() && part.get(0).getDebugTrace() == null)
                    {
                        part.get(0).setDebugTrace(routeDecision.toDebugJson());
                    }
                    else if (routeDecision != null && !part.isEmpty())
                    {
                        String prev = part.get(0).getDebugTrace();
                        part.get(0).setDebugTrace("{\"auto\":" + routeDecision.toDebugJson()
                            + ",\"inner\":" + prev + "}");
                    }
                    // 检测降级标记
                    if (!part.isEmpty() && part.get(0).getDebugTrace() != null
                        && part.get(0).getDebugTrace().contains("degradeReason"))
                    {
                        degraded = true;
                    }
                    all.addAll(part);
                }
                catch (Exception e)
                {
                    log.warn("检索失败 kbId={} mode={}: {}", kbId, m, e.getMessage());
                    degraded = true;
                    if (m != KbSearchMode.basic)
                    {
                        try
                        {
                            all.addAll(searchBasic(kb, query, k, min));
                        }
                        catch (Exception ex)
                        {
                            failed = true;
                            log.warn("Basic 回退也失败 kbId={}: {}", kbId, ex.getMessage());
                        }
                    }
                    else
                    {
                        failed = true;
                    }
                }
            }
            all.sort(Comparator.comparingDouble(KbSearchHit::getScore).reversed());
            final List<KbSearchHit> ranked = all.size() > k
                ? new ArrayList<>(all.subList(0, k)) : all;
            List<KbSearchHit> top;
            try
            {
                top = DataSourceScope.runOn(DataSourceType.SLAVE,
                    () -> chunkContextExpander.expand(ranked));
            }
            catch (Exception e)
            {
                log.debug("上下文扩展失败,返回原始命中: {}", e.getMessage());
                top = ranked;
            }
            // 剥离 debugTrace（非管理台）
            boolean keep = keepDebugTrace
                && (modePolicy == null || modePolicy.isAdminDebugTrace());
            if (!keep && (modePolicy == null || modePolicy.isStripDebugTrace()))
            {
                for (KbSearchHit h : top)
                {
                    if (h != null)
                    {
                        h.setDebugTrace(null);
                    }
                }
            }
            return top;
        }
        catch (InterruptedException ie)
        {
            Thread.currentThread().interrupt();
            failed = true;
            return List.of();
        }
        finally
        {
            if (acquired && searchSemaphore != null)
            {
                searchSemaphore.release();
            }
            if (searchMetrics != null)
            {
                searchMetrics.record(m != null ? m.name() : "unknown",
                    System.currentTimeMillis() - t0, degraded, failed);
            }
        }
    }

    /** Basic：纯向量叶子检索（兼容旧 vector） */
    private List<KbSearchHit> searchBasic(KbKnowledge kb, String query, int k, double min)
    {
        List<KbSearchHit> hits = searchVectorRaw(kb, query, k, min);
        for (int i = 0; i < hits.size(); i++)
        {
            KbSearchHit h = hits.get(i);
            h.setChannel("basic");
            h.setRankByChannel(i + 1);
            h.setBasicScore(h.getScore());
        }
        return hits;
    }

    private List<KbSearchHit> searchVectorRaw(KbKnowledge kb, String query, int k, double min)
    {
        if (StringUtils.isEmpty(kb.getEmbeddingModelCode()))
        {
            return List.of();
        }
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
            return List.of();
        }
        float[] qv = emb.embed(query);
        if (qv == null || qv.length == 0)
        {
            throw new ServiceException("嵌入模型未返回查询向量");
        }
        return DataSourceScope.runOn(DataSourceType.SLAVE, () ->
            kbVectorStore.search(kb.getKbId(), qv, k, min));
    }

    /**
     * Local：实体名/别名全文 → 1–n 跳扩展 → 证据 chunk → LEAF 命中。
     * Neo4j 不可用时返回空，由上层 hybrid/外层 catch 退回 Basic。
     */
    private List<KbSearchHit> searchLocal(KbKnowledge kb, String query, int k)
    {
        if (!kbGraphStore.isAvailable() || !"1".equals(kb.getGraphEnabled()))
        {
            return List.of();
        }
        LocalCollect collected = collectLocalEvidence(kb.getKbId(), query, k);
        if (collected.chunkIds.isEmpty())
        {
            return List.of();
        }
        List<KbSearchHit> hits = loadHitsFromChunks(
            new ArrayList<>(collected.chunkIds), k, collected.chunkToEntities);
        for (int i = 0; i < hits.size(); i++)
        {
            KbSearchHit h = hits.get(i);
            h.setChannel("local");
            h.setRankByChannel(i + 1);
            h.setLocalScore(h.getScore());
        }
        return hits;
    }

    /**
     * DRIFT：预算内 Local/Hybrid 起点 + 社区上下文 + 规则追问。
     * 失败或空结果退回 Hybrid → Local → Basic。
     */
    private List<KbSearchHit> searchDrift(KbKnowledge kb, String query, int k, double min)
    {
        if (driftSearchService == null)
        {
            return degradeDrift(kb, query, k, min, "drift_bean_missing");
        }
        try
        {
            DriftSearchService.DriftResult dr = driftSearchService.search(
                kb, query, k,
                (q, tk) -> searchLocal(kb, q, tk),
                (q, tk) -> searchHybrid(kb, q, tk, min));
            if (dr.hits != null && !dr.hits.isEmpty())
            {
                return dr.hits;
            }
            String reason = dr.budget != null ? dr.budget.getStopReason() : "empty";
            return degradeDrift(kb, query, k, min, "drift_empty:" + reason);
        }
        catch (Exception e)
        {
            log.warn("DRIFT 异常 kbId={}: {}", kb.getKbId(), e.getMessage());
            return degradeDrift(kb, query, k, min, "drift_error:" + e.getMessage());
        }
    }

    private List<KbSearchHit> degradeDrift(KbKnowledge kb, String query, int k, double min, String reason)
    {
        try
        {
            List<KbSearchHit> hybrid = searchHybrid(kb, query, k, min);
            if (!hybrid.isEmpty())
            {
                stampDegrade(hybrid, reason, "hybrid");
                return hybrid;
            }
        }
        catch (Exception ignored)
        {
        }
        try
        {
            List<KbSearchHit> local = searchLocal(kb, query, k);
            if (!local.isEmpty())
            {
                stampDegrade(local, reason, "local");
                return local;
            }
        }
        catch (Exception ignored)
        {
        }
        List<KbSearchHit> basic = searchBasic(kb, query, k, min);
        stampDegrade(basic, reason, "basic");
        return basic;
    }

    private static void stampDegrade(List<KbSearchHit> hits, String reason, String via)
    {
        if (hits == null || hits.isEmpty())
        {
            return;
        }
        hits.get(0).setDebugTrace("{\"degradeReason\":\""
            + (reason == null ? "" : reason.replace("\"", ""))
            + "\",\"via\":\"" + via + "\"}");
    }

    /**
     * Global：动态社区选择 + 报告 map。
     * 无报告/索引时明确降级：优先 Hybrid（可关），再 Basic。
     */
    private List<KbSearchHit> searchGlobal(KbKnowledge kb, String query, int k, double min)
    {
        if (globalCommunitySearch == null)
        {
            log.debug("Global 组件未装配,降级");
            return degradeFromGlobal(kb, query, k, min, "global_bean_missing");
        }
        try
        {
            GlobalCommunitySearch.GlobalSearchResult gr =
                globalCommunitySearch.searchWithTrace(kb, query, k);
            if (gr.hits != null && !gr.hits.isEmpty())
            {
                return gr.hits;
            }
            String reason = gr.trace != null ? gr.trace.getDegradeReason() : "empty";
            log.info("Global 无结果 kbId={} reason={}, 降级", kb.getKbId(), reason);
            List<KbSearchHit> fallback = degradeFromGlobal(kb, query, k, min, reason);
            if (!fallback.isEmpty() && gr.trace != null)
            {
                // 保留降级原因轨迹
                fallback.get(0).setDebugTrace(gr.trace.toJson());
            }
            return fallback;
        }
        catch (Exception e)
        {
            log.warn("Global 检索异常 kbId={}: {}", kb.getKbId(), e.getMessage());
            return degradeFromGlobal(kb, query, k, min, "global_error:" + e.getMessage());
        }
    }

    private List<KbSearchHit> degradeFromGlobal(KbKnowledge kb, String query, int k, double min,
        String reason)
    {
        if (globalFallbackHybrid)
        {
            try
            {
                List<KbSearchHit> hybrid = searchHybrid(kb, query, k, min);
                if (!hybrid.isEmpty())
                {
                    for (KbSearchHit h : hybrid)
                    {
                        if (h.getChannel() == null)
                        {
                            h.setChannel("hybrid");
                        }
                    }
                    return hybrid;
                }
            }
            catch (Exception e)
            {
                log.debug("Global→Hybrid 降级失败: {}", e.getMessage());
            }
        }
        List<KbSearchHit> basic = searchBasic(kb, query, k, min);
        if (!basic.isEmpty() && reason != null)
        {
            basic.get(0).setDebugTrace("{\"degradeReason\":\"" + reason.replace("\"", "") + "\"}");
        }
        return basic;
    }

    /** Hybrid：Basic + Local 用 RRF 融合（替代不可比分数 * 1.1） */
    private List<KbSearchHit> searchHybrid(KbKnowledge kb, String query, int k, double min)
    {
        List<KbSearchHit> basicHits = List.of();
        try
        {
            basicHits = searchBasic(kb, query, k, min);
        }
        catch (Exception e)
        {
            log.debug("hybrid basic 路失败: {}", e.getMessage());
        }
        List<KbSearchHit> localHits = List.of();
        try
        {
            localHits = searchLocal(kb, query, k);
        }
        catch (Exception e)
        {
            log.debug("hybrid local 路失败,退回 pure basic: {}", e.getMessage());
        }
        if (localHits.isEmpty())
        {
            return basicHits;
        }
        if (basicHits.isEmpty())
        {
            // 仅 local 时标 hybrid 会误导；仍标 local
            return localHits;
        }

        Map<Long, KbSearchHit> basicById = indexByChunk(basicHits);
        Map<Long, KbSearchHit> localById = indexByChunk(localHits);

        List<FusedHit<KbSearchHit>> fused = ReciprocalRankFusion.fuse(
            List.of(basicHits, localHits),
            KbSearchHit::getChunkId,
            rrfK,
            k);

        List<KbSearchHit> out = new ArrayList<>();
        int rank = 1;
        for (FusedHit<KbSearchHit> f : fused)
        {
            KbSearchHit base = f.item;
            Long id = base.getChunkId();
            KbSearchHit h = copyHit(base);
            h.setScore(f.score);
            h.setChannel("hybrid");
            h.setRankByChannel(rank++);
            KbSearchHit b = basicById.get(id);
            KbSearchHit l = localById.get(id);
            if (b != null)
            {
                h.setBasicScore(b.getScore());
            }
            if (l != null)
            {
                h.setLocalScore(l.getScore());
                if (l.getEntityNames() != null && !l.getEntityNames().isEmpty())
                {
                    h.setEntityNames(new ArrayList<>(l.getEntityNames()));
                }
            }
            // 展示内容优先 basic（通常更贴 query），缺则用 local
            if (b != null && StringUtils.isNotEmpty(b.getContent()))
            {
                h.setContent(b.getContent());
                h.setDocName(b.getDocName());
                h.setHeadingPath(b.getHeadingPath());
            }
            out.add(h);
        }
        return out;
    }

    private LocalCollect collectLocalEvidence(Long kbId, String query, int k)
    {
        LocalCollect out = new LocalCollect();
        List<String> terms = keywords(query);
        List<String> queries = new ArrayList<>();
        queries.add(query);
        queries.addAll(terms);

        // 种子实体：全文/CONTAINS，按出现顺序近似相关度
        LinkedHashMap<String, GraphEntity> seeds = new LinkedHashMap<>();
        for (String q : queries)
        {
            if (StringUtils.isEmpty(q))
            {
                continue;
            }
            try
            {
                List<GraphEntity> found = kbGraphStore.searchEntities(kbId, q, Math.max(8, k));
                for (GraphEntity e : found)
                {
                    if (e.getName() != null && !seeds.containsKey(e.getName()))
                    {
                        seeds.put(e.getName(), e);
                    }
                }
            }
            catch (Exception e)
            {
                log.debug("实体搜索失败: {}", e.getMessage());
            }
            if (seeds.size() >= Math.max(10, k))
            {
                break;
            }
        }

        int hops = localHops > 0 ? localHops : KbGraphConstants.DEFAULT_HOPS;
        // 加权：种子实体 sourceIds 优先，邻居次之
        Map<Long, Double> chunkWeight = new LinkedHashMap<>();
        Map<Long, Set<String>> chunkEntities = new HashMap<>();
        int seedRank = 0;
        for (Map.Entry<String, GraphEntity> se : seeds.entrySet())
        {
            seedRank++;
            double seedBoost = 1.0 / seedRank;
            addEntitySources(se.getValue(), se.getKey(), chunkWeight, chunkEntities, 1.0 + seedBoost);
            try
            {
                List<GraphEntity> neigh = kbGraphStore.neighbors(kbId, se.getKey(),
                    hops, KbGraphConstants.DEFAULT_NEIGHBOR_LIMIT);
                int nRank = 0;
                for (GraphEntity e : neigh)
                {
                    if (e == null || e.getName() == null)
                    {
                        continue;
                    }
                    if (se.getKey().equals(e.getName()))
                    {
                        continue; // 自身已计
                    }
                    nRank++;
                    addEntitySources(e, e.getName(), chunkWeight, chunkEntities,
                        0.5 * seedBoost / nRank);
                }
            }
            catch (Exception e)
            {
                log.debug("邻居扩展失败 name={}: {}", se.getKey(), e.getMessage());
            }
            if (chunkWeight.size() >= k * 6)
            {
                break;
            }
        }

        // 按权重排序 chunk
        List<Map.Entry<Long, Double>> sorted = new ArrayList<>(chunkWeight.entrySet());
        sorted.sort(Map.Entry.<Long, Double>comparingByValue().reversed());
        for (Map.Entry<Long, Double> e : sorted)
        {
            out.chunkIds.add(e.getKey());
            Set<String> ents = chunkEntities.get(e.getKey());
            if (ents != null)
            {
                out.chunkToEntities.put(e.getKey(), new ArrayList<>(ents));
            }
            if (out.chunkIds.size() >= k * 4)
            {
                break;
            }
        }
        return out;
    }

    private static void addEntitySources(GraphEntity e, String name,
        Map<Long, Double> chunkWeight, Map<Long, Set<String>> chunkEntities, double weight)
    {
        if (e == null || e.getSourceIds() == null)
        {
            return;
        }
        for (Long sid : e.getSourceIds())
        {
            if (sid == null || sid <= 0)
            {
                continue;
            }
            chunkWeight.merge(sid, weight, Double::sum);
            chunkEntities.computeIfAbsent(sid, x -> new LinkedHashSet<>()).add(name);
        }
    }

    private List<KbSearchHit> loadHitsFromChunks(List<Long> chunkIds, int k,
        Map<Long, List<String>> chunkToEntities)
    {
        if (chunkIds.isEmpty())
        {
            return List.of();
        }
        List<Long> limited = chunkIds.size() > 100 ? chunkIds.subList(0, 100) : chunkIds;
        List<KbChunk> chunks = DataSourceScope.runOn(DataSourceType.SLAVE,
            () -> kbChunkMapper.selectByChunkIds(limited));
        if (chunks == null || chunks.isEmpty())
        {
            return List.of();
        }
        Map<Long, Integer> order = new HashMap<>();
        for (int i = 0; i < limited.size(); i++)
        {
            order.put(limited.get(i), i);
        }
        chunks.sort(Comparator.comparingInt(c -> order.getOrDefault(c.getChunkId(), Integer.MAX_VALUE)));
        List<KbSearchHit> hits = new ArrayList<>();
        for (KbChunk c : chunks)
        {
            if (c == null || (c.getChunkLevel() != null && !ChunkLevels.isLeaf(c.getChunkLevel())))
            {
                continue;
            }
            KbSearchHit h = new KbSearchHit();
            h.setChunkId(c.getChunkId());
            h.setKbId(c.getKbId());
            h.setDocId(c.getDocId());
            h.setDocName(c.getDocName());
            h.setHeadingPath(c.getHeadingPath());
            h.setChunkIndex(c.getChunkIndex());
            h.setContent(c.getContent());
            h.setParentChunkId(c.getParentChunkId());
            int i = hits.size();
            h.setScore(KbGraphConstants.GRAPH_BASE_SCORE * (1.0 - i * 0.02));
            if (chunkToEntities != null)
            {
                List<String> ents = chunkToEntities.get(c.getChunkId());
                if (ents != null)
                {
                    h.setEntityNames(new ArrayList<>(ents));
                }
            }
            hits.add(h);
            if (hits.size() >= k)
            {
                break;
            }
        }
        return hits;
    }

    private static Map<Long, KbSearchHit> indexByChunk(List<KbSearchHit> hits)
    {
        Map<Long, KbSearchHit> m = new LinkedHashMap<>();
        for (KbSearchHit h : hits)
        {
            if (h.getChunkId() != null)
            {
                m.putIfAbsent(h.getChunkId(), h);
            }
        }
        return m;
    }

    private static KbSearchHit copyHit(KbSearchHit src)
    {
        KbSearchHit h = new KbSearchHit();
        h.setChunkId(src.getChunkId());
        h.setKbId(src.getKbId());
        h.setDocId(src.getDocId());
        h.setDocName(src.getDocName());
        h.setHeadingPath(src.getHeadingPath());
        h.setChunkIndex(src.getChunkIndex());
        h.setContent(src.getContent());
        h.setScore(src.getScore());
        h.setChannel(src.getChannel());
        h.setRankByChannel(src.getRankByChannel());
        h.setBasicScore(src.getBasicScore());
        h.setLocalScore(src.getLocalScore());
        h.setParentChunkId(src.getParentChunkId());
        if (src.getEntityNames() != null)
        {
            h.setEntityNames(new ArrayList<>(src.getEntityNames()));
        }
        return h;
    }

    /** 简单分词:空白/标点切,保留长度>=2 的片段 */
    static List<String> keywords(String query)
    {
        List<String> out = new ArrayList<>();
        if (query == null)
        {
            return out;
        }
        for (String p : SPLIT.split(query))
        {
            String t = p.trim();
            if (t.length() >= 2)
            {
                out.add(t);
            }
        }
        String compact = query.replaceAll("\\s+", "");
        if (out.isEmpty() && compact.length() >= 2)
        {
            int max = Math.min(compact.length() - 1, 6);
            for (int i = 0; i < max; i++)
            {
                int end = Math.min(i + 3, compact.length());
                if (end - i >= 2)
                {
                    out.add(compact.substring(i, end));
                }
            }
        }
        return out;
    }

    private KbKnowledge loadKb(Long kbId)
    {
        return DataSourceScope.runOn(DataSourceType.SLAVE, () ->
            kbKnowledgeMapper.selectKbKnowledgeById(kbId));
    }

    public String formatForModel(List<KbSearchHit> hits)
    {
        if (hits == null || hits.isEmpty())
        {
            return "未检索到相关知识片段。";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hits.size(); i++)
        {
            KbSearchHit h = hits.get(i);
            sb.append('[').append(i + 1).append("] 《")
                .append(h.getDocName() != null ? h.getDocName() : "未知文档")
                .append('》');
            if (StringUtils.isNotEmpty(h.getHeadingPath()))
            {
                sb.append(' ').append(h.getHeadingPath());
            }
            if (StringUtils.isNotEmpty(h.getChannel()))
            {
                sb.append(" (").append(h.getChannel()).append(')');
            }
            sb.append('\n');
            sb.append("    ").append(h.getContent() != null ? h.getContent().replace("\n", "\n    ") : "");
            sb.append("\n\n");
        }
        return sb.toString().trim();
    }

    private static final class LocalCollect
    {
        final LinkedHashSet<Long> chunkIds = new LinkedHashSet<>();
        final Map<Long, List<String>> chunkToEntities = new HashMap<>();
    }
}
