package com.ruoyi.system.kb.graph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.TransactionContext;
import org.neo4j.driver.Values;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.kb.graph.provenance.GraphRelationEvidence;
import com.ruoyi.system.kb.graph.provenance.GraphTextUnit;
import com.ruoyi.system.kb.graph.provenance.GraphWriteBundle;

/**
 * Neo4j 图存储实现。
 * <p>血统 v2：{@link #replaceDocumentEvidence}/{@link #deleteDocumentEvidence} 以 TextUnit/RelationEvidence
 * 为事实；{@link #removeChunkRefs} 兼容旧路径。
 */
@Component("neo4jKbGraphStore")
@ConditionalOnBean(Driver.class)
public class Neo4jKbGraphStore implements KbGraphStore
{
    private static final Logger log = LoggerFactory.getLogger(Neo4jKbGraphStore.class);

    @Autowired
    private Driver driver;

    @Value("${ai.kb.neo4j.database:neo4j}")
    private String database;

    @Override
    public boolean isAvailable()
    {
        return true;
    }

    private Session session()
    {
        return driver.session(SessionConfig.builder().withDatabase(database).build());
    }

    @Override
    public void upsertEntities(Long kbId, List<GraphEntity> entities)
    {
        if (kbId == null || entities == null || entities.isEmpty())
        {
            return;
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (GraphEntity e : entities)
        {
            if (e == null || StringUtils.isEmpty(e.getName()))
            {
                continue;
            }
            Map<String, Object> row = new HashMap<>();
            String name = e.getName().trim();
            String entityKey = StringUtils.isNotEmpty(e.getEntityKey())
                ? e.getEntityKey() : (kbId + "|" + name);
            row.put("entityKey", entityKey);
            row.put("name", name);
            row.put("canonicalName", e.getCanonicalName() != null ? e.getCanonicalName() : name);
            row.put("candidateKey", e.getCandidateKey() != null ? e.getCandidateKey() : "");
            row.put("type", e.getType() != null ? e.getType() : "");
            row.put("description", e.getDescription() != null ? e.getDescription() : "");
            row.put("sourceIds", toLongList(e.getSourceIds()));
            row.put("filePaths", e.getFilePaths() != null ? e.getFilePaths() : List.of());
            rows.add(row);
        }
        if (rows.isEmpty())
        {
            return;
        }
        // 稳定身份 entityKey；name 仅展示（同名异义可共存）
        String cypher = """
            UNWIND $rows AS r
            MERGE (e:Entity {kbId: $kbId, entityKey: r.entityKey})
            ON CREATE SET e.name = r.name,
                          e.canonicalName = r.canonicalName,
                          e.candidateKey = r.candidateKey,
                          e.type = r.type,
                          e.description = r.description,
                          e.sourceIds = r.sourceIds,
                          e.filePaths = r.filePaths,
                          e.updatedAt = datetime()
            ON MATCH SET e.name = r.name,
                         e.canonicalName = r.canonicalName,
                         e.candidateKey = CASE WHEN r.candidateKey <> '' THEN r.candidateKey ELSE e.candidateKey END,
                         e.type = CASE WHEN r.type <> '' THEN r.type ELSE e.type END,
                         e.description = r.description,
                         e.sourceIds = r.sourceIds,
                         e.filePaths = r.filePaths,
                         e.updatedAt = datetime()
            """;
        try (Session s = session())
        {
            s.run(cypher, Values.parameters("kbId", kbId, "rows", rows)).consume();
        }
    }

    @Override
    public void upsertRelations(Long kbId, List<GraphRelation> relations)
    {
        if (kbId == null || relations == null || relations.isEmpty())
        {
            return;
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (GraphRelation r : relations)
        {
            if (r == null || StringUtils.isEmpty(r.getSourceName()) || StringUtils.isEmpty(r.getTargetName()))
            {
                continue;
            }
            if (r.getSourceName().equals(r.getTargetName()))
            {
                continue;
            }
            Map<String, Object> row = new HashMap<>();
            String source = r.getSourceName().trim();
            String target = r.getTargetName().trim();
            String sourceKey = StringUtils.isNotEmpty(r.getSourceEntityKey())
                ? r.getSourceEntityKey().trim()
                : (kbId + "|" + source + "||");
            String targetKey = StringUtils.isNotEmpty(r.getTargetEntityKey())
                ? r.getTargetEntityKey().trim()
                : (kbId + "|" + target + "||");
            String relKey = StringUtils.isNotEmpty(r.getRelationKey())
                ? r.getRelationKey()
                : (kbId + "|" + source + "|" + (r.getPredicate() != null ? r.getPredicate() : "")
                    + "|" + target);
            row.put("source", source);
            row.put("target", target);
            row.put("sourceKey", sourceKey);
            row.put("targetKey", targetKey);
            row.put("relationKey", relKey);
            row.put("keywords", r.getKeywords() != null ? r.getKeywords() : "");
            row.put("predicate", r.getPredicate() != null ? r.getPredicate() : "");
            row.put("description", r.getDescription() != null ? r.getDescription() : "");
            row.put("weight", r.getWeight() > 0 ? r.getWeight() : 1.0);
            row.put("sourceIds", toLongList(r.getSourceIds()));
            rows.add(row);
        }
        if (rows.isEmpty())
        {
            return;
        }
        // 端点优先 entityKey，回退 name（兼容旧边与旧数据）
        String cypher = """
            UNWIND $rows AS r
            OPTIONAL MATCH (aKey:Entity {kbId: $kbId, entityKey: r.sourceKey})
            OPTIONAL MATCH (aName:Entity {kbId: $kbId, name: r.source})
            WITH r, coalesce(aKey, aName) AS a
            OPTIONAL MATCH (bKey:Entity {kbId: $kbId, entityKey: r.targetKey})
            OPTIONAL MATCH (bName:Entity {kbId: $kbId, name: r.target})
            WITH r, a, coalesce(bKey, bName) AS b
            WHERE a IS NOT NULL AND b IS NOT NULL
            MERGE (a)-[rel:RELATED {kbId: $kbId, relationKey: r.relationKey}]->(b)
            ON CREATE SET rel.keywords = r.keywords,
                          rel.predicate = r.predicate,
                          rel.description = r.description,
                          rel.weight = r.weight,
                          rel.sourceIds = r.sourceIds,
                          rel.updatedAt = datetime()
            ON MATCH SET rel.keywords = CASE WHEN r.keywords <> '' THEN r.keywords ELSE rel.keywords END,
                         rel.predicate = CASE WHEN r.predicate <> '' THEN r.predicate ELSE rel.predicate END,
                         rel.description = r.description,
                         rel.weight = r.weight,
                         rel.sourceIds = r.sourceIds,
                         rel.updatedAt = datetime()
            """;
        try (Session s = session())
        {
            s.run(cypher, Values.parameters("kbId", kbId, "rows", rows)).consume();
        }
    }

    @Override
    public List<GraphEntity> findEntitiesByNames(Long kbId, List<String> names)
    {
        if (kbId == null || names == null || names.isEmpty())
        {
            return List.of();
        }
        List<String> clean = names.stream()
            .filter(StringUtils::isNotEmpty)
            .map(String::trim)
            .distinct()
            .toList();
        if (clean.isEmpty())
        {
            return List.of();
        }
        String cypher = """
            MATCH (e:Entity {kbId: $kbId})
            WHERE e.name IN $names
            RETURN e
            """;
        try (Session s = session())
        {
            Result rs = s.run(cypher, Values.parameters("kbId", kbId, "names", clean));
            return mapEntities(rs);
        }
    }

    @Override
    public List<GraphEntity> searchEntities(Long kbId, String query, int limit)
    {
        if (kbId == null || StringUtils.isEmpty(query) || limit <= 0)
        {
            return List.of();
        }
        // 全文索引;失败则退回 CONTAINS
        try (Session s = session())
        {
            try
            {
                String ft = """
                    CALL db.index.fulltext.queryNodes('entity_name_ft', $q)
                    YIELD node, score
                    WHERE node.kbId = $kbId
                    RETURN node AS e
                    ORDER BY score DESC
                    LIMIT $limit
                    """;
                Result rs = s.run(ft, Values.parameters("kbId", kbId, "q", escapeLucene(query), "limit", limit));
                List<GraphEntity> hits = mapEntities(rs);
                if (!hits.isEmpty())
                {
                    return hits;
                }
            }
            catch (Exception e)
            {
                log.debug("全文检索不可用,退回 CONTAINS: {}", e.getMessage());
            }
            String fallback = """
                MATCH (e:Entity {kbId: $kbId})
                WHERE toLower(e.name) CONTAINS toLower($q)
                RETURN e
                LIMIT $limit
                """;
            return mapEntities(s.run(fallback, Values.parameters("kbId", kbId, "q", query, "limit", limit)));
        }
    }

    @Override
    public List<GraphEntity> neighbors(Long kbId, String name, int hops, int limit)
    {
        if (kbId == null || StringUtils.isEmpty(name) || limit <= 0)
        {
            return List.of();
        }
        int h = Math.max(1, Math.min(hops, 2));
        // 变长路径:包含自身 + 邻居
        String cypher = """
            MATCH (start:Entity {kbId: $kbId, name: $name})
            OPTIONAL MATCH path = (start)-[:RELATED*0..%d]-(n:Entity)
            WHERE n.kbId = $kbId
            WITH DISTINCT n AS e
            RETURN e
            LIMIT $limit
            """.formatted(h);
        try (Session s = session())
        {
            return mapEntities(s.run(cypher, Values.parameters("kbId", kbId, "name", name, "limit", limit)));
        }
    }

    @Override
    public List<GraphRelation> relationsAmong(Long kbId, List<String> names, int limit)
    {
        if (kbId == null || names == null || names.isEmpty() || limit <= 0)
        {
            return List.of();
        }
        List<String> clean = names.stream()
            .filter(StringUtils::isNotEmpty)
            .map(String::trim)
            .distinct()
            .limit(200)
            .toList();
        if (clean.isEmpty())
        {
            return List.of();
        }
        int lim = Math.max(1, Math.min(limit, 300));
        String cypher = """
            MATCH (a:Entity {kbId: $kbId})-[r:RELATED {kbId: $kbId}]->(b:Entity)
            WHERE a.name IN $names AND b.name IN $names
            RETURN a.name AS source, b.name AS target, r.keywords AS keywords,
                   r.predicate AS predicate, r.description AS description,
                   r.weight AS weight, r.sourceIds AS sourceIds
            LIMIT $limit
            """;
        try (Session s = session())
        {
            Result rs = s.run(cypher, Values.parameters("kbId", kbId, "names", clean, "limit", lim));
            List<GraphRelation> out = new ArrayList<>();
            while (rs.hasNext())
            {
                Record rec = rs.next();
                GraphRelation rel = new GraphRelation();
                rel.setKbId(kbId);
                rel.setSourceName(rec.get("source").asString(""));
                rel.setTargetName(rec.get("target").asString(""));
                rel.setKeywords(rec.get("keywords").isNull() ? "" : rec.get("keywords").asString(""));
                if (!rec.get("predicate").isNull())
                {
                    rel.setPredicate(rec.get("predicate").asString(""));
                }
                rel.setDescription(rec.get("description").isNull() ? "" : rec.get("description").asString(""));
                rel.setWeight(rec.get("weight").isNull() ? 1.0 : rec.get("weight").asDouble(1.0));
                rel.setSourceIds(valueToLongList(rec.get("sourceIds")));
                out.add(rel);
            }
            return out;
        }
    }

    @Override
    public void replaceDocumentEvidence(GraphWriteBundle bundle)
    {
        if (bundle == null || bundle.getKbId() == null || bundle.getDocId() == null)
        {
            return;
        }
        Long kbId = bundle.getKbId();
        Long docId = bundle.getDocId();
        long gen = bundle.getGeneration() != null ? bundle.getGeneration() : 0L;
        Long runId = bundle.getRunId();
        try (Session s = session())
        {
            s.executeWrite(tx -> {
                deleteDocEvidenceInTx(tx, kbId, docId);
                upsertEntitiesInTx(tx, kbId, bundle.getEntities());
                writeTextUnitsInTx(tx, kbId, docId, gen, runId, bundle.getTextUnits());
                writeEvidencesInTx(tx, kbId, docId, gen, runId, bundle.getEvidences(), bundle.getRelations());
                recomputeRelatedInTx(tx, kbId);
                pruneOrphansInTx(tx, kbId);
                return null;
            });
        }
        log.debug("replaceDocumentEvidence kbId={} docId={} gen={}", kbId, docId, gen);
    }

    @Override
    public void deleteDocumentEvidence(Long kbId, Long docId)
    {
        if (kbId == null || docId == null)
        {
            return;
        }
        try (Session s = session())
        {
            s.executeWrite(tx -> {
                deleteDocEvidenceInTx(tx, kbId, docId);
                recomputeRelatedInTx(tx, kbId);
                pruneOrphansInTx(tx, kbId);
                return null;
            });
        }
        log.debug("deleteDocumentEvidence kbId={} docId={}", kbId, docId);
    }

    @Override
    public void removeChunkRefs(Long kbId, List<Long> chunkIds)
    {
        if (kbId == null || chunkIds == null || chunkIds.isEmpty())
        {
            return;
        }
        List<Long> ids = toLongList(chunkIds);
        // 优先：按 TextUnit 定位 doc 做精确证据删除
        try (Session s = session())
        {
            Result rs = s.run("""
                MATCH (t:TextUnit {kbId: $kbId})
                WHERE t.chunkId IN $chunkIds
                RETURN DISTINCT t.docId AS docId
                """, Values.parameters("kbId", kbId, "chunkIds", ids));
            List<Long> docs = new ArrayList<>();
            while (rs.hasNext())
            {
                long d = rs.next().get("docId").asLong(0);
                if (d > 0)
                {
                    docs.add(d);
                }
            }
            if (!docs.isEmpty())
            {
                for (Long docId : docs)
                {
                    deleteDocumentEvidence(kbId, docId);
                }
                return;
            }
        }
        // 兼容：无 TextUnit 时回退 sourceIds 摘除
        String stripEntity = """
            MATCH (e:Entity {kbId: $kbId})
            WHERE any(s IN coalesce(e.sourceIds, []) WHERE s IN $chunkIds)
            SET e.sourceIds = [s IN coalesce(e.sourceIds, []) WHERE NOT s IN $chunkIds],
                e.updatedAt = datetime()
            """;
        String stripRel = """
            MATCH ()-[r:RELATED {kbId: $kbId}]->()
            WHERE any(s IN coalesce(r.sourceIds, []) WHERE s IN $chunkIds)
            SET r.sourceIds = [s IN coalesce(r.sourceIds, []) WHERE NOT s IN $chunkIds],
                r.updatedAt = datetime()
            """;
        String delEmptyRel = """
            MATCH ()-[r:RELATED {kbId: $kbId}]->()
            WHERE size(coalesce(r.sourceIds, [])) = 0
            DELETE r
            """;
        String delEmptyEntity = """
            MATCH (e:Entity {kbId: $kbId})
            WHERE size(coalesce(e.sourceIds, [])) = 0
              AND NOT (e)<-[:FROM|TO]-(:RelationEvidence)
              AND NOT (e)<-[:MENTIONS]-(:TextUnit)
            DETACH DELETE e
            """;
        try (Session s = session())
        {
            var params = Values.parameters("kbId", kbId, "chunkIds", ids);
            s.run(stripEntity, params).consume();
            s.run(stripRel, params).consume();
            s.run(delEmptyRel, params).consume();
            s.run(delEmptyEntity, params).consume();
        }
        log.debug("removeChunkRefs kbId={} chunks={}", kbId, ids.size());
    }

    @Override
    public void deleteByKb(Long kbId)
    {
        if (kbId == null)
        {
            return;
        }
        try (Session s = session())
        {
            s.run("MATCH (t:TextUnit {kbId: $kbId}) DETACH DELETE t",
                Values.parameters("kbId", kbId)).consume();
            s.run("MATCH (re:RelationEvidence {kbId: $kbId}) DETACH DELETE re",
                Values.parameters("kbId", kbId)).consume();
            s.run("MATCH (e:Entity {kbId: $kbId}) DETACH DELETE e",
                Values.parameters("kbId", kbId)).consume();
            s.run("MATCH ()-[r:RELATED {kbId: $kbId}]->() DELETE r",
                Values.parameters("kbId", kbId)).consume();
        }
    }

    @Override
    public int countTextUnits(Long kbId, Long docId)
    {
        if (kbId == null)
        {
            return 0;
        }
        try (Session s = session())
        {
            if (docId == null)
            {
                return s.run("MATCH (t:TextUnit {kbId: $kbId}) RETURN count(t) AS c",
                    Values.parameters("kbId", kbId)).single().get("c").asInt(0);
            }
            return s.run("MATCH (t:TextUnit {kbId: $kbId, docId: $docId}) RETURN count(t) AS c",
                Values.parameters("kbId", kbId, "docId", docId)).single().get("c").asInt(0);
        }
    }

    @Override
    public int countRelationEvidences(Long kbId, Long docId)
    {
        if (kbId == null)
        {
            return 0;
        }
        try (Session s = session())
        {
            if (docId == null)
            {
                return s.run("MATCH (re:RelationEvidence {kbId: $kbId}) RETURN count(re) AS c",
                    Values.parameters("kbId", kbId)).single().get("c").asInt(0);
            }
            return s.run("MATCH (re:RelationEvidence {kbId: $kbId, docId: $docId}) RETURN count(re) AS c",
                Values.parameters("kbId", kbId, "docId", docId)).single().get("c").asInt(0);
        }
    }

    private static void deleteDocEvidenceInTx(TransactionContext tx, Long kbId, Long docId)
    {
        tx.run("MATCH (t:TextUnit {kbId: $kbId, docId: $docId}) DETACH DELETE t",
            Values.parameters("kbId", kbId, "docId", docId)).consume();
        tx.run("MATCH (re:RelationEvidence {kbId: $kbId, docId: $docId}) DETACH DELETE re",
            Values.parameters("kbId", kbId, "docId", docId)).consume();
    }

    private static void upsertEntitiesInTx(TransactionContext tx, Long kbId, List<GraphEntity> entities)
    {
        if (entities == null || entities.isEmpty())
        {
            return;
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (GraphEntity e : entities)
        {
            if (e == null || StringUtils.isEmpty(e.getName()))
            {
                continue;
            }
            Map<String, Object> row = new HashMap<>();
            String name = e.getName().trim();
            String entityKey = StringUtils.isNotEmpty(e.getEntityKey())
                ? e.getEntityKey() : (kbId + "|" + name);
            row.put("entityKey", entityKey);
            row.put("name", name);
            row.put("canonicalName", e.getCanonicalName() != null ? e.getCanonicalName() : name);
            row.put("candidateKey", e.getCandidateKey() != null ? e.getCandidateKey() : "");
            row.put("type", e.getType() != null ? e.getType() : "");
            row.put("description", e.getDescription() != null ? e.getDescription() : "");
            row.put("sourceIds", toLongList(e.getSourceIds()));
            row.put("filePaths", e.getFilePaths() != null ? e.getFilePaths() : List.of());
            rows.add(row);
        }
        if (rows.isEmpty())
        {
            return;
        }
        tx.run("""
            UNWIND $rows AS r
            MERGE (e:Entity {kbId: $kbId, entityKey: r.entityKey})
            ON CREATE SET e.name = r.name,
                          e.canonicalName = r.canonicalName,
                          e.candidateKey = r.candidateKey,
                          e.type = r.type,
                          e.description = r.description,
                          e.sourceIds = r.sourceIds,
                          e.filePaths = r.filePaths,
                          e.updatedAt = datetime()
            ON MATCH SET e.name = r.name,
                         e.canonicalName = r.canonicalName,
                         e.candidateKey = CASE WHEN r.candidateKey <> '' THEN r.candidateKey ELSE e.candidateKey END,
                         e.type = CASE WHEN r.type <> '' THEN r.type ELSE e.type END,
                         e.description = CASE WHEN r.description <> '' THEN r.description ELSE e.description END,
                         e.sourceIds = r.sourceIds,
                         e.filePaths = r.filePaths,
                         e.updatedAt = datetime()
            """, Values.parameters("kbId", kbId, "rows", rows)).consume();
    }

    private static void writeTextUnitsInTx(TransactionContext tx, Long kbId, Long docId, long gen,
        Long runId, List<GraphTextUnit> units)
    {
        if (units == null || units.isEmpty())
        {
            return;
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (GraphTextUnit tu : units)
        {
            if (tu == null || tu.getChunkId() == null)
            {
                continue;
            }
            Map<String, Object> row = new HashMap<>();
            row.put("chunkId", tu.getChunkId());
            row.put("contentHash", tu.getContentHash() != null ? tu.getContentHash() : "");
            row.put("headingPath", tu.getHeadingPath() != null ? tu.getHeadingPath() : "");
            row.put("sourceLabel", tu.getSourceLabel() != null ? tu.getSourceLabel() : "");
            row.put("pageFrom", tu.getSourcePageFrom());
            row.put("pageTo", tu.getSourcePageTo());
            rows.add(row);
        }
        if (rows.isEmpty())
        {
            return;
        }
        tx.run("""
            UNWIND $rows AS r
            MERGE (t:TextUnit {kbId: $kbId, docId: $docId, chunkId: r.chunkId})
            SET t.generation = $gen,
                t.runId = $runId,
                t.contentHash = r.contentHash,
                t.headingPath = r.headingPath,
                t.sourceLabel = r.sourceLabel,
                t.sourcePageFrom = r.pageFrom,
                t.sourcePageTo = r.pageTo,
                t.updatedAt = datetime()
            """, Values.parameters("kbId", kbId, "docId", docId, "gen", gen, "runId", runId, "rows", rows))
            .consume();
    }

    private static void writeEvidencesInTx(TransactionContext tx, Long kbId, Long docId, long gen,
        Long runId, List<GraphRelationEvidence> evidences, List<GraphRelation> relations)
    {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (evidences != null)
        {
            for (GraphRelationEvidence ev : evidences)
            {
                if (ev == null || StringUtils.isEmpty(ev.getSourceEntityKey())
                    || StringUtils.isEmpty(ev.getTargetEntityKey()))
                {
                    continue;
                }
                // sourceEntityKey 字段历史混用：可能是 entityKey 或展示名
                String sk = resolveEntityKey(kbId, ev.getSourceEntityKey());
                String tk = resolveEntityKey(kbId, ev.getTargetEntityKey());
                String sn = displayNameOf(ev.getSourceEntityKey());
                String tn = displayNameOf(ev.getTargetEntityKey());
                if (sk.equals(tk))
                {
                    continue;
                }
                Map<String, Object> row = new HashMap<>();
                row.put("sourceKey", sk);
                row.put("targetKey", tk);
                row.put("sourceName", sn);
                row.put("targetName", tn);
                row.put("predicate", ev.getPredicate() != null ? ev.getPredicate() : "");
                row.put("description", ev.getDescription() != null ? ev.getDescription() : "");

                row.put("chunkId", ev.getTextUnitChunkId());
                row.put("evidenceKey", kbId + "|" + docId + "|" + sk + "|" + row.get("predicate") + "|" + tk
                    + "|" + (ev.getTextUnitChunkId() == null ? "_" : ev.getTextUnitChunkId()));
                rows.add(row);
            }
        }
        if (rows.isEmpty() && relations != null)
        {
            for (GraphRelation r : relations)
            {
                if (r == null || StringUtils.isEmpty(r.getSourceName()) || StringUtils.isEmpty(r.getTargetName()))
                {
                    continue;
                }
                String sn = r.getSourceName().trim();
                String tn = r.getTargetName().trim();
                String sk = StringUtils.isNotEmpty(r.getSourceEntityKey())
                    ? r.getSourceEntityKey().trim() : resolveEntityKey(kbId, sn);
                String tk = StringUtils.isNotEmpty(r.getTargetEntityKey())
                    ? r.getTargetEntityKey().trim() : resolveEntityKey(kbId, tn);
                if (sk.equals(tk))
                {
                    continue;
                }
                Long chunkId = (r.getSourceIds() != null && !r.getSourceIds().isEmpty())
                    ? r.getSourceIds().get(0) : null;
                Map<String, Object> row = new HashMap<>();
                row.put("sourceKey", sk);
                row.put("targetKey", tk);
                row.put("sourceName", sn);
                row.put("targetName", tn);
                row.put("predicate", r.getKeywords() != null ? r.getKeywords() : "");
                row.put("description", r.getDescription() != null ? r.getDescription() : "");
                row.put("chunkId", chunkId);
                row.put("evidenceKey", kbId + "|" + docId + "|" + sk + "|" + row.get("predicate") + "|" + tk
                    + "|" + (chunkId == null ? "_" : chunkId));
                rows.add(row);
            }
        }
        if (rows.isEmpty())
        {
            return;
        }
        // 确保端点实体存在（按 entityKey MERGE，name 仅展示）。
        // 修复：关系端点的 entityKey 可能不含类型段(与实体节点 key 分裂成两个节点)，
        // 这里在创建/更新空壳端点时，继承同名实体的 type/description，让空壳自愈成完整实体，
        // 避免详情接口只查到空壳导致「暂无描述」。
        tx.run("""
            UNWIND $rows AS r
            OPTIONAL MATCH (fullA:Entity {kbId: $kbId, name: r.sourceName})
            WHERE fullA.description IS NOT NULL AND fullA.description <> ''
            WITH r, coalesce(fullA.description, '') AS srcDescA, coalesce(fullA.type, '') AS srcTypeA
            OPTIONAL MATCH (fullB:Entity {kbId: $kbId, name: r.targetName})
            WHERE fullB.description IS NOT NULL AND fullB.description <> ''
            WITH r, srcDescA, srcTypeA, coalesce(fullB.description, '') AS srcDescB, coalesce(fullB.type, '') AS srcTypeB
            MERGE (a:Entity {kbId: $kbId, entityKey: r.sourceKey})
            ON CREATE SET a.name = r.sourceName, a.type = srcTypeA, a.description = srcDescA,
                          a.sourceIds = [], a.filePaths = [], a.updatedAt = datetime()
            ON MATCH SET a.name = CASE WHEN r.sourceName <> '' THEN r.sourceName ELSE a.name END,
                         a.type = CASE WHEN srcTypeA <> '' AND (a.type IS NULL OR a.type = '') THEN srcTypeA ELSE a.type END,
                         a.description = CASE WHEN srcDescA <> '' AND (a.description IS NULL OR a.description = '') THEN srcDescA ELSE a.description END,
                         a.updatedAt = datetime()
            MERGE (b:Entity {kbId: $kbId, entityKey: r.targetKey})
            ON CREATE SET b.name = r.targetName, b.type = srcTypeB, b.description = srcDescB,
                          b.sourceIds = [], b.filePaths = [], b.updatedAt = datetime()
            ON MATCH SET b.name = CASE WHEN r.targetName <> '' THEN r.targetName ELSE b.name END,
                         b.type = CASE WHEN srcTypeB <> '' AND (b.type IS NULL OR b.type = '') THEN srcTypeB ELSE b.type END,
                         b.description = CASE WHEN srcDescB <> '' AND (b.description IS NULL OR b.description = '') THEN srcDescB ELSE b.description END,
                         b.updatedAt = datetime()
            """, Values.parameters("kbId", kbId, "rows", rows)).consume();
        tx.run("""
            UNWIND $rows AS r
            MATCH (a:Entity {kbId: $kbId, entityKey: r.sourceKey})
            MATCH (b:Entity {kbId: $kbId, entityKey: r.targetKey})
            MERGE (re:RelationEvidence {kbId: $kbId, evidenceKey: r.evidenceKey})
            SET re.docId = $docId,
                re.generation = $gen,
                re.runId = $runId,
                re.predicate = r.predicate,
                re.description = r.description,
                re.chunkId = r.chunkId,
                re.updatedAt = datetime()
            WITH re, a, b, r
            OPTIONAL MATCH (re)-[oldF:FROM]->() DELETE oldF
            WITH re, a, b, r
            OPTIONAL MATCH (re)-[oldT:TO]->() DELETE oldT
            WITH re, a, b, r
            MERGE (re)-[:FROM]->(a)
            MERGE (re)-[:TO]->(b)
            WITH re, r
            OPTIONAL MATCH (t:TextUnit {kbId: $kbId, docId: $docId, chunkId: r.chunkId})
            FOREACH (_ IN CASE WHEN t IS NULL THEN [] ELSE [1] END |
              MERGE (t)-[:SUPPORTS]->(re)
            )
            """, Values.parameters("kbId", kbId, "docId", docId, "gen", gen, "runId", runId, "rows", rows))
            .consume();
    }

    /**
     * 展示名或已是 entityKey → 规范 entityKey。
     * 约定 entityKey 含 '|' 且以 kbId 开头。
     */
    private static String resolveEntityKey(Long kbId, String raw)
    {
        if (raw == null || raw.isBlank())
        {
            return (kbId == null ? "0" : kbId) + "|_||";
        }
        String t = raw.trim();
        String prefix = (kbId == null ? "0" : kbId) + "|";
        if (t.startsWith(prefix) && t.chars().filter(ch -> ch == '|').count() >= 2)
        {
            return t;
        }
        return prefix + t + "||";
    }

    private static String displayNameOf(String rawOrKey)
    {
        if (rawOrKey == null || rawOrKey.isBlank())
        {
            return "";
        }
        String t = rawOrKey.trim();
        // entityKey: kbId|name|type|sense
        int c = (int) t.chars().filter(ch -> ch == '|').count();
        if (c >= 2)
        {
            int first = t.indexOf('|');
            int second = t.indexOf('|', first + 1);
            if (first >= 0 && second > first)
            {
                return t.substring(first + 1, second);
            }
        }
        return t;
    }

    private static void recomputeRelatedInTx(TransactionContext tx, Long kbId)
    {
        // 先删聚合边，再从证据重算（含权重=证据条数、sourceIds 展示缓存）
        tx.run("MATCH ()-[r:RELATED {kbId: $kbId}]->() DELETE r",
            Values.parameters("kbId", kbId)).consume();
        tx.run("""
            MATCH (re:RelationEvidence {kbId: $kbId})-[:FROM]->(a:Entity)
            MATCH (re)-[:TO]->(b:Entity)
            WITH a, b, collect(re) AS evs
            WITH a, b, evs,
                 [x IN evs WHERE x.chunkId IS NOT NULL | x.chunkId] AS chunkIds,
                 head(evs).predicate AS predicate,
                 head(evs).description AS description
            MERGE (a)-[rel:RELATED {kbId: $kbId}]->(b)
            SET rel.weight = size(evs),
                rel.keywords = coalesce(predicate, ''),
                rel.description = coalesce(description, ''),
                rel.sourceIds = chunkIds[0..10],
                rel.updatedAt = datetime()
            """, Values.parameters("kbId", kbId)).consume();
        // 从证据回填实体 sourceIds 展示缓存（完整血统在 RelationEvidence.chunkId）
        tx.run("""
            MATCH (e:Entity {kbId: $kbId})
            OPTIONAL MATCH (re:RelationEvidence {kbId: $kbId})-[:FROM|TO]->(e)
            WITH e, [x IN collect(DISTINCT re.chunkId) WHERE x IS NOT NULL] AS ids
            SET e.sourceIds = ids[0..10],
                e.updatedAt = datetime()
            """, Values.parameters("kbId", kbId)).consume();
    }

    private static void pruneOrphansInTx(TransactionContext tx, Long kbId)
    {
        // 只清理「无关系、无来源、无描述」的纯空壳；有描述的实体即使暂时孤立也保留，
        // 否则关系端点 key 分裂会让带描述的实体节点被误删，详情只剩空壳。
        tx.run("""
            MATCH (e:Entity {kbId: $kbId})
            WHERE NOT (e)<-[:FROM|TO]-(:RelationEvidence)
              AND NOT (e)<-[:MENTIONS]-(:TextUnit)
              AND size(coalesce(e.sourceIds, [])) = 0
              AND (e.description IS NULL OR e.description = '')
              AND (e.type IS NULL OR e.type = '')
            DETACH DELETE e
            """, Values.parameters("kbId", kbId)).consume();
    }

    @Override
    public List<GraphEntity> listEntities(Long kbId, int limit)
    {
        if (kbId == null)
        {
            return List.of();
        }
        int lim = Math.max(1, Math.min(limit, 500));
        try (Session s = session())
        {
            // 优先来源多的实体，探索默认种子更稳
            return mapEntities(s.run(
                """
                MATCH (e:Entity {kbId: $kbId})
                RETURN e
                ORDER BY size(coalesce(e.sourceIds, [])) DESC, e.name
                LIMIT $limit
                """,
                Values.parameters("kbId", kbId, "limit", lim)));
        }
    }

    @Override
    public List<GraphEntity> listEntitiesBySourceChunks(Long kbId, java.util.Collection<Long> chunkIds, int limit)
    {
        if (kbId == null || chunkIds == null || chunkIds.isEmpty() || limit <= 0)
        {
            return List.of();
        }
        List<Long> ids = toLongList(new ArrayList<>(chunkIds));
        if (ids.isEmpty())
        {
            return List.of();
        }
        int lim = Math.max(1, Math.min(limit, 500));
        try (Session s = session())
        {
            // 完整血统在 RelationEvidence；sourceIds 为展示缓存，优先命中
            // 再并上「证据边指向本 chunk」的实体，避免 sourceIds 截断到 10 导致单文件空图
            return mapEntities(s.run(
                """
                MATCH (e:Entity {kbId: $kbId})
                WHERE any(x IN coalesce(e.sourceIds, []) WHERE x IN $chunkIds)
                   OR EXISTS {
                     MATCH (re:RelationEvidence {kbId: $kbId})-[:FROM|TO]->(e)
                     WHERE re.chunkId IN $chunkIds
                   }
                RETURN e
                ORDER BY size(coalesce(e.sourceIds, [])) DESC, e.name
                LIMIT $limit
                """,
                Values.parameters("kbId", kbId, "chunkIds", ids, "limit", lim)));
        }
        catch (Exception e)
        {
            log.warn("listEntitiesBySourceChunks 失败, 回退: {}", e.getMessage());
            return KbGraphStore.super.listEntitiesBySourceChunks(kbId, ids, lim);
        }
    }

    @Override
    public List<GraphRelation> listRelations(Long kbId, int limit)
    {
        if (kbId == null)
        {
            return List.of();
        }
        int lim = Math.max(1, Math.min(limit, 500));
        String cypher = """
            MATCH (a:Entity {kbId: $kbId})-[r:RELATED {kbId: $kbId}]->(b:Entity)
            RETURN a.name AS source, b.name AS target, r.keywords AS keywords,
                   r.description AS description, r.weight AS weight, r.sourceIds AS sourceIds
            LIMIT $limit
            """;
        try (Session s = session())
        {
            Result rs = s.run(cypher, Values.parameters("kbId", kbId, "limit", lim));
            List<GraphRelation> out = new ArrayList<>();
            while (rs.hasNext())
            {
                Record rec = rs.next();
                GraphRelation rel = new GraphRelation();
                rel.setKbId(kbId);
                rel.setSourceName(rec.get("source").asString(""));
                rel.setTargetName(rec.get("target").asString(""));
                rel.setKeywords(rec.get("keywords").isNull() ? "" : rec.get("keywords").asString(""));
                rel.setDescription(rec.get("description").isNull() ? "" : rec.get("description").asString(""));
                rel.setWeight(rec.get("weight").isNull() ? 1.0 : rec.get("weight").asDouble(1.0));
                rel.setSourceIds(valueToLongList(rec.get("sourceIds")));
                out.add(rel);
            }
            return out;
        }
    }

    @Override
    public int countEntities(Long kbId)
    {
        if (kbId == null)
        {
            return 0;
        }
        try (Session s = session())
        {
            return s.run("MATCH (e:Entity {kbId: $kbId}) RETURN count(e) AS c",
                Values.parameters("kbId", kbId)).single().get("c").asInt(0);
        }
    }

    @Override
    public int countRelations(Long kbId)
    {
        if (kbId == null)
        {
            return 0;
        }
        try (Session s = session())
        {
            return s.run("MATCH ()-[r:RELATED {kbId: $kbId}]->() RETURN count(r) AS c",
                Values.parameters("kbId", kbId)).single().get("c").asInt(0);
        }
    }

    private static List<GraphEntity> mapEntities(Result rs)
    {
        List<GraphEntity> out = new ArrayList<>();
        while (rs.hasNext())
        {
            Record rec = rs.next();
            org.neo4j.driver.Value v = rec.get("e");
            if (v == null || v.isNull())
            {
                continue;
            }
            var node = v.asNode();
            GraphEntity e = new GraphEntity();
            e.setKbId(node.get("kbId").asLong(0));
            e.setName(node.get("name").asString(""));
            e.setType(node.containsKey("type") && !node.get("type").isNull()
                ? node.get("type").asString("") : "");
            e.setDescription(node.containsKey("description") && !node.get("description").isNull()
                ? node.get("description").asString("") : "");
            if (node.containsKey("entityKey") && !node.get("entityKey").isNull())
            {
                e.setEntityKey(node.get("entityKey").asString(""));
            }
            if (node.containsKey("candidateKey") && !node.get("candidateKey").isNull())
            {
                e.setCandidateKey(node.get("candidateKey").asString(""));
            }
            if (node.containsKey("canonicalName") && !node.get("canonicalName").isNull())
            {
                e.setCanonicalName(node.get("canonicalName").asString(""));
            }
            e.setSourceIds(valueToLongList(node.get("sourceIds")));
            if (node.containsKey("filePaths") && !node.get("filePaths").isNull())
            {
                e.setFilePaths(node.get("filePaths").asList(org.neo4j.driver.Value::asString));
            }
            out.add(e);
        }
        return out;
    }

    private static List<Long> toLongList(List<Long> ids)
    {
        if (ids == null || ids.isEmpty())
        {
            return List.of();
        }
        List<Long> out = new ArrayList<>();
        for (Long id : ids)
        {
            if (id != null)
            {
                out.add(id);
            }
        }
        return out;
    }

    private static List<Long> valueToLongList(org.neo4j.driver.Value v)
    {
        if (v == null || v.isNull())
        {
            return new ArrayList<>();
        }
        List<Long> out = new ArrayList<>();
        for (Object o : v.asList())
        {
            if (o instanceof Number n)
            {
                out.add(n.longValue());
            }
        }
        return out;
    }

    /** 全文查询简单转义,避免特殊字符炸索引 */
    private static String escapeLucene(String q)
    {
        if (q == null)
        {
            return "";
        }
        // 包成短语,减少语法炸裂;过长截断
        String cleaned = q.replace("\"", " ").trim();
        if (cleaned.length() > 200)
        {
            cleaned = cleaned.substring(0, 200);
        }
        return "\"" + cleaned + "\"";
    }
}
