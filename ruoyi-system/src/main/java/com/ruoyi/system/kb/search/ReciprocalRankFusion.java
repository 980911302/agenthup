package com.ruoyi.system.kb.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Reciprocal Rank Fusion：score = Σ 1/(k + rank)，rank 从 1 起。
 * <p>用于融合不可比的通道分数（Basic 余弦 vs Local 图序）。
 */
public final class ReciprocalRankFusion
{
    /** 经典 RRF 常数 */
    public static final int DEFAULT_K = 60;

    private ReciprocalRankFusion() {}

    /**
     * 融合多路有序列表。
     *
     * @param lists   每路按相关性降序
     * @param idFn    稳定去重键
     * @param k       RRF 平滑常数（常用 60）
     * @param topN    截断
     */
    public static <T> List<FusedHit<T>> fuse(List<List<T>> lists, Function<T, Object> idFn, int k, int topN)
    {
        if (lists == null || lists.isEmpty() || idFn == null)
        {
            return List.of();
        }
        int kk = k > 0 ? k : DEFAULT_K;
        Map<Object, FusedHit<T>> byId = new LinkedHashMap<>();
        for (int channel = 0; channel < lists.size(); channel++)
        {
            List<T> list = lists.get(channel);
            if (list == null)
            {
                continue;
            }
            for (int i = 0; i < list.size(); i++)
            {
                T item = list.get(i);
                if (item == null)
                {
                    continue;
                }
                Object id = idFn.apply(item);
                if (id == null)
                {
                    continue;
                }
                int rank = i + 1;
                double contrib = 1.0 / (kk + rank);
                FusedHit<T> fused = byId.get(id);
                if (fused == null)
                {
                    fused = new FusedHit<>(item, 0.0);
                    byId.put(id, fused);
                }
                fused.score += contrib;
                fused.channelRanks.put(channel, rank);
                // 保留首次出现的代表对象；若后续通道有更好展示可不替换
            }
        }
        List<FusedHit<T>> out = new ArrayList<>(byId.values());
        out.sort(Comparator.comparingDouble((FusedHit<T> h) -> h.score).reversed());
        if (topN > 0 && out.size() > topN)
        {
            return new ArrayList<>(out.subList(0, topN));
        }
        return out;
    }

    public static final class FusedHit<T>
    {
        public final T item;
        public double score;
        public final Map<Integer, Integer> channelRanks = new LinkedHashMap<>();

        public FusedHit(T item, double score)
        {
            this.item = Objects.requireNonNull(item);
            this.score = score;
        }
    }
}
