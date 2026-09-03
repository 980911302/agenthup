package com.ruoyi.system.kb.vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.KbChunk;
import com.ruoyi.system.mapper.KbChunkMapper;
import com.ruoyi.system.mapper.KbVectorMapper;

/**
 * PostgreSQL + pgvector 向量存储。
 * <p>按维度路由到预建表 kb_vector_{dim}(768/1024/1536/3072)。
 * {@code @Primary} 顶掉 {@link InMemoryKbVectorStore}。
 * <p>无 {@code @DataSource}:由调用方保证已切到 SLAVE,避免嵌套 AOP clear 父上下文。
 */
@Component
@Primary
public class PgVectorKbVectorStore implements KbVectorStore
{
    private static final Logger log = LoggerFactory.getLogger(PgVectorKbVectorStore.class);

    /** 预建维度;未覆盖的报错,不动态建表 */
    private static final Set<Integer> SUPPORTED_DIMS = Set.of(768, 1024, 1536, 3072);

    @Autowired
    private KbVectorMapper kbVectorMapper;

    @Autowired
    private KbChunkMapper kbChunkMapper;

    @Override
    public void upsert(Long kbId, List<KbChunkVector> vectors)
    {
        if (kbId == null || vectors == null || vectors.isEmpty())
        {
            return;
        }
        // 按维度分组
        Map<Integer, List<Map<String, Object>>> byDim = new HashMap<>();
        for (KbChunkVector v : vectors)
        {
            if (v.getChunkId() == null || v.getEmbedding() == null)
            {
                continue;
            }
            int dim = v.getEmbedding().length;
            requireDim(dim);
            Map<String, Object> row = new HashMap<>();
            row.put("chunkId", v.getChunkId());
            row.put("kbId", kbId);
            row.put("embedding", toVectorLiteral(v.getEmbedding()));
            byDim.computeIfAbsent(dim, k -> new ArrayList<>()).add(row);
        }
        for (Map.Entry<Integer, List<Map<String, Object>>> e : byDim.entrySet())
        {
            int dim = e.getKey();
            List<Map<String, Object>> list = e.getValue();
            // 批量,避免单条往返
            int batch = 50;
            for (int i = 0; i < list.size(); i += batch)
            {
                int end = Math.min(list.size(), i + batch);
                kbVectorMapper.batchUpsert(dim, list.subList(i, end));
            }
            log.debug("kb_vector_{} upsert {} 条, kbId={}", dim, list.size(), kbId);
        }
    }

    @Override
    public List<KbSearchHit> search(Long kbId, float[] query, int topK, double minScore)
    {
        if (kbId == null || query == null || query.length == 0 || topK <= 0)
        {
            return List.of();
        }
        int dim = query.length;
        requireDim(dim);
        List<Map<String, Object>> rows = kbVectorMapper.search(dim, kbId, toVectorLiteral(query), topK);
        if (rows == null || rows.isEmpty())
        {
            return List.of();
        }
        List<Long> chunkIds = new ArrayList<>();
        Map<Long, Double> scoreById = new LinkedHashMap<>();
        for (Map<String, Object> row : rows)
        {
            Long chunkId = toLong(row.get("chunkId"));
            if (chunkId == null)
            {
                chunkId = toLong(row.get("chunkid"));
            }
            double score = toDouble(row.get("score"));
            if (chunkId == null || score < minScore)
            {
                continue;
            }
            chunkIds.add(chunkId);
            scoreById.put(chunkId, score);
        }
        if (chunkIds.isEmpty())
        {
            return List.of();
        }
        List<KbChunk> chunks = kbChunkMapper.selectByChunkIds(chunkIds);
        Map<Long, KbChunk> byId = new HashMap<>();
        for (KbChunk c : chunks)
        {
            byId.put(c.getChunkId(), c);
        }
        // 保持向量检索得分序
        List<KbSearchHit> hits = new ArrayList<>();
        for (Long id : scoreById.keySet())
        {
            KbChunk c = byId.get(id);
            if (c == null)
            {
                continue;
            }
            KbSearchHit hit = new KbSearchHit();
            hit.setChunkId(c.getChunkId());
            hit.setKbId(c.getKbId());
            hit.setDocId(c.getDocId());
            hit.setDocName(c.getDocName());
            hit.setHeadingPath(c.getHeadingPath());
            hit.setChunkIndex(c.getChunkIndex());
            hit.setContent(c.getContent());
            hit.setScore(scoreById.get(id));
            hits.add(hit);
        }
        return hits;
    }

    @Override
    public void deleteByDoc(Long kbId, Long docId)
    {
        if (docId == null)
        {
            return;
        }
        // 先取 chunkId 再删,不依赖 kb_chunk 是否仍在(避免与删 chunk 同事务时子查询空集)
        List<Long> chunkIds = kbChunkMapper.selectChunkIdsByDocId(docId);
        deleteByChunkIds(kbId, chunkIds);
    }

    @Override
    public void deleteByChunkIds(Long kbId, List<Long> chunkIds)
    {
        if (chunkIds == null || chunkIds.isEmpty())
        {
            return;
        }
        // 维度可能变更,各维表都清一遍
        for (int dim : SUPPORTED_DIMS)
        {
            kbVectorMapper.deleteByChunkIds(dim, chunkIds);
        }
    }

    @Override
    public void invalidate(Long kbId)
    {
        // 外部向量库无内存缓存,no-op
        log.debug("PgVectorKbVectorStore.invalidate kbId={} (no-op)", kbId);
    }

    @Override
    public void deleteByKb(Long kbId)
    {
        if (kbId == null)
        {
            return;
        }
        for (int dim : SUPPORTED_DIMS)
        {
            kbVectorMapper.deleteByKbId(dim, kbId);
        }
    }

    private static void requireDim(int dim)
    {
        if (!SUPPORTED_DIMS.contains(dim))
        {
            throw new ServiceException(
                "不支持的向量维度 " + dim + ",预建维度: " + SUPPORTED_DIMS
                    + "。请换模型或扩展 sql/kb_pg.sql 预建表。");
        }
    }

    /** float[] → pgvector 字面量 '[0.1,0.2,...]' */
    static String toVectorLiteral(float[] v)
    {
        StringBuilder sb = new StringBuilder(v.length * 8);
        sb.append('[');
        for (int i = 0; i < v.length; i++)
        {
            if (i > 0)
            {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        sb.append(']');
        return sb.toString();
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
            return Long.parseLong(String.valueOf(o));
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    private static double toDouble(Object o)
    {
        if (o == null)
        {
            return 0;
        }
        if (o instanceof Number n)
        {
            return n.doubleValue();
        }
        try
        {
            return Double.parseDouble(String.valueOf(o));
        }
        catch (NumberFormatException e)
        {
            return 0;
        }
    }
}
