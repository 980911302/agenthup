package com.ruoyi.system.kb.graph.community;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Values;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import com.ruoyi.system.domain.KbGraphCommunity;
import com.ruoyi.system.domain.KbGraphEntityCommunity;

/**
 * Neo4j GDS Leiden 社区检测。GDS 不可用时 probe 失败并由上层降级。
 * <p>projection 在 finally 中删除。
 */
@Component("neo4jGdsCommunityDetector")
@ConditionalOnBean(Driver.class)
@ConditionalOnProperty(name = "ai.kb.graph.community.enabled", havingValue = "true")
public class Neo4jGdsCommunityDetector implements KbCommunityDetector
{
    private static final Logger log = LoggerFactory.getLogger(Neo4jGdsCommunityDetector.class);
    public static final String COMMUNITY_VERSION = "leiden-gds-1";

    @Autowired
    private Driver driver;

    @Value("${ai.kb.neo4j.database:neo4j}")
    private String database;

    @Value("${ai.kb.graph.community.random-seed:42}")
    private long randomSeed;

    @Value("${ai.kb.graph.community.max-projected-nodes:200000}")
    private long maxProjectedNodes;

    @Value("${ai.kb.graph.community.max-projected-relationships:1000000}")
    private long maxProjectedRelationships;

    @Override
    public GdsCapability probe()
    {
        try (Session s = session())
        {
            Result rs = s.run("RETURN gds.version() AS v");
            if (!rs.hasNext())
            {
                return GdsCapability.unavailable("gds.version 无返回");
            }
            String v = rs.next().get("v").asString();
            // 确认 leiden 过程存在
            Result proc = s.run(
                "SHOW PROCEDURES YIELD name WHERE name = 'gds.leiden.stream' RETURN count(*) AS c");
            long c = proc.single().get("c").asLong(0);
            if (c <= 0)
            {
                return GdsCapability.unavailable("缺少 gds.leiden.stream");
            }
            return GdsCapability.ok(v);
        }
        catch (Exception e)
        {
            return GdsCapability.unavailable("GDS 不可用: " + e.getMessage());
        }
    }

    @Override
    public CommunityDetectionResult detect(Long kbId, String graphVersion)
    {
        CommunityDetectionResult out = new CommunityDetectionResult();
        out.setGraphVersion(graphVersion);
        out.setAlgorithm("leiden");
        GdsCapability cap = probe();
        out.setGdsVersion(cap.getVersion());
        if (!cap.isAvailable())
        {
            out.setAvailable(false);
            out.setSkipReason(cap.getReason());
            return out;
        }
        out.setAvailable(true);
        String proj = projectionName(kbId, graphVersion);
        try (Session s = session())
        {
            // 规模检查
            long nodes = s.run("MATCH (e:Entity {kbId: $kbId}) RETURN count(e) AS c",
                Values.parameters("kbId", kbId)).single().get("c").asLong(0);
            long rels = s.run(
                "MATCH (:Entity {kbId: $kbId})-[r:RELATED {kbId: $kbId}]->(:Entity) RETURN count(r) AS c",
                Values.parameters("kbId", kbId)).single().get("c").asLong(0);
            out.setEntityCount((int) nodes);
            out.setRelationCount((int) rels);
            if (nodes == 0)
            {
                out.setLevelCount(0);
                return out;
            }
            if (nodes > maxProjectedNodes || rels > maxProjectedRelationships)
            {
                out.setAvailable(false);
                out.setSkipReason("图规模超过投影上限 nodes=" + nodes + " rels=" + rels);
                return out;
            }

            // memory estimate（失败不阻断，仅日志）
            try
            {
                s.run("""
                    CALL gds.graph.project.cypher.estimate(
                      'MATCH (e:Entity {kbId: $kbId}) RETURN id(e) AS id',
                      'MATCH (a:Entity {kbId: $kbId})-[r:RELATED {kbId: $kbId}]->(b:Entity)
                       RETURN id(a) AS source, id(b) AS target, coalesce(r.weight, 1.0) AS weight',
                      {parameters: {kbId: $kbId}}
                    ) YIELD requiredMemory, bytesMin
                    RETURN requiredMemory, bytesMin
                    """, Values.parameters("kbId", kbId)).consume();
            }
            catch (Exception e)
            {
                log.debug("GDS memory estimate 跳过: {}", e.getMessage());
            }

            // drop if exists then project
            dropProjectionQuietly(s, proj);
            s.run("""
                CALL gds.graph.project.cypher(
                  $proj,
                  'MATCH (e:Entity {kbId: $kbId}) RETURN id(e) AS id, labels(e) AS labels',
                  'MATCH (a:Entity {kbId: $kbId})-[r:RELATED {kbId: $kbId}]->(b:Entity)
                   RETURN id(a) AS source, id(b) AS target, coalesce(r.weight, 1.0) AS weight',
                  {parameters: {kbId: $kbId}, validateRelationships: false}
                )
                """, Values.parameters("proj", proj, "kbId", kbId)).consume();

            Result stream = s.run("""
                CALL gds.leiden.stream($proj, {
                  relationshipWeightProperty: 'weight',
                  randomSeed: $seed,
                  includeIntermediateCommunities: true,
                  concurrency: 4
                })
                YIELD nodeId, communityId, intermediateCommunityIds
                MATCH (e:Entity) WHERE id(e) = nodeId AND e.kbId = $kbId
                RETURN e.entityKey AS entityKey, e.name AS entityName,
                       communityId AS topCommunity,
                       intermediateCommunityIds AS intermediates
                """, Values.parameters("proj", proj, "seed", randomSeed, "kbId", kbId));

            Map<String, List<Long>> entityLevels = new LinkedHashMap<>();
            int maxDepth = 1;
            while (stream.hasNext())
            {
                Record rec = stream.next();
                String entityKey = rec.get("entityKey").isNull()
                    ? (kbId + "|" + rec.get("entityName").asString(""))
                    : rec.get("entityKey").asString();
                String entityName = rec.get("entityName").asString("");
                long top = rec.get("topCommunity").asLong();
                List<Object> inter = rec.get("intermediates").isNull()
                    ? List.of() : rec.get("intermediates").asList();
                List<Long> levels = new ArrayList<>();
                if (inter != null && !inter.isEmpty())
                {
                    for (Object o : inter)
                    {
                        if (o instanceof Number n)
                        {
                            levels.add(n.longValue());
                        }
                    }
                }
                if (levels.isEmpty())
                {
                    levels.add(top);
                }
                // 保证最后一层是 top
                if (levels.get(levels.size() - 1) != top)
                {
                    levels.add(top);
                }
                maxDepth = Math.max(maxDepth, levels.size());
                entityLevels.put(entityKey + "\0" + entityName, levels);
            }

            // 构建 memberships + communities hierarchy
            Map<String, KbGraphCommunity> communityMap = new HashMap<>();
            List<KbGraphEntityCommunity> memberships = new ArrayList<>();
            for (Map.Entry<String, List<Long>> e : entityLevels.entrySet())
            {
                String[] parts = e.getKey().split("\0", 2);
                String entityKey = parts[0];
                String entityName = parts.length > 1 ? parts[1] : "";
                List<Long> levels = e.getValue();
                for (int level = 0; level < levels.size(); level++)
                {
                    final int levelFinal = level;
                    final long cid = levels.get(level);
                    final Long parent = level + 1 < levels.size() ? levels.get(level + 1) : null;
                    String ck = level + "|" + cid;
                    communityMap.computeIfAbsent(ck, k -> {
                        KbGraphCommunity c = new KbGraphCommunity();
                        c.setKbId(kbId);
                        c.setGraphVersion(graphVersion);
                        c.setLevel(levelFinal);
                        c.setCommunityId(cid);
                        c.setParentCommunityId(parent);
                        c.setEntityCount(0);
                        c.setRank(0);
                        return c;
                    });
                    // parent 可能更新
                    KbGraphCommunity c = communityMap.get(ck);
                    if (parent != null)
                    {
                        c.setParentCommunityId(parent);
                    }
                    c.setEntityCount(c.getEntityCount() + 1);

                    KbGraphEntityCommunity m = new KbGraphEntityCommunity();
                    m.setKbId(kbId);
                    m.setGraphVersion(graphVersion);
                    m.setLevel(level);
                    m.setCommunityId(cid);
                    m.setEntityKey(entityKey);
                    m.setEntityName(entityName);
                    memberships.add(m);
                }
            }

            out.setCommunities(new ArrayList<>(communityMap.values()));
            out.setMemberships(memberships);
            out.setLevelCount(maxDepth);
            return out;
        }
        catch (Exception e)
        {
            log.warn("Leiden 检测失败 kbId={}: {}", kbId, e.getMessage());
            out.setAvailable(false);
            out.setSkipReason("leiden_error: " + e.getMessage());
            return out;
        }
        finally
        {
            try (Session s = session())
            {
                dropProjectionQuietly(s, proj);
            }
            catch (Exception e)
            {
                log.debug("清理 projection 失败 {}: {}", proj, e.getMessage());
            }
        }
    }

    private Session session()
    {
        return driver.session(SessionConfig.builder().withDatabase(database).build());
    }

    private static String projectionName(Long kbId, String graphVersion)
    {
        String gv = graphVersion == null ? "na" : graphVersion.replaceAll("[^a-zA-Z0-9_-]", "");
        if (gv.length() > 24)
        {
            gv = gv.substring(0, 24);
        }
        return "kb_comm_" + kbId + "_" + gv;
    }

    private static void dropProjectionQuietly(Session s, String proj)
    {
        try
        {
            s.run("CALL gds.graph.drop($proj, false)", Values.parameters("proj", proj)).consume();
        }
        catch (Exception ignored)
        {
            // ignore
        }
    }
}
