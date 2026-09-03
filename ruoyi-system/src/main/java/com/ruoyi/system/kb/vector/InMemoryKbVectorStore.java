package com.ruoyi.system.kb.vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.ruoyi.system.domain.KbChunk;
import com.ruoyi.system.mapper.KbChunkMapper;

/**
 * 内存暴力向量检索(兜底/单测用)。
 * <p>生产由 {@link PgVectorKbVectorStore}{@code @Primary} 接管。
 * 向量迁到 pgvector 后,本类不再从 DB 懒加载 embedding,仅依赖 upsert 写入的内存索引。
 */
@Component
public class InMemoryKbVectorStore implements KbVectorStore
{
    private static final Logger log = LoggerFactory.getLogger(InMemoryKbVectorStore.class);

    @Autowired
    private KbChunkMapper kbChunkMapper;

    /** kbId -> 向量列表(volatile 替换保证读可见) */
    private final ConcurrentHashMap<Long, List<KbChunkVector>> index = new ConcurrentHashMap<>();

    @Override
    public void upsert(Long kbId, List<KbChunkVector> vectors)
    {
        if (kbId == null || vectors == null || vectors.isEmpty())
        {
            return;
        }
        index.compute(kbId, (id, existing) -> {
            List<KbChunkVector> base = existing != null ? new ArrayList<>(existing) : loadFromDb(id);
            Map<Long, KbChunkVector> byChunk = base.stream()
                .filter(v -> v.getChunkId() != null)
                .collect(Collectors.toMap(KbChunkVector::getChunkId, v -> v, (a, b) -> b));
            for (KbChunkVector v : vectors)
            {
                if (v.getChunkId() != null)
                {
                    byChunk.put(v.getChunkId(), v);
                }
                else
                {
                    base.add(v);
                }
            }
            // 重建列表:保留无 id 的 + map 中的
            List<KbChunkVector> next = new ArrayList<>();
            for (KbChunkVector v : base)
            {
                if (v.getChunkId() == null)
                {
                    next.add(v);
                }
            }
            next.addAll(byChunk.values());
            return next;
        });
    }

    @Override
    public List<KbSearchHit> search(Long kbId, float[] query, int topK, double minScore)
    {
        if (kbId == null || query == null || query.length == 0 || topK <= 0)
        {
            return List.of();
        }
        List<KbChunkVector> vectors = index.computeIfAbsent(kbId, this::loadFromDb);
        List<KbSearchHit> hits = new ArrayList<>();
        for (KbChunkVector v : vectors)
        {
            if (v.getEmbedding() == null)
            {
                continue;
            }
            double score = FloatVectorCodec.cosine(query, v.getEmbedding());
            if (score < minScore)
            {
                continue;
            }
            KbSearchHit hit = new KbSearchHit();
            hit.setChunkId(v.getChunkId());
            hit.setKbId(v.getKbId());
            hit.setDocId(v.getDocId());
            hit.setDocName(v.getDocName());
            hit.setHeadingPath(v.getHeadingPath());
            hit.setChunkIndex(v.getChunkIndex());
            hit.setContent(v.getContent());
            hit.setScore(score);
            hits.add(hit);
        }
        hits.sort(Comparator.comparingDouble(KbSearchHit::getScore).reversed());
        if (hits.size() > topK)
        {
            return hits.subList(0, topK);
        }
        return hits;
    }

    @Override
    public void deleteByDoc(Long kbId, Long docId)
    {
        if (kbId == null || docId == null)
        {
            return;
        }
        index.computeIfPresent(kbId, (id, existing) -> {
            List<KbChunkVector> next = existing.stream()
                .filter(v -> !docId.equals(v.getDocId()))
                .collect(Collectors.toCollection(ArrayList::new));
            return next;
        });
    }

    @Override
    public void deleteByChunkIds(Long kbId, List<Long> chunkIds)
    {
        if (kbId == null || chunkIds == null || chunkIds.isEmpty())
        {
            return;
        }
        var idSet = chunkIds.stream().filter(id -> id != null).collect(Collectors.toSet());
        if (idSet.isEmpty())
        {
            return;
        }
        index.computeIfPresent(kbId, (id, existing) -> {
            List<KbChunkVector> next = existing.stream()
                .filter(v -> v.getChunkId() == null || !idSet.contains(v.getChunkId()))
                .collect(Collectors.toCollection(ArrayList::new));
            return next;
        });
    }

    @Override
    public void invalidate(Long kbId)
    {
        if (kbId != null)
        {
            index.remove(kbId);
            log.debug("知识库[{}]内存向量索引已失效", kbId);
        }
    }

    private List<KbChunkVector> loadFromDb(Long kbId)
    {
        // 向量已不在 kb_chunk,懒加载返回空;生产用 PgVectorKbVectorStore
        log.debug("InMemoryKbVectorStore 懒加载 kbId={} → 空(向量在 pgvector)", kbId);
        return new ArrayList<>();
    }
}
