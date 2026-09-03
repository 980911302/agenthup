package com.ruoyi.system.kb.search;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.system.domain.KbChunk;
import com.ruoyi.system.kb.chunker.ChunkLevels;
import com.ruoyi.system.kb.vector.KbSearchHit;
import com.ruoyi.system.mapper.KbChunkMapper;

@ExtendWith(MockitoExtension.class)
class ChunkContextExpanderTest
{
    @Mock
    private KbChunkMapper kbChunkMapper;

    @InjectMocks
    private ChunkContextExpander expander;

    @BeforeEach
    void setUp()
    {
        ReflectionTestUtils.setField(expander, "expandParent", true);
        ReflectionTestUtils.setField(expander, "expandAdjacent", 1);
        ReflectionTestUtils.setField(expander, "expandMaxTokens", 80);
    }

    @Test
    void expandsParentWithinBudget()
    {
        KbChunk leaf = chunk(10L, 1L, 2, "叶子正文短", ChunkLevels.LEAF, 100L);
        KbChunk parent = chunk(100L, 1L, -1, "父块上下文", ChunkLevels.PARENT, null);
        when(kbChunkMapper.selectKbChunkById(10L)).thenReturn(leaf);
        when(kbChunkMapper.selectKbChunkById(100L)).thenReturn(parent);
        when(kbChunkMapper.selectAdjacentLeaves(eq(1L), eq(2), anyInt())).thenReturn(List.of());

        KbSearchHit hit = new KbSearchHit();
        hit.setChunkId(10L);
        hit.setDocId(1L);
        hit.setContent("叶子正文短");
        hit.setScore(0.9);

        List<KbSearchHit> out = expander.expand(List.of(hit));
        assertTrue(out.get(0).getContent().contains("父块上下文"));
        assertTrue(out.get(0).getContent().contains("【父块】"));
    }

    @Test
    void respectsTokenBudget()
    {
        ReflectionTestUtils.setField(expander, "expandMaxTokens", 5);
        KbChunk leaf = chunk(10L, 1L, 2, "叶", ChunkLevels.LEAF, 100L);
        KbChunk parent = chunk(100L, 1L, -1, "很长的父块内容".repeat(20), ChunkLevels.PARENT, null);
        when(kbChunkMapper.selectKbChunkById(10L)).thenReturn(leaf);
        when(kbChunkMapper.selectKbChunkById(100L)).thenReturn(parent);

        KbSearchHit hit = new KbSearchHit();
        hit.setChunkId(10L);
        hit.setDocId(1L);
        hit.setContent("叶");

        List<KbSearchHit> out = expander.expand(List.of(hit));
        assertFalse(out.get(0).getContent().contains("【父块】"));
    }

    private static KbChunk chunk(Long id, Long docId, int index, String content,
        String level, Long parentId)
    {
        KbChunk c = new KbChunk();
        c.setChunkId(id);
        c.setDocId(docId);
        c.setChunkIndex(index);
        c.setContent(content);
        c.setChunkLevel(level);
        c.setParentChunkId(parentId);
        return c;
    }
}
