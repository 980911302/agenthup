package com.ruoyi.system.kb.graph;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.common.annotation.DataSource;
import com.ruoyi.common.enums.DataSourceType;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.domain.KbChunk;
import com.ruoyi.system.domain.KbGraphEntityCommunity;
import com.ruoyi.system.domain.KbGraphIndex;
import com.ruoyi.system.domain.KbKnowledge;
import com.ruoyi.system.kb.KbGraphExploreAssembler;
import com.ruoyi.system.mapper.KbChunkMapper;
import com.ruoyi.system.mapper.KbGraphCommunityMapper;
import com.ruoyi.system.mapper.KbKnowledgeMapper;

/**
 * 受控子图探索：禁止无界全库加载，服务端节点/边上限。
 */
@Service
@DataSource(DataSourceType.SLAVE)
public class KbGraphExploreService
{
    private static final Logger log = LoggerFactory.getLogger(KbGraphExploreService.class);
    private static final int MAX_SEEDS = 12;

    @Autowired
    private KbKnowledgeMapper kbKnowledgeMapper;
    @Autowired
    private KbGraphCommunityMapper communityMapper;
    @Autowired
    private KbChunkMapper kbChunkMapper;
    @Autowired
    private KbGraphStore kbGraphStore;

    public Map<String, Object> explore(Long kbId, Map<String, Object> params)
    {
        if (params == null)
        {
            params = Map.of();
        }
        KbKnowledge kb = kbKnowledgeMapper.selectKbKnowledgeById(kbId);
        if (kb == null)
        {
            throw new ServiceException("知识库不存在");
        }

        int nodeLimit = KbGraphExploreAssembler.clampNodes(asInt(params.get("limit")));
        int edgeLimit = KbGraphExploreAssembler.clampEdges(asInt(params.get("edgeLimit")));
        int depth = KbGraphExploreAssembler.clampDepth(asInt(params.get("depth")));
        String query = asStr(params.get("query"));
        Long communityId = asLong(params.get("communityId"));
        Set<String> entityTypes = asStringSet(params.get("entityTypes"));
        Set<String> relationTypes = asStringSet(params.get("relationTypes"));
        // 单文件 / 多文件范围：用文档下 chunkId 与实体 sourceIds 求交
        Set<Long> docIds = asLongSet(params.get("docIds"));
        // 兼容单个 docId
        Long singleDoc = asLong(params.get("docId"));
        if (singleDoc != null)
        {
            docIds = new HashSet<>(docIds);
            docIds.add(singleDoc);
        }
        List<String> seedNames = asStringList(params.get("seedNames"));
        Set<Long> chunkFilter = resolveChunkIdsForDocs(docIds);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nodeLimit", nodeLimit);
        out.put("edgeLimit", edgeLimit);
        out.put("depth", depth);
        out.put("docIds", docIds == null || docIds.isEmpty() ? List.of() : new ArrayList<>(docIds));
        out.put("scope", chunkFilter.isEmpty() ? "kb" : "doc");

        boolean enabled = "1".equals(kb.getGraphEnabled());
        boolean available = kbGraphStore != null && kbGraphStore.isAvailable();
        out.put("graphEnabled", enabled);
        out.put("available", enabled && available);

        if (!enabled)
        {
            out.put("userMessage", "本库未启用知识图谱");
            out.put("nodes", List.of());
            out.put("edges", List.of());
            out.put("truncated", false);
            return out;
        }
        if (!available)
        {
            out.put("userMessage", "知识图谱服务暂时不可用");
            out.put("nodes", List.of());
            out.put("edges", List.of());
            out.put("truncated", false);
            return out;
        }
        if (docIds != null && !docIds.isEmpty() && chunkFilter.isEmpty())
        {
            out.put("userMessage", "该文件尚无切片，无法构建图谱（请先完成文档处理）");
            out.put("nodes", List.of());
            out.put("edges", List.of());
            out.put("truncated", false);
            return out;
        }

        List<GraphEntity> seeds = resolveSeeds(kbId, query, communityId, seedNames, nodeLimit, chunkFilter);
        if (seeds.isEmpty())
        {
            out.put("userMessage", StringUtils.isNotEmpty(query)
                ? "未找到匹配实体，请换个关键词"
                : (chunkFilter.isEmpty()
                    ? "暂无可用种子节点，请先完成图谱抽取"
                    : "该文件尚未抽取出实体/关系，请确认已开启图谱并完成处理"));
            out.put("nodes", List.of());
            out.put("edges", List.of());
            out.put("truncated", false);
            out.put("seedCount", 0);
            return out;
        }

        Map<String, GraphEntity> nodeMap = new LinkedHashMap<>();
        for (GraphEntity s : seeds)
        {
            if (s == null || StringUtils.isEmpty(s.getName()))
            {
                continue;
            }
            if (!KbGraphExploreAssembler.typeAllowed(s, entityTypes))
            {
                continue;
            }
            if (!chunkFilterOk(s.getSourceIds(), chunkFilter))
            {
                continue;
            }
            nodeMap.putIfAbsent(s.getName(), s);
        }

        // 扩展邻居（限定文档范围时仅保留本文件证据的实体，形成「单文件子图」）
        int perSeed = Math.max(8, nodeLimit / Math.max(1, Math.min(seeds.size(), MAX_SEEDS)));
        boolean truncated = false;
        for (GraphEntity s : seeds)
        {
            if (s == null || StringUtils.isEmpty(s.getName()))
            {
                continue;
            }
            if (nodeMap.size() >= nodeLimit)
            {
                truncated = true;
                break;
            }
            try
            {
                List<GraphEntity> neigh = kbGraphStore.neighbors(kbId, s.getName(), depth, perSeed);
                for (GraphEntity n : neigh)
                {
                    if (n == null || StringUtils.isEmpty(n.getName()))
                    {
                        continue;
                    }
                    if (!KbGraphExploreAssembler.typeAllowed(n, entityTypes))
                    {
                        continue;
                    }
                    if (!chunkFilterOk(n.getSourceIds(), chunkFilter))
                    {
                        continue;
                    }
                    if (nodeMap.size() >= nodeLimit)
                    {
                        truncated = true;
                        break;
                    }
                    nodeMap.putIfAbsent(n.getName(), n);
                }
            }
            catch (Exception e)
            {
                log.debug("邻居扩展失败 {}: {}", s.getName(), e.getMessage());
            }
        }

        List<String> names = new ArrayList<>(nodeMap.keySet());
        List<GraphRelation> rels = List.of();
        try
        {
            rels = kbGraphStore.relationsAmong(kbId, names, edgeLimit + 20);
        }
        catch (Exception e)
        {
            log.debug("子图关系失败: {}", e.getMessage());
        }

        List<Map<String, Object>> nodes = new ArrayList<>();
        for (GraphEntity e : nodeMap.values())
        {
            nodes.add(KbGraphExploreAssembler.productNode(e));
        }

        List<Map<String, Object>> edges = new ArrayList<>();
        int ei = 0;
        for (GraphRelation r : rels)
        {
            if (!KbGraphExploreAssembler.relationAllowed(r, relationTypes))
            {
                continue;
            }
            if (!nodeMap.containsKey(r.getSourceName()) || !nodeMap.containsKey(r.getTargetName()))
            {
                continue;
            }
            // 单文件：两端实体已限定本文件；边有证据时再优先本文件证据（无证据的边仍保留以连线）
            if (edges.size() >= edgeLimit)
            {
                truncated = true;
                break;
            }
            edges.add(KbGraphExploreAssembler.productEdge(r, ei++));
        }

        out.put("nodes", nodes);
        out.put("edges", edges);
        out.put("truncated", truncated || nodeMap.size() >= nodeLimit || edges.size() >= edgeLimit);
        out.put("seedCount", seeds.size());
        String scopeHint = chunkFilter.isEmpty() ? "全库" : "本文件";
        out.put("userMessage", truncated
            ? (scopeHint + "子图已截断（节点≤" + nodeLimit + "，边≤" + edgeLimit + "），请缩小范围或降低跳数")
            : (scopeHint + "子图 " + nodes.size() + " 节点 / " + edges.size() + " 边"));
        return out;
    }

    public Map<String, Object> entityDetail(Long kbId, String name)
    {
        if (StringUtils.isEmpty(name))
        {
            throw new ServiceException("实体名称不能为空");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (kbGraphStore == null || !kbGraphStore.isAvailable())
        {
            out.put("available", false);
            out.put("userMessage", "知识图谱服务暂时不可用");
            return out;
        }
        List<GraphEntity> found = kbGraphStore.findEntitiesByNames(kbId, List.of(name));
        if (found == null || found.isEmpty())
        {
            // 尝试搜索
            found = kbGraphStore.searchEntities(kbId, name, 5);
        }
        GraphEntity e = null;
        if (found != null)
        {
            // 优先取带描述的候选：同名节点可能因 entityKey 分裂(类型段缺失)存在空壳，
            // 空壳没有简介，先拿完整节点，避免详情页显示「暂无描述」。
            GraphEntity fallback = null;
            for (GraphEntity cand : found)
            {
                if (cand == null || !name.equals(cand.getName()))
                {
                    continue;
                }
                if (fallback == null)
                {
                    fallback = cand;
                }
                if (cand.getDescription() != null && !cand.getDescription().isBlank())
                {
                    e = cand;
                    break;
                }
            }
            if (e == null && fallback != null)
            {
                e = fallback;
            }
            if (e == null && !found.isEmpty())
            {
                e = found.get(0);
            }
        }
        if (e == null)
        {
            out.put("available", true);
            out.put("found", false);
            out.put("userMessage", "未找到实体");
            return out;
        }
        out.put("available", true);
        out.put("found", true);
        out.put("entity", KbGraphExploreAssembler.productNode(e));
        out.put("sources", resolveChunkSources(e.getSourceIds(), 20));

        // 1 跳关系样本
        List<GraphRelation> rels = kbGraphStore.relationsAmong(kbId, List.of(e.getName()), 50);
        // 扩展：用邻居名取关系
        List<GraphEntity> neigh = kbGraphStore.neighbors(kbId, e.getName(), 1, 30);
        List<String> names = new ArrayList<>();
        names.add(e.getName());
        for (GraphEntity n : neigh)
        {
            if (n != null && StringUtils.isNotEmpty(n.getName()))
            {
                names.add(n.getName());
            }
        }
        rels = kbGraphStore.relationsAmong(kbId, names, 40);
        List<Map<String, Object>> relOut = new ArrayList<>();
        int i = 0;
        for (GraphRelation r : rels)
        {
            if (r == null)
            {
                continue;
            }
            if (!name.equals(r.getSourceName()) && !name.equals(r.getTargetName()))
            {
                continue;
            }
            Map<String, Object> m = KbGraphExploreAssembler.productEdge(r, i++);
            m.put("direction", name.equals(r.getSourceName()) ? "out" : "in");
            relOut.add(m);
            if (relOut.size() >= 25)
            {
                break;
            }
        }
        out.put("relations", relOut);

        // 所属主题（社区）
        out.put("themes", resolveThemesForEntity(kbId, e.getName(), e.getEntityKey()));
        return out;
    }

    public Map<String, Object> relationDetail(Long kbId, String source, String target, String label)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        if (kbGraphStore == null || !kbGraphStore.isAvailable())
        {
            out.put("available", false);
            return out;
        }
        if (StringUtils.isEmpty(source) || StringUtils.isEmpty(target))
        {
            throw new ServiceException("关系两端不能为空");
        }
        List<GraphRelation> rels = kbGraphStore.relationsAmong(kbId, List.of(source, target), 50);
        GraphRelation hit = null;
        for (GraphRelation r : rels)
        {
            if (r == null)
            {
                continue;
            }
            boolean ends = source.equals(r.getSourceName()) && target.equals(r.getTargetName());
            if (!ends)
            {
                continue;
            }
            if (StringUtils.isEmpty(label))
            {
                hit = r;
                break;
            }
            String lab = (r.getKeywords() == null ? "" : r.getKeywords())
                + " " + (r.getPredicate() == null ? "" : r.getPredicate());
            if (lab.toLowerCase(Locale.ROOT).contains(label.toLowerCase(Locale.ROOT)))
            {
                hit = r;
                break;
            }
            if (hit == null)
            {
                hit = r;
            }
        }
        if (hit == null)
        {
            out.put("found", false);
            return out;
        }
        out.put("found", true);
        out.put("relation", KbGraphExploreAssembler.productEdge(hit, 0));
        out.put("sources", resolveChunkSources(hit.getSourceIds(), 20));
        return out;
    }

    private List<GraphEntity> resolveSeeds(Long kbId, String query, Long communityId,
                                           List<String> seedNames, int nodeLimit, Set<Long> chunkFilter)
    {
        List<GraphEntity> seeds = new ArrayList<>();
        if (seedNames != null && !seedNames.isEmpty())
        {
            seeds.addAll(kbGraphStore.findEntitiesByNames(kbId, seedNames));
        }
        if (StringUtils.isNotEmpty(query))
        {
            seeds.addAll(kbGraphStore.searchEntities(kbId, query, MAX_SEEDS * 2));
        }
        if (communityId != null)
        {
            seeds.addAll(entitiesInCommunity(kbId, communityId, MAX_SEEDS * 2));
        }
        if (seeds.isEmpty())
        {
            // 默认：来源最多的实体作为入口
            if (chunkFilter != null && !chunkFilter.isEmpty())
            {
                // 单文件：直接按 chunk 反查，避免 sourceIds 截断 + 全库扫名漏种子
                seeds = new ArrayList<>(kbGraphStore.listEntitiesBySourceChunks(
                    kbId, chunkFilter, Math.min(200, Math.max(40, nodeLimit * 3))));
            }
            else
            {
                List<GraphEntity> top = kbGraphStore.listEntities(kbId, Math.min(40, nodeLimit));
                seeds = new ArrayList<>(top);
            }
        }
        // 文档范围：种子若来自搜索/指定名，仍要滤到本文件证据
        if (chunkFilter != null && !chunkFilter.isEmpty()
            && (seedNames != null && !seedNames.isEmpty()
                || StringUtils.isNotEmpty(query)
                || communityId != null))
        {
            List<GraphEntity> filtered = new ArrayList<>();
            for (GraphEntity e : seeds)
            {
                if (e != null && chunkFilterOk(e.getSourceIds(), chunkFilter))
                {
                    filtered.add(e);
                }
            }
            // 搜索命中但 sourceIds 缓存未含本文件 chunk 时，并上 chunk 反查结果
            if (filtered.isEmpty())
            {
                filtered.addAll(kbGraphStore.listEntitiesBySourceChunks(
                    kbId, chunkFilter, Math.min(80, nodeLimit * 2)));
            }
            seeds = filtered;
        }
        // 去重 + 截断种子数
        Map<String, GraphEntity> uniq = new LinkedHashMap<>();
        for (GraphEntity e : seeds)
        {
            if (e != null && StringUtils.isNotEmpty(e.getName()))
            {
                uniq.putIfAbsent(e.getName(), e);
            }
        }
        return KbGraphExploreAssembler.pickSeeds(new ArrayList<>(uniq.values()), MAX_SEEDS);
    }

    /** 文档 → chunkId 集合，用于与实体/关系 sourceIds 求交 */
    private Set<Long> resolveChunkIdsForDocs(Set<Long> docIds)
    {
        Set<Long> chunkIds = new HashSet<>();
        if (docIds == null || docIds.isEmpty())
        {
            return chunkIds;
        }
        for (Long docId : docIds)
        {
            if (docId == null)
            {
                continue;
            }
            try
            {
                List<Long> ids = kbChunkMapper.selectChunkIdsByDocId(docId);
                if (ids != null)
                {
                    chunkIds.addAll(ids);
                }
            }
            catch (Exception e)
            {
                log.debug("加载文档 chunkId 失败 docId={}: {}", docId, e.getMessage());
            }
        }
        return chunkIds;
    }

    /**
     * chunk 范围过滤：无范围则放行；有范围时 sourceIds 须与文档 chunk 有交集。
     * 无 sourceIds 的实体在「单文件」视图下剔除（避免串文件）。
     */
    private static boolean chunkFilterOk(List<Long> sourceIds, Set<Long> chunkFilter)
    {
        if (chunkFilter == null || chunkFilter.isEmpty())
        {
            return true;
        }
        if (sourceIds == null || sourceIds.isEmpty())
        {
            return false;
        }
        for (Long id : sourceIds)
        {
            if (id != null && chunkFilter.contains(id))
            {
                return true;
            }
        }
        return false;
    }

    private List<GraphEntity> entitiesInCommunity(Long kbId, Long communityId, int limit)
    {
        KbGraphIndex idx = communityMapper.selectIndex(kbId);
        if (idx == null || StringUtils.isEmpty(idx.getGraphVersion()))
        {
            return List.of();
        }
        List<KbGraphEntityCommunity> members = communityMapper.selectEntityCommunities(
            kbId, idx.getGraphVersion());
        if (members == null || members.isEmpty())
        {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (KbGraphEntityCommunity m : members)
        {
            if (m == null || m.getCommunityId() == null)
            {
                continue;
            }
            if (!communityId.equals(m.getCommunityId()))
            {
                continue;
            }
            if (StringUtils.isNotEmpty(m.getEntityName()))
            {
                names.add(m.getEntityName());
            }
            if (names.size() >= limit)
            {
                break;
            }
        }
        if (names.isEmpty())
        {
            return List.of();
        }
        return kbGraphStore.findEntitiesByNames(kbId, names);
    }

    private List<Map<String, Object>> resolveThemesForEntity(Long kbId, String name, String entityKey)
    {
        List<Map<String, Object>> themes = new ArrayList<>();
        try
        {
            KbGraphIndex idx = communityMapper.selectIndex(kbId);
            if (idx == null || StringUtils.isEmpty(idx.getGraphVersion()))
            {
                return themes;
            }
            List<KbGraphEntityCommunity> members = communityMapper.selectEntityCommunities(
                kbId, idx.getGraphVersion());
            if (members == null)
            {
                return themes;
            }
            Set<Long> seen = new HashSet<>();
            for (KbGraphEntityCommunity m : members)
            {
                if (m == null || m.getCommunityId() == null)
                {
                    continue;
                }
                boolean match = (name != null && name.equals(m.getEntityName()))
                    || (entityKey != null && entityKey.equals(m.getEntityKey()));
                if (!match || !seen.add(m.getCommunityId()))
                {
                    continue;
                }
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("communityId", m.getCommunityId());
                t.put("level", m.getLevel());
                t.put("title", "主题 " + m.getCommunityId());
                themes.add(t);
                if (themes.size() >= 5)
                {
                    break;
                }
            }
        }
        catch (Exception e)
        {
            log.debug("解析实体主题失败: {}", e.getMessage());
        }
        return themes;
    }

    private List<Map<String, Object>> resolveChunkSources(List<Long> chunkIds, int max)
    {
        List<Map<String, Object>> out = new ArrayList<>();
        if (chunkIds == null || chunkIds.isEmpty())
        {
            return out;
        }
        List<Long> ids = chunkIds.size() > max ? chunkIds.subList(0, max) : chunkIds;
        try
        {
            List<KbChunk> chunks = kbChunkMapper.selectByChunkIds(new ArrayList<>(ids));
            if (chunks == null)
            {
                return out;
            }
            for (KbChunk c : chunks)
            {
                if (c == null)
                {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("chunkId", c.getChunkId());
                m.put("docId", c.getDocId());
                m.put("docName", c.getDocName());
                m.put("headingPath", c.getHeadingPath());
                m.put("sourcePageFrom", c.getSourcePageFrom());
                m.put("sourcePageTo", c.getSourcePageTo());
                m.put("sourceLabel", c.getSourceLabel());
                String content = c.getContent();
                if (content != null && content.length() > 280)
                {
                    content = content.substring(0, 280) + "…";
                }
                m.put("snippet", content);
                out.add(m);
            }
        }
        catch (Exception e)
        {
            log.debug("解析来源切片失败: {}", e.getMessage());
        }
        return out;
    }

    private static Integer asInt(Object o)
    {
        if (o instanceof Number n)
        {
            return n.intValue();
        }
        if (o != null)
        {
            try
            {
                return Integer.parseInt(o.toString());
            }
            catch (Exception ignored)
            {
            }
        }
        return null;
    }

    private static Long asLong(Object o)
    {
        if (o instanceof Number n)
        {
            return n.longValue();
        }
        if (o != null && StringUtils.isNotEmpty(o.toString()))
        {
            try
            {
                return Long.parseLong(o.toString());
            }
            catch (Exception ignored)
            {
            }
        }
        return null;
    }

    private static String asStr(Object o)
    {
        return o == null ? null : o.toString().trim();
    }

    @SuppressWarnings("unchecked")
    private static Set<String> asStringSet(Object o)
    {
        Set<String> set = new HashSet<>();
        if (o instanceof List<?> list)
        {
            for (Object x : list)
            {
                if (x != null && StringUtils.isNotEmpty(x.toString()))
                {
                    set.add(x.toString().trim());
                }
            }
        }
        else if (o instanceof String s && StringUtils.isNotEmpty(s))
        {
            for (String p : s.split(","))
            {
                if (StringUtils.isNotEmpty(p.trim()))
                {
                    set.add(p.trim());
                }
            }
        }
        return set;
    }

    @SuppressWarnings("unchecked")
    private static Set<Long> asLongSet(Object o)
    {
        Set<Long> set = new HashSet<>();
        if (o instanceof List<?> list)
        {
            for (Object x : list)
            {
                Long v = asLong(x);
                if (v != null)
                {
                    set.add(v);
                }
            }
        }
        return set;
    }

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Object o)
    {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> list)
        {
            for (Object x : list)
            {
                if (x != null && StringUtils.isNotEmpty(x.toString()))
                {
                    out.add(x.toString().trim());
                }
            }
        }
        return out;
    }
}
