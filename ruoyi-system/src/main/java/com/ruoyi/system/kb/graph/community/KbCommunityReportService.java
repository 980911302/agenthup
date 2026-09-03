package com.ruoyi.system.kb.graph.community;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.ruoyi.common.datasource.DataSourceScope;
import com.ruoyi.common.enums.DataSourceType;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.domain.KbChunk;
import com.ruoyi.system.domain.KbGraphCommunity;
import com.ruoyi.system.domain.KbGraphCommunityReport;
import com.ruoyi.system.domain.KbGraphCommunityReportSource;
import com.ruoyi.system.domain.KbGraphEntityCommunity;
import com.ruoyi.system.domain.KbKnowledge;
import com.ruoyi.system.kb.chunker.TokenCounter;
import com.ruoyi.system.kb.graph.GraphEntity;
import com.ruoyi.system.kb.graph.GraphRelation;
import com.ruoyi.system.kb.graph.KbGraphStore;
import com.ruoyi.system.kb.graph.community.ExtractiveCommunityReportBuilder.EvidenceSnippet;
import com.ruoyi.system.mapper.KbChunkMapper;
import com.ruoyi.system.mapper.KbGraphCommunityMapper;
import com.ruoyi.system.mapper.KbKnowledgeMapper;

/**
 * 自底向上社区报告生成：extractive 默认可复现；可选 LLM 增强；失败单社区隔离。
 */
@Service
public class KbCommunityReportService
{
    private static final Logger log = LoggerFactory.getLogger(KbCommunityReportService.class);
    public static final String REPORT_VERSION = "report-v1";

    @Autowired
    private KbGraphCommunityMapper communityMapper;
    @Autowired
    private KbGraphStore kbGraphStore;
    @Autowired
    private KbChunkMapper kbChunkMapper;
    @Autowired
    private KbKnowledgeMapper kbKnowledgeMapper;
    @Autowired(required = false)
    private CommunityReportLlmGenerator llmGenerator;
    @Autowired(required = false)
    private CommunityReportVectorStore reportVectorStore;

    @Value("${ai.kb.graph.community.report-enabled:true}")
    private boolean reportEnabled;

    @Value("${ai.kb.graph.community.report-use-llm:false}")
    private boolean reportUseLlm;

    @Value("${ai.kb.graph.community.report-embed:true}")
    private boolean reportEmbed;

    @Value("${ai.kb.graph.community.report-max-input-tokens:4000}")
    private int maxInputTokens;

    @Value("${ai.kb.graph.community.report-max-evidence:12}")
    private int maxEvidence;

    /**
     * 在社区检测落库后调用。返回成功报告数。
     */
    public int generateReports(Long kbId, String graphVersion)
    {
        if (!reportEnabled || kbId == null || StringUtils.isEmpty(graphVersion))
        {
            return 0;
        }
        List<KbGraphCommunity> communities = DataSourceScope.runOn(DataSourceType.SLAVE,
            () -> communityMapper.selectCommunities(kbId, graphVersion));
        List<KbGraphEntityCommunity> memberships = DataSourceScope.runOn(DataSourceType.SLAVE,
            () -> communityMapper.selectEntityCommunities(kbId, graphVersion));
        if (communities == null || communities.isEmpty())
        {
            return 0;
        }

        // 清理旧报告
        DataSourceScope.runOn(DataSourceType.SLAVE, () -> {
            communityMapper.deleteReportSourcesByKb(kbId);
            communityMapper.deleteReportsByKb(kbId);
        });
        if (reportVectorStore != null)
        {
            try
            {
                reportVectorStore.deleteByKb(kbId);
            }
            catch (Exception e)
            {
                log.debug("清理社区报告向量失败: {}", e.getMessage());
            }
        }

        Map<String, GraphEntity> entityByName = loadEntityIndex(kbId);
        List<GraphRelation> allRels = safeListRelations(kbId);
        Map<Long, String> chunkTexts = new HashMap<>();

        // memberships: level|communityId -> entity keys/names
        Map<String, List<KbGraphEntityCommunity>> byComm = new HashMap<>();
        for (KbGraphEntityCommunity m : memberships != null ? memberships : List.<KbGraphEntityCommunity>of())
        {
            if (m == null || m.getLevel() == null || m.getCommunityId() == null)
            {
                continue;
            }
            String k = m.getLevel() + "|" + m.getCommunityId();
            byComm.computeIfAbsent(k, x -> new ArrayList<>()).add(m);
        }

        // childrenOf key = parent "level|communityId"
        Map<String, List<KbGraphCommunity>> childrenOf = new HashMap<>();
        for (KbGraphCommunity c : communities)
        {
            if (c.getParentCommunityId() == null || c.getLevel() == null)
            {
                continue;
            }
            String parentKey = findCommunityKey(communities, c.getParentCommunityId(), c.getLevel() + 1);
            if (parentKey != null)
            {
                childrenOf.computeIfAbsent(parentKey, x -> new ArrayList<>()).add(c);
            }
        }

        // bottom-up by level
        List<KbGraphCommunity> ordered = new ArrayList<>(communities);
        ordered.sort(Comparator
            .comparing((KbGraphCommunity c) -> c.getLevel() == null ? 0 : c.getLevel())
            .thenComparing(c -> c.getCommunityId() == null ? 0L : c.getCommunityId()));

        Map<String, CommunityReportDraft> drafts = new LinkedHashMap<>();
        int ok = 0;
        int fail = 0;
        String modelCode = resolveModelCode(kbId);

        for (KbGraphCommunity community : ordered)
        {
            String ck = community.getLevel() + "|" + community.getCommunityId();
            try
            {
                CommunityReportDraft draft = buildOne(
                    kbId, graphVersion, community, byComm.getOrDefault(ck, List.of()),
                    childrenOf.getOrDefault(ck, List.of()), drafts,
                    entityByName, allRels, chunkTexts, modelCode);
                drafts.put(ck, draft);
                persistDraft(draft);
                if ("READY".equals(draft.getStatus()) || "PARTIAL".equals(draft.getStatus()))
                {
                    ok++;
                    maybeEmbed(kbId, draft);
                }
                else
                {
                    fail++;
                }
            }
            catch (Exception e)
            {
                fail++;
                log.warn("社区报告失败 kbId={} community={}: {}", kbId, ck, e.getMessage());
                CommunityReportDraft failed = new CommunityReportDraft();
                failed.setKbId(kbId);
                failed.setGraphVersion(graphVersion);
                failed.setLevel(community.getLevel() != null ? community.getLevel() : 0);
                failed.setCommunityId(community.getCommunityId() != null ? community.getCommunityId() : 0L);
                failed.setTitle("报告失败 L" + community.getLevel() + "-" + community.getCommunityId());
                failed.setSummary("生成失败: " + e.getMessage());
                failed.setFullContent(failed.getSummary());
                failed.setFindingsJson("[]");
                failed.setStatus("FAILED");
                failed.setIncomplete(true);
                failed.setContentHash(ExtractiveCommunityReportBuilder.sha256(
                    ck + "|failed|" + CommunityReportPrompt.PROMPT_VERSION));
                failed.setPromptVersion(CommunityReportPrompt.PROMPT_VERSION);
                failed.setModelCode("none");
                drafts.put(ck, failed);
                try
                {
                    persistDraft(failed);
                }
                catch (Exception persistEx)
                {
                    log.debug("失败报告落库也失败: {}", persistEx.getMessage());
                }
            }
        }
        log.info("社区报告完成 kbId={} gv={} ok={} fail={}", kbId, graphVersion, ok, fail);
        return ok;
    }

    private CommunityReportDraft buildOne(
        Long kbId, String graphVersion, KbGraphCommunity community,
        List<KbGraphEntityCommunity> members, List<KbGraphCommunity> childComms,
        Map<String, CommunityReportDraft> drafts,
        Map<String, GraphEntity> entityByName, List<GraphRelation> allRels,
        Map<Long, String> chunkTexts, String modelCode)
    {
        int level = community.getLevel() != null ? community.getLevel() : 0;
        long cid = community.getCommunityId() != null ? community.getCommunityId() : 0L;

        List<GraphEntity> entities = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (KbGraphEntityCommunity m : members)
        {
            String name = m.getEntityName();
            if (name == null || name.isBlank())
            {
                continue;
            }
            names.add(name);
            GraphEntity ge = entityByName.get(name.toLowerCase());
            if (ge == null)
            {
                ge = new GraphEntity();
                ge.setKbId(kbId);
                ge.setName(name);
                ge.setEntityKey(m.getEntityKey());
            }
            entities.add(ge);
        }

        List<GraphRelation> rels = allRels.stream()
            .filter(r -> r != null && r.getSourceName() != null && r.getTargetName() != null)
            .filter(r -> names.contains(r.getSourceName()) && names.contains(r.getTargetName()))
            .sorted(Comparator.comparing(GraphRelation::getSourceName)
                .thenComparing(GraphRelation::getTargetName))
            .limit(40)
            .collect(Collectors.toList());

        List<CommunityReportDraft> childDrafts = new ArrayList<>();
        if (childComms != null)
        {
            for (KbGraphCommunity ch : childComms)
            {
                String key = ch.getLevel() + "|" + ch.getCommunityId();
                CommunityReportDraft d = drafts.get(key);
                if (d != null)
                {
                    childDrafts.add(d);
                }
            }
        }

        // evidence snippets
        LinkedHashSources sources = collectSources(entities, rels, childDrafts);
        List<EvidenceSnippet> snippets = loadEvidence(sources.ids, chunkTexts, maxEvidence);

        // map-reduce for oversized material: if too many entities, summarize children only + sample entities
        if (TokenCounter.estimate(entities.stream()
            .map(e -> e.getName() + " " + (e.getDescription() == null ? "" : e.getDescription()))
            .collect(Collectors.joining("\n"))) > maxInputTokens
            && entities.size() > 20)
        {
            entities = entities.stream()
                .sorted(Comparator.comparing(GraphEntity::getName, String.CASE_INSENSITIVE_ORDER))
                .limit(20)
                .collect(Collectors.toList());
        }

        CommunityReportDraft draft = ExtractiveCommunityReportBuilder.build(
            kbId, graphVersion, level, cid, entities, rels, childDrafts, snippets);

        if (reportUseLlm && llmGenerator != null && StringUtils.isNotEmpty(modelCode))
        {
            try
            {
                CommunityReportDraft llm = llmGenerator.enhance(draft, entities, rels, childDrafts, snippets, modelCode);
                if (llm != null && StringUtils.isNotEmpty(llm.getSummary()))
                {
                    // 保留 extractive 出处，覆盖正文
                    llm.getSourceChunkIds().clear();
                    llm.getSourceChunkIds().addAll(draft.getSourceChunkIds());
                    if (llm.getSourceChunkIds().isEmpty() && !snippets.isEmpty())
                    {
                        for (EvidenceSnippet sn : snippets)
                        {
                            llm.getSourceChunkIds().add(sn.chunkId());
                        }
                    }
                    draft = llm;
                }
            }
            catch (Exception e)
            {
                log.debug("LLM 社区报告增强失败,使用 extractive: {}", e.getMessage());
                draft.setIncomplete(true);
            }
        }

        // 验收：有实体时尽量保证至少一条出处；若全无 sourceIds，标记 PARTIAL 但仍落库
        if (draft.getSourceChunkIds().isEmpty() && !entities.isEmpty())
        {
            draft.setStatus("PARTIAL");
            draft.setIncomplete(true);
            draft.setSkipReason("no_source_chunks");
        }
        return draft;
    }

    private void persistDraft(CommunityReportDraft draft)
    {
        long reportId = ExtractiveCommunityReportBuilder.stableReportId(
            draft.getKbId(), draft.getGraphVersion(), draft.getLevel(), draft.getCommunityId());
        KbGraphCommunityReport row = new KbGraphCommunityReport();
        row.setReportId(reportId);
        row.setKbId(draft.getKbId());
        row.setGraphVersion(draft.getGraphVersion());
        row.setLevel(draft.getLevel());
        row.setCommunityId(draft.getCommunityId());
        row.setTitle(draft.getTitle());
        row.setSummary(draft.getSummary());
        row.setFullContent(draft.getFullContent());
        row.setFindingsJson(draft.getFindingsJson());
        row.setSourceCount(draft.getSourceChunkIds().size());
        row.setTokenCount(TokenCounter.estimate(
            nz(draft.getTitle()) + "\n" + nz(draft.getSummary()) + "\n" + nz(draft.getFullContent())));
        row.setModelCode(draft.getModelCode());
        row.setPromptVersion(draft.getPromptVersion());
        row.setContentHash(draft.getContentHash());
        row.setStatus(draft.getStatus());
        row.setCreateTime(new Date());

        List<KbGraphCommunityReportSource> sources = new ArrayList<>();
        int rank = 0;
        for (Long chunkId : draft.getSourceChunkIds())
        {
            if (chunkId == null)
            {
                continue;
            }
            KbGraphCommunityReportSource s = new KbGraphCommunityReportSource();
            s.setReportId(reportId);
            s.setChunkId(chunkId);
            s.setEvidenceRank(rank++);
            sources.add(s);
            if (rank >= 50)
            {
                break;
            }
        }

        DataSourceScope.runOn(DataSourceType.SLAVE, () -> {
            communityMapper.insertReport(row);
            if (!sources.isEmpty())
            {
                communityMapper.batchInsertReportSources(sources);
            }
        });
    }

    private void maybeEmbed(Long kbId, CommunityReportDraft draft)
    {
        if (!reportEmbed || reportVectorStore == null || draft == null)
        {
            return;
        }
        try
        {
            long reportId = ExtractiveCommunityReportBuilder.stableReportId(
                draft.getKbId(), draft.getGraphVersion(), draft.getLevel(), draft.getCommunityId());
            String text = draft.getSummary() != null ? draft.getSummary() : draft.getTitle();
            reportVectorStore.upsert(kbId, reportId, text);
        }
        catch (Exception e)
        {
            log.debug("社区报告向量写入跳过: {}", e.getMessage());
        }
    }

    private Map<String, GraphEntity> loadEntityIndex(Long kbId)
    {
        Map<String, GraphEntity> map = new HashMap<>();
        if (!kbGraphStore.isAvailable())
        {
            return map;
        }
        try
        {
            for (GraphEntity e : kbGraphStore.listEntities(kbId, 50_000))
            {
                if (e != null && e.getName() != null)
                {
                    map.put(e.getName().toLowerCase(), e);
                }
            }
        }
        catch (Exception e)
        {
            log.debug("加载实体索引失败: {}", e.getMessage());
        }
        return map;
    }

    private List<GraphRelation> safeListRelations(Long kbId)
    {
        if (!kbGraphStore.isAvailable())
        {
            return List.of();
        }
        try
        {
            return kbGraphStore.listRelations(kbId, 200_000);
        }
        catch (Exception e)
        {
            return List.of();
        }
    }

    private String resolveModelCode(Long kbId)
    {
        try
        {
            KbKnowledge kb = DataSourceScope.runOn(DataSourceType.SLAVE,
                () -> kbKnowledgeMapper.selectKbKnowledgeById(kbId));
            return kb != null ? kb.getExtractModelCode() : null;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static String findCommunityKey(List<KbGraphCommunity> all, Long communityId, int preferredLevel)
    {
        if (communityId == null)
        {
            return null;
        }
        for (KbGraphCommunity c : all)
        {
            if (communityId.equals(c.getCommunityId())
                && c.getLevel() != null && c.getLevel() == preferredLevel)
            {
                return c.getLevel() + "|" + c.getCommunityId();
            }
        }
        for (KbGraphCommunity c : all)
        {
            if (communityId.equals(c.getCommunityId()))
            {
                return c.getLevel() + "|" + c.getCommunityId();
            }
        }
        return null;
    }

    private static LinkedHashSources collectSources(List<GraphEntity> entities,
        List<GraphRelation> rels, List<CommunityReportDraft> children)
    {
        LinkedHashSources out = new LinkedHashSources();
        if (entities != null)
        {
            for (GraphEntity e : entities)
            {
                if (e != null && e.getSourceIds() != null)
                {
                    out.ids.addAll(e.getSourceIds().stream().filter(id -> id != null && id > 0).toList());
                }
            }
        }
        if (rels != null)
        {
            for (GraphRelation r : rels)
            {
                if (r != null && r.getSourceIds() != null)
                {
                    out.ids.addAll(r.getSourceIds().stream().filter(id -> id != null && id > 0).toList());
                }
            }
        }
        if (children != null)
        {
            for (CommunityReportDraft c : children)
            {
                if (c != null)
                {
                    out.ids.addAll(c.getSourceChunkIds());
                }
            }
        }
        return out;
    }

    private List<EvidenceSnippet> loadEvidence(List<Long> chunkIds, Map<Long, String> cache, int limit)
    {
        if (chunkIds == null || chunkIds.isEmpty() || limit <= 0)
        {
            return List.of();
        }
        List<Long> need = new ArrayList<>();
        List<EvidenceSnippet> out = new ArrayList<>();
        for (Long id : chunkIds)
        {
            if (id == null)
            {
                continue;
            }
            if (cache.containsKey(id))
            {
                out.add(new EvidenceSnippet(id, truncate(cache.get(id), 400)));
            }
            else
            {
                need.add(id);
            }
            if (out.size() >= limit)
            {
                return out;
            }
        }
        if (need.isEmpty())
        {
            return out;
        }
        try
        {
            List<Long> batch = need.subList(0, Math.min(need.size(), limit * 2));
            List<KbChunk> chunks = DataSourceScope.runOn(DataSourceType.SLAVE,
                () -> kbChunkMapper.selectByChunkIds(batch));
            if (chunks != null)
            {
                for (KbChunk c : chunks)
                {
                    if (c == null || c.getChunkId() == null)
                    {
                        continue;
                    }
                    String text = c.getContent() != null ? c.getContent() : "";
                    cache.put(c.getChunkId(), text);
                }
            }
        }
        catch (Exception e)
        {
            log.debug("加载 chunk 证据失败: {}", e.getMessage());
        }
        for (Long id : chunkIds)
        {
            if (out.size() >= limit)
            {
                break;
            }
            if (id == null)
            {
                continue;
            }
            String t = cache.get(id);
            if (t != null)
            {
                out.add(new EvidenceSnippet(id, truncate(t, 400)));
            }
        }
        return out;
    }

    private static String truncate(String s, int max)
    {
        if (s == null)
        {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String nz(String s)
    {
        return s == null ? "" : s;
    }

    private static final class LinkedHashSources
    {
        final List<Long> ids = new ArrayList<>();
    }
}
