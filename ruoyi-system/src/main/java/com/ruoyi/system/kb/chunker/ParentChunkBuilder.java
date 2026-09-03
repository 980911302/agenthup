package com.ruoyi.system.kb.chunker;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 从有序 LEAF 草稿生成 PARENT：按文档顺序 + 连续 headingPath 聚合，超限则切分连续子组。
 * <p>不跨章节全局合并同名标题。
 */
public final class ParentChunkBuilder
{
    private ParentChunkBuilder()
    {
    }

    /**
     * @param leaves          已按 chunkIndex 排序的 LEAF
     * @param parentMaxTokens 单个 PARENT 最大 token（至少 50）
     */
    public static List<ChunkDraft> buildParents(List<ChunkDraft> leaves, int parentMaxTokens)
    {
        if (leaves == null || leaves.isEmpty())
        {
            return List.of();
        }
        int maxTokens = Math.max(50, parentMaxTokens);
        List<ChunkDraft> parents = new ArrayList<>();
        List<ChunkDraft> run = new ArrayList<>();
        String runPath = null;

        for (ChunkDraft leaf : leaves)
        {
            if (leaf == null || leaf.getContent() == null || leaf.getContent().isBlank())
            {
                continue;
            }
            String path = leaf.getHeadingPath();
            if (!run.isEmpty() && !Objects.equals(runPath, path))
            {
                flushRun(parents, run, maxTokens);
                run = new ArrayList<>();
            }
            if (run.isEmpty())
            {
                runPath = path;
            }
            run.add(leaf);
        }
        flushRun(parents, run, maxTokens);

        for (int i = 0; i < parents.size(); i++)
        {
            // 负索引：与 LEAF 的 0..n-1 不冲突，列表排序时 PARENT 在前
            parents.get(i).setChunkIndex(-(i + 1));
        }
        return parents;
    }

    /**
     * 默认父块预算：叶子目标的 2.5 倍，下限 400。
     */
    public static int defaultParentMaxTokens(int leafChunkSize)
    {
        int base = Math.max(50, leafChunkSize);
        return Math.max(400, (int) (base * 2.5));
    }

    private static void flushRun(List<ChunkDraft> parents, List<ChunkDraft> run, int maxTokens)
    {
        if (run == null || run.isEmpty())
        {
            return;
        }
        List<ChunkDraft> group = new ArrayList<>();
        int tokens = 0;
        for (ChunkDraft leaf : run)
        {
            int t = Math.max(1, leaf.getTokenCount() > 0
                ? leaf.getTokenCount() : TokenCounter.estimate(leaf.getContent()));
            if (!group.isEmpty() && tokens + t > maxTokens)
            {
                parents.add(makeParent(group));
                group = new ArrayList<>();
                tokens = 0;
            }
            // 单叶超父上限：仍单独成父，避免丢叶
            if (group.isEmpty() && t > maxTokens)
            {
                parents.add(makeParent(List.of(leaf)));
                continue;
            }
            group.add(leaf);
            tokens += t;
        }
        if (!group.isEmpty())
        {
            parents.add(makeParent(group));
        }
    }

    private static ChunkDraft makeParent(List<ChunkDraft> children)
    {
        ChunkDraft parent = new ChunkDraft();
        parent.setChunkLevel(ChunkLevels.PARENT);
        StringBuilder sb = new StringBuilder();
        List<Integer> childIdx = new ArrayList<>();
        Integer pageFrom = null;
        Integer pageTo = null;
        String firstLabel = null;
        String lastLabel = null;
        String path = children.get(0).getHeadingPath();
        String strategy = children.get(0).getChunkerStrategy();
        String fp = children.get(0).getChunkParamsHash();
        for (ChunkDraft c : children)
        {
            if (sb.length() > 0)
            {
                sb.append("\n\n");
            }
            sb.append(c.getContent());
            childIdx.add(c.getChunkIndex());
            if (c.getSourcePageFrom() != null)
            {
                pageFrom = pageFrom == null ? c.getSourcePageFrom()
                    : Math.min(pageFrom, c.getSourcePageFrom());
            }
            if (c.getSourcePageTo() != null)
            {
                pageTo = pageTo == null ? c.getSourcePageTo()
                    : Math.max(pageTo, c.getSourcePageTo());
            }
            if (c.getSourceLabel() != null && !c.getSourceLabel().isBlank())
            {
                if (firstLabel == null)
                {
                    firstLabel = c.getSourceLabel().trim();
                }
                lastLabel = c.getSourceLabel().trim();
            }
        }
        String content = sb.toString().trim();
        parent.setContent(content);
        parent.setTokenCount(TokenCounter.estimate(content));
        parent.setHeadingPath(path);
        parent.setBlockType("parent");
        parent.setChunkerStrategy(strategy);
        parent.setChunkParamsHash(fp);
        parent.setSourcePageFrom(pageFrom);
        parent.setSourcePageTo(pageTo);
        if (firstLabel != null)
        {
            parent.setSourceLabel(firstLabel.equals(lastLabel) ? firstLabel
                : firstLabel + " … " + lastLabel);
        }
        parent.setChildLeafIndices(childIdx);
        return parent;
    }
}
