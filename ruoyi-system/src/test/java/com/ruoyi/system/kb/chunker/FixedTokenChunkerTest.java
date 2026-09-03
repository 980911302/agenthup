package com.ruoyi.system.kb.chunker;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import com.ruoyi.system.kb.parser.IrBlock;
import com.ruoyi.system.kb.parser.IrDoc;

class FixedTokenChunkerTest
{
    private final FixedTokenChunker chunker = new FixedTokenChunker();

    @Test
    void splitsOversizedFirstSegmentByRealTokenLimit()
    {
        IrDoc doc = new IrDoc();
        IrBlock block = new IrBlock();
        block.setText("知识图谱检索增强生成与实体关系抽取".repeat(80));
        block.setBlockType("paragraph");
        block.setHeadingPath(List.of("GraphRAG"));
        block.setPageNumber(7);
        doc.getBlocks().add(block);

        List<ChunkDraft> chunks = chunker.chunk(doc, new ChunkParams("F", 60, 10));

        assertTrue(chunks.size() > 2);
        assertTrue(chunks.stream().allMatch(chunk -> chunk.getTokenCount() <= 60));
        assertTrue(chunks.stream().allMatch(chunk -> Integer.valueOf(7).equals(chunk.getSourcePageFrom())));
        assertEquals("GraphRAG", chunks.get(0).getHeadingPath());
    }

    @Test
    void overlapNeverPushesNormalSegmentPastLimit()
    {
        IrDoc doc = new IrDoc();
        for (int i = 0; i < 8; i++)
        {
            IrBlock block = new IrBlock();
            block.setText("segment-" + i + " contains retrieval context and relationship evidence");
            block.setBlockType("paragraph");
            block.setHeadingPath(List.of("Section"));
            doc.getBlocks().add(block);
        }

        List<ChunkDraft> chunks = chunker.chunk(doc, new ChunkParams("F", 50, 20));

        assertTrue(chunks.size() > 1);
        assertTrue(chunks.stream().allMatch(chunk -> chunk.getTokenCount() <= 50));
    }
}
