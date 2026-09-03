package com.ruoyi.system.kb.chunker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ParentChunkBuilderTest
{
    @Test
    void groupsConsecutiveSameHeadingPath()
    {
        List<ChunkDraft> leaves = List.of(
            leaf(0, "A1", "章一", 20),
            leaf(1, "A2", "章一", 20),
            leaf(2, "B1", "章二", 20));

        List<ChunkDraft> parents = ParentChunkBuilder.buildParents(leaves, 200);
        assertEquals(2, parents.size());
        assertEquals(ChunkLevels.PARENT, parents.get(0).getChunkLevel());
        assertTrue(parents.get(0).getContent().contains("A1"));
        assertTrue(parents.get(0).getContent().contains("A2"));
        assertEquals(List.of(0, 1), parents.get(0).getChildLeafIndices());
        assertEquals(List.of(2), parents.get(1).getChildLeafIndices());
        assertTrue(parents.get(0).getChunkIndex() < 0);
    }

    @Test
    void splitsWhenExceedingParentBudget()
    {
        List<ChunkDraft> leaves = new ArrayList<>();
        // 每个约 40 token 的中文重复
        for (int i = 0; i < 6; i++)
        {
            leaves.add(leaf(i, "内容".repeat(20) + i, "同章", 40));
        }
        List<ChunkDraft> parents = ParentChunkBuilder.buildParents(leaves, 100);
        assertTrue(parents.size() >= 2);
        for (ChunkDraft p : parents)
        {
            assertTrue(p.getTokenCount() <= 120 || p.getChildLeafIndices().size() == 1);
            assertEquals("同章", p.getHeadingPath());
        }
        // 所有叶子都被某个父引用
        int covered = parents.stream().mapToInt(p -> p.getChildLeafIndices().size()).sum();
        assertEquals(6, covered);
    }

    @Test
    void preservesSourcePagesAcrossChildren()
    {
        ChunkDraft a = leaf(0, "页3正文", "表", 10);
        a.setSourcePageFrom(3);
        a.setSourcePageTo(3);
        a.setSourceLabel("员工表");
        ChunkDraft b = leaf(1, "页4正文", "表", 10);
        b.setSourcePageFrom(4);
        b.setSourcePageTo(4);
        b.setSourceLabel("员工表");
        List<ChunkDraft> parents = ParentChunkBuilder.buildParents(List.of(a, b), 500);
        assertEquals(1, parents.size());
        assertEquals(3, parents.get(0).getSourcePageFrom());
        assertEquals(4, parents.get(0).getSourcePageTo());
        assertEquals("员工表", parents.get(0).getSourceLabel());
    }

    private static ChunkDraft leaf(int index, String content, String path, int tokens)
    {
        ChunkDraft d = new ChunkDraft();
        d.setChunkIndex(index);
        d.setContent(content);
        d.setHeadingPath(path);
        d.setTokenCount(tokens);
        d.setChunkLevel(ChunkLevels.LEAF);
        d.setChunkerStrategy("P");
        d.setChunkParamsHash("fp");
        d.setBlockType("paragraph");
        return d;
    }
}
