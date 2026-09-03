package com.ruoyi.system.kb.graph.provenance;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.kb.graph.GraphEntity;
import com.ruoyi.system.kb.graph.GraphRelation;
import com.ruoyi.system.kb.graph.GraphSourceIds;
import com.ruoyi.system.kb.graph.KbGraphConstants;
import com.ruoyi.system.kb.graph.KbGraphStore;

/**
 * 进程内证据图存储：合约测试与无 Neo4j 时的完整血统语义。
 * <p>非 Spring Bean；生产用 {@link com.ruoyi.system.kb.graph.Neo4jKbGraphStore}。
 */
public class InMemoryProvenanceGraphStore implements KbGraphStore
{
    private final Map<String, GraphTextUnit> textUnits = new ConcurrentHashMap<>();
    private final Map<String, GraphRelationEvidence> evidences = new ConcurrentHashMap<>();
    private final Map<String, GraphEntity> entities = new ConcurrentHashMap<>();
    private final Map<String, GraphRelation> relations = new ConcurrentHashMap<>();
    /** entityKey -> chunkIds 完整血统（不受 10 条上限） */
    private final Map<String, LinkedHashSet<Long>> entityChunkLineage = new ConcurrentHashMap<>();
    /** entityKey -> filePaths 展示 */
    private final Map<String, List<String>> entityFilePaths = new ConcurrentHashMap<>();

    @Override
    public boolean isAvailable()
    {
        return true;
    }

    @Override
    public synchronized void replaceDocumentEvidence(GraphWriteBundle bundle)
    {
        if (bundle == null || bundle.getKbId() == null || bundle.getDocId() == null)
        {
            return;
        }
        // 清理失败则抛错：此处 delete 不吞异常
        deleteDocumentEvidence(bundle.getKbId(), bundle.getDocId());

        Long kbId = bundle.getKbId();
        Long docId = bundle.getDocId();
        Long gen = bundle.getGeneration() != null ? bundle.getGeneration() : 0L;
        Long runId = bundle.getRunId();

        for (GraphTextUnit tu : bundle.getTextUnits())
        {
            if (tu == null || tu.getChunkId() == null)
            {
                continue;
            }
            tu.setKbId(kbId);
            tu.setDocId(docId);
            tu.setGeneration(gen);
            tu.setRunId(runId);
            textUnits.put(textUnitKey(kbId, docId, tu.getChunkId()), copyTu(tu));
        }

        for (GraphEntity e : bundle.getEntities())
        {
            if (e == null || StringUtils.isEmpty(e.getName()))
            {
                continue;
            }
            String name = e.getName().trim();
            String ek = StringUtils.isNotEmpty(e.getEntityKey())
                ? e.getEntityKey() : entityKey(kbId, name);
            GraphEntity stored = entities.computeIfAbsent(ek, k -> {
                GraphEntity n = new GraphEntity();
                n.setKbId(kbId);
                n.setName(name);
                n.setEntityKey(ek);
                return n;
            });
            stored.setName(name);
            if (StringUtils.isNotEmpty(e.getCanonicalName()))
            {
                stored.setCanonicalName(e.getCanonicalName());
            }
            if (StringUtils.isNotEmpty(e.getCandidateKey()))
            {
                stored.setCandidateKey(e.getCandidateKey());
            }
            if (StringUtils.isNotEmpty(e.getType()))
            {
                stored.setType(e.getType());
            }
            if (StringUtils.isNotEmpty(e.getDescription()))
            {
                stored.setDescription(e.getDescription());
            }
            LinkedHashSet<Long> lineage = entityChunkLineage.computeIfAbsent(ek, k -> new LinkedHashSet<>());
            if (e.getSourceIds() != null)
            {
                lineage.addAll(e.getSourceIds().stream().filter(Objects::nonNull).toList());
            }
            if (e.getFilePaths() != null && !e.getFilePaths().isEmpty())
            {
                entityFilePaths.put(ek, GraphSourceIds.mergePaths(
                    entityFilePaths.get(ek), e.getFilePaths(), KbGraphConstants.MAX_FILE_PATHS));
            }
            refreshEntityDisplay(ek);
        }

        boolean hasExplicitEvidence = bundle.getEvidences() != null && !bundle.getEvidences().isEmpty();
        if (hasExplicitEvidence)
        {
            for (GraphRelationEvidence ev : bundle.getEvidences())
            {
                if (ev == null || StringUtils.isEmpty(ev.getSourceEntityKey())
                    || StringUtils.isEmpty(ev.getTargetEntityKey()))
                {
                    continue;
                }
                String sk = ev.getSourceEntityKey().trim();
                String tk = ev.getTargetEntityKey().trim();
                if (sk.equals(tk))
                {
                    continue;
                }
                ensureEntityShell(kbId, sk);
                ensureEntityShell(kbId, tk);
                String ekey = evidenceKey(kbId, docId, sk, ev.getPredicate(), tk, ev.getTextUnitChunkId());
                GraphRelationEvidence copy = copyEv(ev);
                copy.setKbId(kbId);
                copy.setDocId(docId);
                copy.setGeneration(gen);
                copy.setRunId(runId);
                copy.setSourceEntityKey(sk);
                copy.setTargetEntityKey(tk);
                copy.setEvidenceKey(ekey);
                evidences.put(ekey, copy);
                if (ev.getTextUnitChunkId() != null)
                {
                    entityChunkLineage.computeIfAbsent(entityKey(kbId, sk), k -> new LinkedHashSet<>())
                        .add(ev.getTextUnitChunkId());
                    entityChunkLineage.computeIfAbsent(entityKey(kbId, tk), k -> new LinkedHashSet<>())
                        .add(ev.getTextUnitChunkId());
                    refreshEntityDisplay(entityKey(kbId, sk));
                    refreshEntityDisplay(entityKey(kbId, tk));
                }
            }
        }
        else
        {
            // 兼容：无显式证据时从 relations 推导
            for (GraphRelation r : bundle.getRelations())
            {
                if (r == null || StringUtils.isEmpty(r.getSourceName()) || StringUtils.isEmpty(r.getTargetName()))
                {
                    continue;
                }
                String sk = r.getSourceName().trim();
                String tk = r.getTargetName().trim();
                if (sk.equals(tk))
                {
                    continue;
                }
                ensureEntityShell(kbId, sk);
                ensureEntityShell(kbId, tk);
                List<Long> srcIds = r.getSourceIds() != null ? new ArrayList<>(r.getSourceIds()) : new ArrayList<>();
                if (srcIds.isEmpty())
                {
                    srcIds.add(null);
                }
                for (Long chunkId : srcIds)
                {
                    String ekey = evidenceKey(kbId, docId, sk, r.getKeywords(), tk, chunkId);
                    GraphRelationEvidence ev = new GraphRelationEvidence();
                    ev.setKbId(kbId);
                    ev.setDocId(docId);
                    ev.setGeneration(gen);
                    ev.setRunId(runId);
                    ev.setSourceEntityKey(sk);
                    ev.setTargetEntityKey(tk);
                    ev.setPredicate(r.getKeywords() != null ? r.getKeywords() : "");
                    ev.setDescription(r.getDescription());
                    ev.setEvidenceKey(ekey);
                    ev.setTextUnitChunkId(chunkId);
                    evidences.put(ekey, ev);
                    if (chunkId != null)
                    {
                        entityChunkLineage.computeIfAbsent(entityKey(kbId, sk), k -> new LinkedHashSet<>())
                            .add(chunkId);
                        entityChunkLineage.computeIfAbsent(entityKey(kbId, tk), k -> new LinkedHashSet<>())
                            .add(chunkId);
                    }
                }
                refreshEntityDisplay(entityKey(kbId, sk));
                refreshEntityDisplay(entityKey(kbId, tk));
            }
        }

        recomputeRelated(kbId);
        pruneOrphans(kbId);
    }

    @Override
    public synchronized void deleteDocumentEvidence(Long kbId, Long docId)
    {
        if (kbId == null || docId == null)
        {
            return;
        }
        textUnits.entrySet().removeIf(e -> {
            GraphTextUnit tu = e.getValue();
            return kbId.equals(tu.getKbId()) && docId.equals(tu.getDocId());
        });
        Set<String> touchedEntities = new HashSet<>();
        evidences.entrySet().removeIf(e -> {
            GraphRelationEvidence ev = e.getValue();
            if (kbId.equals(ev.getKbId()) && docId.equals(ev.getDocId()))
            {
                touchedEntities.add(entityKey(kbId, ev.getSourceEntityKey()));
                touchedEntities.add(entityKey(kbId, ev.getTargetEntityKey()));
                return true;
            }
            return false;
        });

        // 重建血统：从剩余 textUnits 的内容不做实体链接时，仅从 evidences + 现存 lineage 中扣减本 doc chunk
        // 简化：完全从 evidences 的 chunk + 仍存在的其他 doc 的 entity 展示不完整；
        // 用「按 doc 写入时记录的 chunk 归 doc」—— 扫描剩余 TU chunk 无法还原实体。
        // 策略：lineage 仅保留仍被任一 evidence 或任一 textUnit(doc) 引用的 chunk 无法关联实体。
        // 因此 delete 后：对每个实体，lineage 重建为「所有剩余 evidence 的 textUnitChunkId」并集。
        rebuildLineageFromEvidence(kbId);
        recomputeRelated(kbId);
        pruneOrphans(kbId);
    }

    @Override
    public synchronized void upsertEntities(Long kbId, List<GraphEntity> list)
    {
        if (kbId == null || list == null)
        {
            return;
        }
        for (GraphEntity e : list)
        {
            if (e == null || StringUtils.isEmpty(e.getName()))
            {
                continue;
            }
            String name = e.getName().trim();
            String ek = StringUtils.isNotEmpty(e.getEntityKey())
                ? e.getEntityKey() : entityKey(kbId, name);
            GraphEntity stored = entities.computeIfAbsent(ek, k -> {
                GraphEntity n = new GraphEntity();
                n.setKbId(kbId);
                n.setName(name);
                n.setEntityKey(ek);
                return n;
            });
            stored.setName(name);
            if (StringUtils.isNotEmpty(e.getEntityKey()))
            {
                stored.setEntityKey(e.getEntityKey());
            }
            if (StringUtils.isNotEmpty(e.getCanonicalName()))
            {
                stored.setCanonicalName(e.getCanonicalName());
            }
            if (StringUtils.isNotEmpty(e.getType()))
            {
                stored.setType(e.getType());
            }
            if (StringUtils.isNotEmpty(e.getDescription()))
            {
                stored.setDescription(e.getDescription());
            }
            LinkedHashSet<Long> lineage = entityChunkLineage.computeIfAbsent(ek, k -> new LinkedHashSet<>());
            if (e.getSourceIds() != null)
            {
                lineage.addAll(e.getSourceIds().stream().filter(Objects::nonNull).toList());
            }
            refreshEntityDisplay(ek);
        }
    }

    @Override
    public synchronized void upsertRelations(Long kbId, List<GraphRelation> list)
    {
        if (kbId == null || list == null)
        {
            return;
        }
        for (GraphRelation r : list)
        {
            if (r == null || StringUtils.isEmpty(r.getSourceName()) || StringUtils.isEmpty(r.getTargetName()))
            {
                continue;
            }
            String sk = r.getSourceName().trim();
            String tk = r.getTargetName().trim();
            if (sk.equals(tk))
            {
                continue;
            }
            ensureEntityShell(kbId, sk);
            ensureEntityShell(kbId, tk);
            String rk = StringUtils.isNotEmpty(r.getRelationKey())
                ? r.getRelationKey() : relKey(kbId, sk, tk);
            GraphRelation stored = relations.computeIfAbsent(rk, k -> {
                GraphRelation n = new GraphRelation();
                n.setKbId(kbId);
                n.setSourceName(sk);
                n.setTargetName(tk);
                n.setRelationKey(rk);
                return n;
            });
            stored.setSourceName(sk);
            stored.setTargetName(tk);
            if (StringUtils.isNotEmpty(r.getKeywords()))
            {
                stored.setKeywords(r.getKeywords());
            }
            if (StringUtils.isNotEmpty(r.getPredicate()))
            {
                stored.setPredicate(r.getPredicate());
            }
            if (StringUtils.isNotEmpty(r.getDescription()))
            {
                stored.setDescription(r.getDescription());
            }
            stored.setWeight(r.getWeight() > 0 ? r.getWeight() : Math.max(1.0, stored.getWeight()));
            stored.setSourceIds(GraphSourceIds.mergeLongs(stored.getSourceIds(), r.getSourceIds(),
                KbGraphConstants.MAX_SOURCE_IDS));
        }
    }

    @Override
    public List<GraphEntity> findEntitiesByNames(Long kbId, List<String> names)
    {
        if (kbId == null || names == null)
        {
            return List.of();
        }
        List<GraphEntity> out = new ArrayList<>();
        Set<String> want = new HashSet<>();
        for (String n : names)
        {
            if (StringUtils.isNotEmpty(n))
            {
                want.add(n.trim());
            }
        }
        for (GraphEntity e : entities.values())
        {
            if (!kbId.equals(e.getKbId()) || e.getName() == null)
            {
                continue;
            }
            if (want.contains(e.getName()) || (e.getCanonicalName() != null && want.contains(e.getCanonicalName())))
            {
                out.add(copyEntity(e));
            }
        }
        return out;
    }

    @Override
    public List<GraphEntity> searchEntities(Long kbId, String query, int limit)
    {
        if (kbId == null || StringUtils.isEmpty(query) || limit <= 0)
        {
            return List.of();
        }
        String q = query.toLowerCase(Locale.ROOT);
        return entities.values().stream()
            .filter(e -> kbId.equals(e.getKbId()) && e.getName() != null
                && e.getName().toLowerCase(Locale.ROOT).contains(q))
            .limit(limit)
            .map(this::copyEntity)
            .toList();
    }

    @Override
    public List<GraphEntity> neighbors(Long kbId, String name, int hops, int limit)
    {
        if (kbId == null || StringUtils.isEmpty(name))
        {
            return List.of();
        }
        Set<String> names = new LinkedHashSet<>();
        names.add(name.trim());
        for (int i = 0; i < Math.max(1, hops); i++)
        {
            Set<String> add = new HashSet<>();
            for (GraphRelation r : relations.values())
            {
                if (!kbId.equals(r.getKbId()))
                {
                    continue;
                }
                if (names.contains(r.getSourceName()))
                {
                    add.add(r.getTargetName());
                }
                if (names.contains(r.getTargetName()))
                {
                    add.add(r.getSourceName());
                }
            }
            names.addAll(add);
        }
        return names.stream()
            .map(n -> entities.get(entityKey(kbId, n)))
            .filter(Objects::nonNull)
            .limit(Math.max(1, limit))
            .map(this::copyEntity)
            .toList();
    }

    @Override
    public synchronized void removeChunkRefs(Long kbId, List<Long> chunkIds)
    {
        if (kbId == null || chunkIds == null || chunkIds.isEmpty())
        {
            return;
        }
        Set<Long> remove = new HashSet<>(chunkIds);
        // 若 textUnit 命中，按 doc 精确删证据
        Set<Long> docs = textUnits.values().stream()
            .filter(t -> kbId.equals(t.getKbId()) && t.getChunkId() != null && remove.contains(t.getChunkId()))
            .map(GraphTextUnit::getDocId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        for (Long docId : docs)
        {
            deleteDocumentEvidence(kbId, docId);
        }
        // 兼容：无 textUnit 时只摘 sourceIds
        if (docs.isEmpty())
        {
            for (Map.Entry<String, LinkedHashSet<Long>> e : entityChunkLineage.entrySet())
            {
                if (!e.getKey().startsWith(kbId + "|"))
                {
                    continue;
                }
                e.getValue().removeAll(remove);
                refreshEntityDisplay(e.getKey());
            }
            for (GraphRelation r : relations.values())
            {
                if (kbId.equals(r.getKbId()))
                {
                    r.setSourceIds(GraphSourceIds.removeAll(r.getSourceIds(), chunkIds));
                }
            }
            relations.entrySet().removeIf(en ->
                kbId.equals(en.getValue().getKbId())
                    && (en.getValue().getSourceIds() == null || en.getValue().getSourceIds().isEmpty()));
            pruneOrphans(kbId);
        }
    }

    @Override
    public synchronized void deleteByKb(Long kbId)
    {
        if (kbId == null)
        {
            return;
        }
        textUnits.entrySet().removeIf(e -> kbId.equals(e.getValue().getKbId()));
        evidences.entrySet().removeIf(e -> kbId.equals(e.getValue().getKbId()));
        entities.entrySet().removeIf(e -> kbId.equals(e.getValue().getKbId()));
        relations.entrySet().removeIf(e -> kbId.equals(e.getValue().getKbId()));
        entityChunkLineage.keySet().removeIf(k -> k.startsWith(kbId + "|"));
        entityFilePaths.keySet().removeIf(k -> k.startsWith(kbId + "|"));
    }

    @Override
    public List<GraphEntity> listEntities(Long kbId, int limit)
    {
        return entities.values().stream()
            .filter(e -> kbId.equals(e.getKbId()))
            .sorted(Comparator.comparing(GraphEntity::getName, Comparator.nullsLast(String::compareTo)))
            .limit(Math.max(1, Math.min(limit, 500)))
            .map(this::copyEntity)
            .toList();
    }

    @Override
    public List<GraphRelation> listRelations(Long kbId, int limit)
    {
        return relations.values().stream()
            .filter(r -> kbId.equals(r.getKbId()))
            .limit(Math.max(1, Math.min(limit, 500)))
            .map(this::copyRel)
            .toList();
    }

    @Override
    public int countEntities(Long kbId)
    {
        return (int) entities.values().stream().filter(e -> kbId.equals(e.getKbId())).count();
    }

    @Override
    public int countRelations(Long kbId)
    {
        return (int) relations.values().stream().filter(r -> kbId.equals(r.getKbId())).count();
    }

    @Override
    public int countTextUnits(Long kbId, Long docId)
    {
        return (int) textUnits.values().stream()
            .filter(t -> kbId.equals(t.getKbId()) && (docId == null || docId.equals(t.getDocId())))
            .count();
    }

    @Override
    public int countRelationEvidences(Long kbId, Long docId)
    {
        return (int) evidences.values().stream()
            .filter(e -> kbId.equals(e.getKbId()) && (docId == null || docId.equals(e.getDocId())))
            .count();
    }

    /** 测试：实体完整血统 chunk 数（不受 10 上限） */
    public int entityLineageSize(Long kbId, String name)
    {
        if (name == null)
        {
            return 0;
        }
        int total = 0;
        for (Map.Entry<String, GraphEntity> en : entities.entrySet())
        {
            GraphEntity e = en.getValue();
            if (!kbId.equals(e.getKbId()))
            {
                continue;
            }
            if (name.equals(e.getName()) || name.equals(e.getCanonicalName())
                || entityKey(kbId, name).equals(en.getKey()))
            {
                LinkedHashSet<Long> set = entityChunkLineage.get(en.getKey());
                if (set != null)
                {
                    total = Math.max(total, set.size());
                }
            }
        }
        return total;
    }

    private void rebuildLineageFromEvidence(Long kbId)
    {
        Map<String, LinkedHashSet<Long>> next = new HashMap<>();
        for (GraphRelationEvidence ev : evidences.values())
        {
            if (!kbId.equals(ev.getKbId()))
            {
                continue;
            }
            if (ev.getTextUnitChunkId() == null)
            {
                continue;
            }
            next.computeIfAbsent(entityKey(kbId, ev.getSourceEntityKey()), k -> new LinkedHashSet<>())
                .add(ev.getTextUnitChunkId());
            next.computeIfAbsent(entityKey(kbId, ev.getTargetEntityKey()), k -> new LinkedHashSet<>())
                .add(ev.getTextUnitChunkId());
        }
        // 清除本 kb 旧 lineage，写入重建结果
        entityChunkLineage.keySet().removeIf(k -> k.startsWith(kbId + "|"));
        entityChunkLineage.putAll(next);
        for (String ek : new ArrayList<>(entities.keySet()))
        {
            if (ek.startsWith(kbId + "|"))
            {
                refreshEntityDisplay(ek);
            }
        }
    }

    private void recomputeRelated(Long kbId)
    {
        Map<String, List<GraphRelationEvidence>> byPair = new LinkedHashMap<>();
        for (GraphRelationEvidence ev : evidences.values())
        {
            if (!kbId.equals(ev.getKbId()))
            {
                continue;
            }
            String pred = ev.getPredicate() != null ? ev.getPredicate() : "";
            String rk = relKey(kbId, ev.getSourceEntityKey(), pred, ev.getTargetEntityKey());
            byPair.computeIfAbsent(rk, k -> new ArrayList<>()).add(ev);
        }
        relations.entrySet().removeIf(e -> kbId.equals(e.getValue().getKbId()));
        for (Map.Entry<String, List<GraphRelationEvidence>> e : byPair.entrySet())
        {
            List<GraphRelationEvidence> list = e.getValue();
            GraphRelationEvidence first = list.get(0);
            GraphRelation rel = new GraphRelation();
            rel.setKbId(kbId);
            rel.setSourceName(first.getSourceEntityKey());
            rel.setTargetName(first.getTargetEntityKey());
            rel.setKeywords(first.getPredicate() != null ? first.getPredicate() : "");
            rel.setPredicate(first.getPredicate());
            rel.setRelationKey(e.getKey());
            rel.setDescription(first.getDescription());
            rel.setWeight(list.size());
            List<Long> ids = new ArrayList<>();
            for (GraphRelationEvidence ev : list)
            {
                if (ev.getTextUnitChunkId() != null)
                {
                    ids.add(ev.getTextUnitChunkId());
                }
            }
            rel.setSourceIds(GraphSourceIds.mergeLongs(List.of(), ids, KbGraphConstants.MAX_SOURCE_IDS));
            relations.put(e.getKey(), rel);
        }
    }

    private void pruneOrphans(Long kbId)
    {
        Set<String> referenced = new HashSet<>();
        for (GraphRelationEvidence ev : evidences.values())
        {
            if (kbId.equals(ev.getKbId()))
            {
                referenced.add(entityKey(kbId, ev.getSourceEntityKey()));
                referenced.add(entityKey(kbId, ev.getTargetEntityKey()));
            }
        }
        for (Map.Entry<String, LinkedHashSet<Long>> e : entityChunkLineage.entrySet())
        {
            if (e.getKey().startsWith(kbId + "|") && e.getValue() != null && !e.getValue().isEmpty())
            {
                referenced.add(e.getKey());
            }
        }
        entities.entrySet().removeIf(en ->
            en.getKey().startsWith(kbId + "|") && !referenced.contains(en.getKey()));
        entityChunkLineage.keySet().removeIf(k -> k.startsWith(kbId + "|") && !referenced.contains(k));
        entityFilePaths.keySet().removeIf(k -> k.startsWith(kbId + "|") && !referenced.contains(k));
    }

    private void ensureEntityShell(Long kbId, String name)
    {
        String ek = entityKey(kbId, name);
        entities.computeIfAbsent(ek, k -> {
            GraphEntity n = new GraphEntity();
            n.setKbId(kbId);
            n.setName(name);
            return n;
        });
    }

    private void refreshEntityDisplay(String ek)
    {
        GraphEntity e = entities.get(ek);
        if (e == null)
        {
            return;
        }
        LinkedHashSet<Long> lineage = entityChunkLineage.getOrDefault(ek, new LinkedHashSet<>());
        e.setSourceIds(GraphSourceIds.mergeLongs(List.of(), new ArrayList<>(lineage),
            KbGraphConstants.MAX_SOURCE_IDS));
        List<String> paths = entityFilePaths.get(ek);
        if (paths != null)
        {
            e.setFilePaths(new ArrayList<>(paths));
        }
    }

    private static String entityKey(Long kbId, String name)
    {
        return kbId + "|" + (name == null ? "" : name.trim());
    }

    private static String textUnitKey(Long kbId, Long docId, Long chunkId)
    {
        return kbId + "|" + docId + "|" + chunkId;
    }

    private static String evidenceKey(Long kbId, Long docId, String src, String pred, String tgt, Long chunkId)
    {
        return kbId + "|" + docId + "|" + src + "|" + (pred == null ? "" : pred) + "|" + tgt + "|"
            + (chunkId == null ? "_" : chunkId);
    }

    private static String relKey(Long kbId, String src, String pred, String tgt)
    {
        return kbId + "|" + src + "|" + (pred == null ? "" : pred) + "|" + tgt;
    }

    private static String relKey(Long kbId, String src, String tgt)
    {
        return relKey(kbId, src, "", tgt);
    }

    private GraphEntity copyEntity(GraphEntity e)
    {
        GraphEntity c = new GraphEntity();
        c.setKbId(e.getKbId());
        c.setName(e.getName());
        c.setType(e.getType());
        c.setDescription(e.getDescription());
        c.setCanonicalName(e.getCanonicalName());
        c.setCandidateKey(e.getCandidateKey());
        c.setEntityKey(e.getEntityKey());
        c.setSourceIds(e.getSourceIds() == null ? new ArrayList<>() : new ArrayList<>(e.getSourceIds()));
        c.setFilePaths(e.getFilePaths() == null ? new ArrayList<>() : new ArrayList<>(e.getFilePaths()));
        return c;
    }

    private GraphRelation copyRel(GraphRelation r)
    {
        GraphRelation c = new GraphRelation();
        c.setKbId(r.getKbId());
        c.setSourceName(r.getSourceName());
        c.setTargetName(r.getTargetName());
        c.setKeywords(r.getKeywords());
        c.setDescription(r.getDescription());
        c.setWeight(r.getWeight());
        c.setSourceIds(r.getSourceIds() == null ? new ArrayList<>() : new ArrayList<>(r.getSourceIds()));
        return c;
    }

    private static GraphTextUnit copyTu(GraphTextUnit t)
    {
        GraphTextUnit c = new GraphTextUnit();
        c.setKbId(t.getKbId());
        c.setDocId(t.getDocId());
        c.setChunkId(t.getChunkId());
        c.setContentHash(t.getContentHash());
        c.setGeneration(t.getGeneration());
        c.setRunId(t.getRunId());
        c.setContent(t.getContent());
        c.setHeadingPath(t.getHeadingPath());
        c.setSourcePageFrom(t.getSourcePageFrom());
        c.setSourcePageTo(t.getSourcePageTo());
        c.setSourceLabel(t.getSourceLabel());
        return c;
    }

    private static GraphRelationEvidence copyEv(GraphRelationEvidence e)
    {
        GraphRelationEvidence c = new GraphRelationEvidence();
        c.setKbId(e.getKbId());
        c.setDocId(e.getDocId());
        c.setEvidenceKey(e.getEvidenceKey());
        c.setPredicate(e.getPredicate());
        c.setDescription(e.getDescription());
        c.setGeneration(e.getGeneration());
        c.setRunId(e.getRunId());
        c.setSourceEntityKey(e.getSourceEntityKey());
        c.setTargetEntityKey(e.getTargetEntityKey());
        c.setTextUnitChunkId(e.getTextUnitChunkId());
        return c;
    }
}
