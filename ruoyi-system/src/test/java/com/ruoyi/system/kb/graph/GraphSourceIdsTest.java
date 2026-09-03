package com.ruoyi.system.kb.graph;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class GraphSourceIdsTest
{
    @Test
    void mergeKeepsEarliestAndCaps()
    {
        List<Long> existing = List.of(1L, 2L, 3L);
        List<Long> incoming = List.of(3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L, 13L);
        List<Long> merged = GraphSourceIds.mergeLongs(existing, incoming, KbGraphConstants.MAX_SOURCE_IDS);
        assertEquals(KbGraphConstants.MAX_SOURCE_IDS, merged.size());
        // KEEP: 最早出现的保留
        assertEquals(1L, merged.get(0));
        assertEquals(2L, merged.get(1));
        assertEquals(3L, merged.get(2));
        assertFalse(merged.contains(13L));
    }

    @Test
    void mergePaths_placeholderWhenExceeds()
    {
        List<String> existing = List.of("a.md", "b.md");
        List<String> incoming = List.of("c.md", "d.md", "e.md", "f.md", "g.md", "h.md", "i.md", "j.md", "k.md");
        List<String> merged = GraphSourceIds.mergePaths(existing, incoming, KbGraphConstants.MAX_FILE_PATHS);
        assertEquals(KbGraphConstants.MAX_FILE_PATHS, merged.size());
        assertEquals(KbGraphConstants.MORE_FILES_PLACEHOLDER, merged.get(merged.size() - 1));
    }

    @Test
    void removeAll()
    {
        List<Long> left = GraphSourceIds.removeAll(List.of(1L, 2L, 3L), List.of(2L));
        assertEquals(List.of(1L, 3L), left);
    }
}
