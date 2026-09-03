package com.ruoyi.system.kb.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.domain.KbChunk;
import com.ruoyi.system.kb.chunker.ChunkLevels;
import com.ruoyi.system.kb.chunker.TokenCounter;
import com.ruoyi.system.kb.vector.KbSearchHit;
import com.ruoyi.system.mapper.KbChunkMapper;

/**
 * 命中 LEAF 后按 token 预算扩展 PARENT 与相邻 LEAF。
 * 最终列表仍以原命中为主，扩展内容拼进 content，不额外占 topK 名额。
 */
@Component
public class ChunkContextExpander
{
    @Autowired
    private KbChunkMapper kbChunkMapper;

    @Value("${ai.kb.search.expand-parent:true}")
    private boolean expandParent;

    @Value("${ai.kb.search.expand-adjacent:1}")
    private int expandAdjacent;

    @Value("${ai.kb.search.expand-max-tokens:2000}")
    private int expandMaxTokens;

    public List<KbSearchHit> expand(List<KbSearchHit> hits)
    {
        if (hits == null || hits.isEmpty() || expandMaxTokens <= 0)
        {
            return hits == null ? List.of() : hits;
        }
        if (!expandParent && expandAdjacent <= 0)
        {
            return hits;
        }
        List<KbSearchHit> out = new ArrayList<>(hits.size());
        for (KbSearchHit hit : hits)
        {
            out.add(expandOne(hit));
        }
        return out;
    }

    private KbSearchHit expandOne(KbSearchHit hit)
    {
        if (hit == null || hit.getChunkId() == null || hit.getDocId() == null)
        {
            return hit;
        }
        KbChunk leaf = kbChunkMapper.selectKbChunkById(hit.getChunkId());
        if (leaf == null || !ChunkLevels.isLeaf(leaf.getChunkLevel()))
        {
            return hit;
        }
        int budget = Math.max(0, expandMaxTokens);
        int used = TokenCounter.estimate(hit.getContent() != null ? hit.getContent() : "");
        StringBuilder ctx = new StringBuilder();
        Set<Long> seen = new LinkedHashSet<>();
        seen.add(leaf.getChunkId());

        if (expandParent && leaf.getParentChunkId() != null && used < budget)
        {
            KbChunk parent = kbChunkMapper.selectKbChunkById(leaf.getParentChunkId());
            if (parent != null && StringUtils.isNotEmpty(parent.getContent()))
            {
                int pt = TokenCounter.estimate(parent.getContent());
                if (used + pt <= budget)
                {
                    appendBlock(ctx, "【父块】", parent.getContent());
                    used += pt;
                    seen.add(parent.getChunkId());
                }
            }
        }

        if (expandAdjacent > 0 && leaf.getChunkIndex() != null && used < budget)
        {
            List<KbChunk> neighbors = kbChunkMapper.selectAdjacentLeaves(
                leaf.getDocId(), leaf.getChunkIndex(), expandAdjacent);
            if (neighbors != null && !neighbors.isEmpty())
            {
                List<KbChunk> ordered = new ArrayList<>(neighbors);
                ordered.sort(Comparator.comparingInt(c ->
                    Math.abs((c.getChunkIndex() == null ? 0 : c.getChunkIndex())
                        - leaf.getChunkIndex())));
                for (KbChunk n : ordered)
                {
                    if (n == null || n.getChunkId() == null || seen.contains(n.getChunkId()))
                    {
                        continue;
                    }
                    if (StringUtils.isEmpty(n.getContent()))
                    {
                        continue;
                    }
                    int nt = TokenCounter.estimate(n.getContent());
                    if (used + nt > budget)
                    {
                        continue;
                    }
                    String tag = n.getChunkIndex() != null && leaf.getChunkIndex() != null
                        && n.getChunkIndex() < leaf.getChunkIndex() ? "【前邻】" : "【后邻】";
                    appendBlock(ctx, tag, n.getContent());
                    used += nt;
                    seen.add(n.getChunkId());
                }
            }
        }

        if (ctx.length() == 0)
        {
            return hit;
        }
        // 扩展文本附在命中正文后，保留原 hit 字段与分数
        String expanded = (hit.getContent() == null ? "" : hit.getContent())
            + "\n\n" + ctx;
        hit.setContent(expanded);
        return hit;
    }

    private static void appendBlock(StringBuilder sb, String tag, String content)
    {
        if (sb.length() > 0)
        {
            sb.append("\n\n");
        }
        sb.append(tag).append('\n').append(content);
    }
}
