package com.ruoyi.system.kb.graph.merge;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.kb.graph.GraphEntity;
import com.ruoyi.system.kb.graph.GraphRelation;
import com.ruoyi.system.kb.graph.GraphSourceIds;
import com.ruoyi.system.kb.graph.KbGraphConstants;
import com.ruoyi.system.kb.graph.extract.ExtractResultParser.ExtractResult;
import com.ruoyi.system.kb.graph.merge.EntityCanonicalizer.Identity;

/**
 * 按规范化 entityKey / relationKey 合并 sourceIds 与描述，再写回图库。
 * <p>同名异义（不同类型）不合并；关系按 source+predicate+target 分键。
 */
@Component
public class KbGraphMerger
{
    private static final Logger log = LoggerFactory.getLogger(KbGraphMerger.class);

    @Autowired
    private DescriptionSummarizer summarizer;

    @Value("${ai.kb.graph.merge-concurrency:8}")
    private int mergeConcurrency;

    public MergeResult merge(Long docId, Long kbId, String modelCode,
                             List<ExtractResult> chunkResults)
    {
        Map<String, AccumEntity> entityMap = new LinkedHashMap<>();
        Map<String, AccumRel> relMap = new LinkedHashMap<>();
        // 端点展示名 → entityKey（用于关系端点对齐）
        Map<String, String> displayToEntityKey = new LinkedHashMap<>();

        if (chunkResults != null)
        {
            for (ExtractResult er : chunkResults)
            {
                if (er == null)
                {
                    continue;
                }
                for (GraphEntity e : er.getEntities())
                {
                    if (e == null || StringUtils.isEmpty(e.getName()))
                    {
                        continue;
                    }
                    Identity id = EntityCanonicalizer.identityOf(kbId, e);
                    AccumEntity acc = entityMap.computeIfAbsent(id.entityKey,
                        k -> new AccumEntity(id));
                    if (StringUtils.isNotEmpty(e.getType()) && StringUtils.isEmpty(acc.type))
                    {
                        acc.type = e.getType();
                    }
                    if (StringUtils.isNotEmpty(e.getDescription()))
                    {
                        acc.descriptions.add(e.getDescription().trim());
                    }
                    // 完整血统用 set；展示 sourceIds 仍封顶
                    if (e.getSourceIds() != null)
                    {
                        for (Long sid : e.getSourceIds())
                        {
                            if (sid != null)
                            {
                                acc.evidenceChunkIds.add(sid);
                            }
                        }
                    }
                    acc.sourceIds = GraphSourceIds.mergeLongs(acc.sourceIds, e.getSourceIds(),
                        KbGraphConstants.MAX_SOURCE_IDS);
                    acc.filePaths = GraphSourceIds.mergePaths(acc.filePaths, e.getFilePaths(),
                        KbGraphConstants.MAX_FILE_PATHS);
                    displayToEntityKey.put(id.displayName, id.entityKey);
                    displayToEntityKey.put(EntityNormalizer.normalizeName(id.displayName), id.entityKey);
                }
                for (GraphRelation r : er.getRelations())
                {
                    if (r == null || StringUtils.isEmpty(r.getSourceName())
                        || StringUtils.isEmpty(r.getTargetName()))
                    {
                        continue;
                    }
                    GraphRelation tmp = new GraphRelation();
                    tmp.setSourceName(r.getSourceName());
                    tmp.setTargetName(r.getTargetName());
                    tmp.setKeywords(r.getKeywords());
                    RelationCanonicalizer.applyTo(tmp, kbId);

                    String rKey = tmp.getRelationKey();
                    AccumRel acc = relMap.computeIfAbsent(rKey,
                        k -> new AccumRel(tmp.getSourceName(), tmp.getTargetName(),
                            tmp.getKeywords(), tmp.getPredicate(), rKey));
                    if (StringUtils.isNotEmpty(r.getDescription()))
                    {
                        acc.descriptions.add(r.getDescription().trim());
                    }
                    if (r.getSourceIds() != null)
                    {
                        for (Long sid : r.getSourceIds())
                        {
                            if (sid != null)
                            {
                                acc.evidenceChunkIds.add(sid);
                            }
                        }
                    }
                    acc.sourceIds = GraphSourceIds.mergeLongs(acc.sourceIds, r.getSourceIds(),
                        KbGraphConstants.MAX_SOURCE_IDS);
                }
            }
        }

        // 保证关系端点实体存在（悬空端点 = 0）
        for (AccumRel acc : relMap.values())
        {
            ensureEndpointEntity(entityMap, kbId, acc.source, displayToEntityKey);
            ensureEndpointEntity(entityMap, kbId, acc.target, displayToEntityKey);
        }

        List<GraphEntity> entities = summarizeEntities(docId, kbId, modelCode, entityMap);
        List<GraphRelation> relations = new ArrayList<>();
        for (AccumRel acc : relMap.values())
        {
            GraphRelation r = new GraphRelation();
            r.setKbId(kbId);
            r.setSourceName(acc.source);
            r.setTargetName(acc.target);
            // 绑定稳定 entityKey，写图时按 key MERGE，避免 name 唯一冲突
            String sk = displayToEntityKey.get(acc.source);
            if (sk == null)
            {
                sk = displayToEntityKey.get(EntityNormalizer.normalizeName(acc.source));
            }
            String tk = displayToEntityKey.get(acc.target);
            if (tk == null)
            {
                tk = displayToEntityKey.get(EntityNormalizer.normalizeName(acc.target));
            }
            if (sk == null)
            {
                sk = EntityCanonicalizer.identityOf(kbId, acc.source, null, null).entityKey;
            }
            if (tk == null)
            {
                tk = EntityCanonicalizer.identityOf(kbId, acc.target, null, null).entityKey;
            }
            r.setSourceEntityKey(sk);
            r.setTargetEntityKey(tk);
            r.setKeywords(acc.keywords);
            r.setPredicate(acc.predicate);
            r.setRelationKey(acc.relationKey);
            r.setDescription(DescriptionSummarizer.joinDirect(acc.descriptions));
            r.setSourceIds(acc.sourceIds);
            r.setWeight(RelationCanonicalizer.weightFromEvidence(
                acc.evidenceChunkIds.size(), 1));
            relations.add(r);
        }

        // 最终端点完整性断言
        Set<String> entityNames = new HashSet<>();
        for (GraphEntity e : entities)
        {
            if (e.getName() != null)
            {
                entityNames.add(EntityNormalizer.normalizeName(e.getName()));
            }
        }
        relations.removeIf(r ->
            !entityNames.contains(EntityNormalizer.normalizeName(r.getSourceName()))
                || !entityNames.contains(EntityNormalizer.normalizeName(r.getTargetName())));

        MergeResult result = new MergeResult();
        result.setEntities(entities);
        result.setRelations(relations);
        return result;
    }

    private static void ensureEndpointEntity(Map<String, AccumEntity> entityMap, Long kbId,
        String displayName, Map<String, String> displayToEntityKey)
    {
        Identity id = EntityCanonicalizer.identityOf(kbId, displayName, null, null);
        entityMap.computeIfAbsent(id.entityKey, k -> new AccumEntity(id));
        displayToEntityKey.put(id.displayName, id.entityKey);
    }

    private List<GraphEntity> summarizeEntities(Long docId, Long kbId, String modelCode,
                                                Map<String, AccumEntity> entityMap)
    {
        List<GraphEntity> out = new ArrayList<>();
        if (entityMap.isEmpty())
        {
            return out;
        }
        int pool = Math.max(1, mergeConcurrency);
        ExecutorService exec = Executors.newFixedThreadPool(pool);
        try
        {
            List<Future<GraphEntity>> futures = new ArrayList<>();
            for (AccumEntity acc : entityMap.values())
            {
                futures.add(exec.submit(() -> {
                    GraphEntity e = new GraphEntity();
                    e.setKbId(kbId);
                    e.setName(acc.displayName);
                    e.setCanonicalName(acc.displayName);
                    e.setType(acc.type);
                    e.setEntityKey(acc.entityKey);
                    e.setCandidateKey(acc.candidateKey);
                    e.setSourceIds(acc.sourceIds);
                    e.setFilePaths(acc.filePaths);
                    e.setDescription(summarizer.summarize(docId, modelCode, acc.displayName,
                        acc.descriptions));
                    return e;
                }));
            }
            for (Future<GraphEntity> f : futures)
            {
                try
                {
                    out.add(f.get(120, TimeUnit.SECONDS));
                }
                catch (Exception ex)
                {
                    log.warn("实体合并任务失败: {}", ex.getMessage());
                }
            }
        }
        finally
        {
            exec.shutdownNow();
        }
        return out;
    }

    private static class AccumEntity
    {
        final String displayName;
        final String entityKey;
        final String candidateKey;
        String type;
        final List<String> descriptions = new ArrayList<>();
        List<Long> sourceIds = new ArrayList<>();
        List<String> filePaths = new ArrayList<>();
        final LinkedHashSet<Long> evidenceChunkIds = new LinkedHashSet<>();

        AccumEntity(Identity id)
        {
            this.displayName = id.displayName;
            this.entityKey = id.entityKey;
            this.candidateKey = id.candidateKey;
            this.type = id.normalizedType;
        }
    }

    private static class AccumRel
    {
        final String source;
        final String target;
        final String keywords;
        final String predicate;
        final String relationKey;
        final List<String> descriptions = new ArrayList<>();
        List<Long> sourceIds = new ArrayList<>();
        final LinkedHashSet<Long> evidenceChunkIds = new LinkedHashSet<>();

        AccumRel(String source, String target, String keywords, String predicate, String relationKey)
        {
            this.source = source;
            this.target = target;
            this.keywords = keywords;
            this.predicate = predicate;
            this.relationKey = relationKey;
        }
    }

    public static class MergeResult
    {
        private List<GraphEntity> entities = new ArrayList<>();
        private List<GraphRelation> relations = new ArrayList<>();

        public List<GraphEntity> getEntities()
        {
            return entities;
        }

        public void setEntities(List<GraphEntity> entities)
        {
            this.entities = entities != null ? entities : new ArrayList<>();
        }

        public List<GraphRelation> getRelations()
        {
            return relations;
        }

        public void setRelations(List<GraphRelation> relations)
        {
            this.relations = relations != null ? relations : new ArrayList<>();
        }
    }
}
