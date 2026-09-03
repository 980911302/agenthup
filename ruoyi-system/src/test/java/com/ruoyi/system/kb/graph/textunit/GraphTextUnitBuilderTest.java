package com.ruoyi.system.kb.graph.textunit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.ruoyi.system.domain.KbChunk;
import com.ruoyi.system.domain.KbGraphTextUnit;

class GraphTextUnitBuilderTest
{
    @Test
    void packsConsecutiveSamePathLeaves()
    {
        List<KbChunk> leaves = List.of(
            leaf(1L, 0, "段1", "章一", "paragraph", 30),
            leaf(2L, 1, "段2", "章一", "paragraph", 30),
            leaf(3L, 2, "段3", "章二", "paragraph", 30));

        List<KbGraphTextUnit> units = GraphTextUnitBuilder.build(leaves, 100, 140);
        assertEquals(2, units.size());
        assertTrue(units.get(0).getContent().contains("段1"));
        assertTrue(units.get(0).getContent().contains("段2"));
        assertEquals(List.of(1L, 2L), units.get(0).getLeafChunkIds());
        assertEquals(List.of(3L), units.get(1).getLeafChunkIds());
        assertEquals(0, units.get(0).getOrdinal());
        assertEquals(1, units.get(1).getOrdinal());
    }

    @Test
    void separatesTableFromParagraph()
    {
        List<KbChunk> leaves = List.of(
            leaf(10L, 0, "正文", "章", "paragraph", 20),
            leaf(11L, 1, "姓名 | 部门\n张 | 研发", "章", "table", 40));

        List<KbGraphTextUnit> units = GraphTextUnitBuilder.build(leaves, 200, 300);
        assertEquals(2, units.size());
        assertEquals("paragraph", units.get(0).getBlockType());
        assertEquals("table", units.get(1).getBlockType());
        assertTrue(units.get(1).getContent().contains("姓名 | 部门"));
    }

    @Test
    void preservesPagesAndLabels()
    {
        KbChunk a = leaf(1L, 0, "行1", "表", "table", 15);
        a.setSourcePageFrom(2);
        a.setSourcePageTo(2);
        a.setSourceLabel("价目表");
        KbChunk b = leaf(2L, 1, "行2", "表", "table", 15);
        b.setSourcePageFrom(3);
        b.setSourcePageTo(3);
        b.setSourceLabel("价目表");

        List<KbGraphTextUnit> units = GraphTextUnitBuilder.build(List.of(a, b), 200, 300);
        assertEquals(1, units.size());
        assertEquals(2, units.get(0).getSourcePageFrom());
        assertEquals(3, units.get(0).getSourcePageTo());
        assertEquals("价目表", units.get(0).getSourceLabel());
        assertEquals(GraphTextUnitBuilder.GRAPH_UNIT_VERSION, units.get(0).getGraphUnitVersion());
    }

    @Test
    void paramsHashChangesWithSize()
    {
        String a = GraphTextUnitBuilder.paramsHash(350, 500);
        String b = GraphTextUnitBuilder.paramsHash(200, 500);
        assertFalse(a.equals(b));
        assertEquals(a, GraphTextUnitBuilder.paramsHash(350, 500));
    }

    @Test
    void everyLeafMappedToSomeUnit()
    {
        List<KbChunk> leaves = new ArrayList<>();
        for (int i = 0; i < 10; i++)
        {
            leaves.add(leaf(100L + i, i, "内容".repeat(15) + i, "同章", "paragraph", 40));
        }
        List<KbGraphTextUnit> units = GraphTextUnitBuilder.build(leaves, 80, 120);
        int mapped = units.stream().mapToInt(u -> u.getLeafChunkIds().size()).sum();
        assertEquals(10, mapped);
        assertTrue(units.size() >= 2);
    }

    private static KbChunk leaf(Long id, int index, String content, String path,
        String type, int tokens)
    {
        KbChunk c = new KbChunk();
        c.setChunkId(id);
        c.setChunkIndex(index);
        c.setContent(content);
        c.setHeadingPath(path);
        c.setBlockType(type);
        c.setTokenCount(tokens);
        c.setChunkLevel("LEAF");
        return c;
    }
}
