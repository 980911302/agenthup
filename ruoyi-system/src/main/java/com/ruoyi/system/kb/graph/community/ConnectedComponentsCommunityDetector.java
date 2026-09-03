package com.ruoyi.system.kb.graph.community;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import com.ruoyi.system.domain.KbGraphCommunity;
import com.ruoyi.system.domain.KbGraphEntityCommunity;
import com.ruoyi.system.kb.graph.GraphEntity;
import com.ruoyi.system.kb.graph.GraphRelation;

/**
 * 进程内连通分量 + 固定 seed 分层（合约测 / GDS 不可用时的可验证近似）。
 * <p>Level 0：每个连通分量内按 seed 稳定哈希分桶（非孤立实体必有社区）。
 * Level 1：分量合并为父社区。孤立点（无边）单独成社区。
 */
public class ConnectedComponentsCommunityDetector implements KbCommunityDetector
{
    public static final String COMMUNITY_VERSION = "cc-hier-1";

    private final long randomSeed;

    public ConnectedComponentsCommunityDetector()
    {
        this(42L);
    }

    public ConnectedComponentsCommunityDetector(long randomSeed)
    {
        this.randomSeed = randomSeed;
    }

    @Override
    public GdsCapability probe()
    {
        return GdsCapability.ok("in-memory-cc");
    }

    @Override
    public CommunityDetectionResult detect(Long kbId, String graphVersion)
    {
        throw new UnsupportedOperationException("use detect(kbId, graphVersion, entities, relations)");
    }

    public CommunityDetectionResult detect(Long kbId, String graphVersion,
        List<GraphEntity> entities, List<GraphRelation> relations)
    {
        CommunityDetectionResult result = new CommunityDetectionResult();
        result.setAvailable(true);
        result.setAlgorithm("connected-components-hier");
        result.setGdsVersion("in-memory");
        result.setGraphVersion(graphVersion);
        if (kbId == null || entities == null || entities.isEmpty())
        {
            result.setLevelCount(0);
            return result;
        }

        Map<String, String> keyToName = new LinkedHashMap<>();
        for (GraphEntity e : entities)
        {
            if (e == null || e.getName() == null)
            {
                continue;
            }
            String key = e.getEntityKey() != null ? e.getEntityKey() : (kbId + "|" + e.getName());
            keyToName.put(key, e.getName());
        }
        if (keyToName.isEmpty())
        {
            return result;
        }

        Map<String, Set<String>> adj = new HashMap<>();
        for (String k : keyToName.keySet())
        {
            adj.put(k, new HashSet<>());
        }
        int relCount = 0;
        if (relations != null)
        {
            for (GraphRelation r : relations)
            {
                if (r == null || r.getSourceName() == null || r.getTargetName() == null)
                {
                    continue;
                }
                String sk = findKeyByName(keyToName, r.getSourceName());
                String tk = findKeyByName(keyToName, r.getTargetName());
                if (sk == null || tk == null || sk.equals(tk))
                {
                    continue;
                }
                adj.get(sk).add(tk);
                adj.get(tk).add(sk);
                relCount++;
            }
        }

        // 连通分量
        List<List<String>> components = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        for (String k : keyToName.keySet())
        {
            if (visited.contains(k))
            {
                continue;
            }
            List<String> comp = new ArrayList<>();
            dfs(k, adj, visited, comp);
            Collections.sort(comp);
            components.add(comp);
        }
        components.sort((a, b) -> {
            int c = Integer.compare(b.size(), a.size());
            return c != 0 ? c : a.get(0).compareTo(b.get(0));
        });

        List<KbGraphEntityCommunity> memberships = new ArrayList<>();
        List<KbGraphCommunity> communities = new ArrayList<>();
        Map<Long, Long> leafToParent = new LinkedHashMap<>();
        long nextId = 1L;

        // Level 0: 大分量按 seed 分成最多 4 个桶，小分量/孤立点单独
        for (List<String> comp : components)
        {
            long parentId = nextId++;
            List<List<String>> buckets = splitStable(comp, randomSeed);
            for (List<String> bucket : buckets)
            {
                long leafId = nextId++;
                leafToParent.put(leafId, parentId);
                for (String ek : bucket)
                {
                    KbGraphEntityCommunity m = new KbGraphEntityCommunity();
                    m.setKbId(kbId);
                    m.setGraphVersion(graphVersion);
                    m.setLevel(0);
                    m.setCommunityId(leafId);
                    m.setEntityKey(ek);
                    m.setEntityName(keyToName.get(ek));
                    memberships.add(m);
                }
                KbGraphCommunity c = new KbGraphCommunity();
                c.setKbId(kbId);
                c.setGraphVersion(graphVersion);
                c.setLevel(0);
                c.setCommunityId(leafId);
                c.setParentCommunityId(parentId);
                c.setEntityCount(bucket.size());
                c.setRelationCount(0);
                c.setRank(0);
                c.setContentHash(hash(bucket.toString() + randomSeed));
                communities.add(c);
            }
            KbGraphCommunity parent = new KbGraphCommunity();
            parent.setKbId(kbId);
            parent.setGraphVersion(graphVersion);
            parent.setLevel(1);
            parent.setCommunityId(parentId);
            parent.setParentCommunityId(null);
            parent.setEntityCount(comp.size());
            parent.setRelationCount(0);
            parent.setRank(0);
            parent.setContentHash(hash(comp.toString() + randomSeed + "|L1"));
            communities.add(parent);
            for (String ek : comp)
            {
                KbGraphEntityCommunity m = new KbGraphEntityCommunity();
                m.setKbId(kbId);
                m.setGraphVersion(graphVersion);
                m.setLevel(1);
                m.setCommunityId(parentId);
                m.setEntityKey(ek);
                m.setEntityName(keyToName.get(ek));
                memberships.add(m);
            }
        }

        // 填 parent 引用完整性检查：level0 的 parent 必须存在
        Set<Long> level1Ids = new HashSet<>();
        for (KbGraphCommunity c : communities)
        {
            if (c.getLevel() != null && c.getLevel() == 1)
            {
                level1Ids.add(c.getCommunityId());
            }
        }
        for (KbGraphCommunity c : communities)
        {
            if (c.getLevel() != null && c.getLevel() == 0 && c.getParentCommunityId() != null)
            {
                if (!level1Ids.contains(c.getParentCommunityId()))
                {
                    c.setParentCommunityId(null);
                }
            }
        }

        result.setCommunities(communities);
        result.setMemberships(memberships);
        result.setLevelCount(2);
        result.setEntityCount(keyToName.size());
        result.setRelationCount(relCount);
        return result;
    }

    private static void dfs(String k, Map<String, Set<String>> adj, Set<String> visited, List<String> comp)
    {
        visited.add(k);
        comp.add(k);
        for (String n : adj.getOrDefault(k, Set.of()))
        {
            if (!visited.contains(n))
            {
                dfs(n, adj, visited, comp);
            }
        }
    }

    /** 固定 seed 的稳定分桶：1 个元素 1 桶；否则最多 4 桶 */
    private static List<List<String>> splitStable(List<String> sortedKeys, long seed)
    {
        if (sortedKeys.size() <= 3)
        {
            return List.of(new ArrayList<>(sortedKeys));
        }
        int buckets = Math.min(4, sortedKeys.size());
        List<List<String>> out = new ArrayList<>();
        for (int i = 0; i < buckets; i++)
        {
            out.add(new ArrayList<>());
        }
        for (String k : sortedKeys)
        {
            int h = Math.floorMod(hashInt(k + "|" + seed), buckets);
            out.get(h).add(k);
        }
        List<List<String>> nonEmpty = new ArrayList<>();
        for (List<String> b : out)
        {
            if (!b.isEmpty())
            {
                nonEmpty.add(b);
            }
        }
        return nonEmpty;
    }

    private static String findKeyByName(Map<String, String> keyToName, String name)
    {
        for (Map.Entry<String, String> e : keyToName.entrySet())
        {
            if (name.equals(e.getValue()))
            {
                return e.getKey();
            }
        }
        return null;
    }

    private static int hashInt(String s)
    {
        return hash(s).hashCode();
    }

    private static String hash(String s)
    {
        try
        {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++)
            {
                sb.append(String.format("%02x", dig[i]));
            }
            return sb.toString();
        }
        catch (Exception e)
        {
            return Integer.toHexString(s.hashCode());
        }
    }
}
