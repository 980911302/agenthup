package com.ruoyi.system.kb.chunker;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.ruoyi.system.kb.KbConstants;
import com.ruoyi.system.kb.parser.IrBlock;
import com.ruoyi.system.kb.parser.IrDoc;

class ParagraphChunkerTest
{
    private ParagraphChunker chunker;

    @BeforeEach
    void setUp() { chunker = new ParagraphChunker(); }

    @Test
    void groupsByHeadingPath()
    {
        IrDoc doc = new IrDoc();
        doc.getBlocks().add(block("章一", "heading", List.of("章一"), 1));
        doc.getBlocks().add(block("段落A内容足够长一些用于测试", "paragraph", List.of("章一"), null));
        doc.getBlocks().add(block("段落B继续在章一", "paragraph", List.of("章一"), null));
        doc.getBlocks().add(block("章二", "heading", List.of("章二"), 1));
        doc.getBlocks().add(block("段落C在章二下面", "paragraph", List.of("章二"), null));

        ChunkParams params = new ChunkParams("P", 800, 100);
        List<ChunkDraft> chunks = chunker.chunk(doc, params);

        assertTrue(chunks.size() >= 2);
        // 第一章的段落应继承 headingPath
        assertEquals("章一", chunks.get(0).getHeadingPath());
        assertTrue(chunks.get(0).getContent().contains("段落A"));
        // 末块应是章二
        ChunkDraft last = chunks.get(chunks.size() - 1);
        assertEquals("章二", last.getHeadingPath());
        assertTrue(last.getContent().contains("段落C"));
        // 血统
        assertEquals("P", chunks.get(0).getChunkerStrategy());
        assertNotNull(chunks.get(0).getChunkParamsHash());
    }

    @Test
    void splitsWhenExceedsChunkSize()
    {
        IrDoc doc = new IrDoc();
        // 构造多个中等段落,小 chunkSize 强制切开
        for (int i = 0; i < 10; i++)
        {
            doc.getBlocks().add(block(
                "这是第" + i + "段内容，包含足够多的中文字符以便 token 估算超过阈值。"
                    + "继续填充一些说明文字来确保长度。",
                "paragraph", List.of("同级章节"), null));
        }
        ChunkParams params = new ChunkParams("P", 40, 5);
        List<ChunkDraft> chunks = chunker.chunk(doc, params);
        assertTrue(chunks.size() > 1, "超长应切成多块");
        for (ChunkDraft c : chunks)
        {
            assertEquals("同级章节", c.getHeadingPath());
        }
    }

    @Test
    void hardSplitRespectsRealTokenLimit()
    {
        IrDoc doc = new IrDoc();
        doc.getBlocks().add(block("实体关系抽取和社区摘要生成".repeat(100),
            "paragraph", List.of("GraphRAG"), null));

        List<ChunkDraft> chunks = chunker.chunk(doc, new ChunkParams("P", 60, 10));

        assertTrue(chunks.size() > 2);
        assertTrue(chunks.stream().allMatch(chunk -> chunk.getTokenCount() <= 60));
    }

    @Test
    void headingSepConstantUsed()
    {
        IrDoc doc = new IrDoc();
        doc.getBlocks().add(block("正文", "paragraph",
            List.of("第3章 技术方案", "3.2 存储设计"), null));
        List<ChunkDraft> chunks = chunker.chunk(doc, new ChunkParams("P", 800, 100));
        assertEquals(1, chunks.size());
        assertEquals("第3章 技术方案" + KbConstants.HEADING_SEP + "3.2 存储设计",
            chunks.get(0).getHeadingPath());
    }

    @Test
    void keepsOriginalOrderWhenHeadingPathRepeats()
    {
        IrDoc doc = new IrDoc();
        doc.getBlocks().add(block("A-第一次出现", "paragraph", List.of("重复章节"), null));
        doc.getBlocks().add(block("B-中间章节", "paragraph", List.of("另一章节"), null));
        doc.getBlocks().add(block("C-再次出现", "paragraph", List.of("重复章节"), null));

        List<ChunkDraft> chunks = chunker.chunk(doc, new ChunkParams("P", 800, 0));

        assertEquals(3, chunks.size());
        assertTrue(chunks.get(0).getContent().startsWith("A-"));
        assertTrue(chunks.get(1).getContent().startsWith("B-"));
        assertTrue(chunks.get(2).getContent().startsWith("C-"));
    }

    @Test
    void separatesDifferentStructuralBlockTypes()
    {
        IrDoc doc = new IrDoc();
        doc.getBlocks().add(block("正文", "paragraph", List.of("章"), null));
        doc.getBlocks().add(block("列表项", "list_item", List.of("章"), null));
        doc.getBlocks().add(block("int answer = 42;", "code", List.of("章"), null));
        doc.getBlocks().add(block("列A | 列B", "table_header", List.of("章"), null));
        doc.getBlocks().add(block("值A | 值B", "table_row", List.of("章"), null));

        List<ChunkDraft> chunks = chunker.chunk(doc, new ChunkParams("P", 800, 0));

        assertEquals(List.of("paragraph", "list_item", "code", "table"),
            chunks.stream().map(ChunkDraft::getBlockType).toList());
        assertTrue(chunks.get(1).getContent().startsWith("- "));
    }

    @Test
    void repeatsTableHeaderAndKeepsSourcePages()
    {
        IrDoc doc = new IrDoc();
        IrBlock header = block("姓名 | 部门 | 主要职责", "table_header", List.of("人员"), null);
        header.setPageNumber(3);
        header.setSourceLabel("员工表");
        doc.getBlocks().add(header);
        for (int i = 0; i < 4; i++)
        {
            IrBlock row = block("员工" + i + " | 技术研发中心 | " + "负责系统设计和交付".repeat(4),
                "table_row", List.of("人员"), null);
            row.setPageNumber(i < 2 ? 3 : 4);
            row.setSourceLabel("员工表");
            doc.getBlocks().add(row);
        }

        List<ChunkDraft> chunks = chunker.chunk(doc, new ChunkParams("P", 50, 0));

        assertTrue(chunks.size() > 1);
        for (ChunkDraft chunk : chunks)
        {
            assertTrue(chunk.getContent().startsWith("姓名 | 部门 | 主要职责"));
            assertEquals("员工表", chunk.getSourceLabel());
            assertEquals("LEAF", chunk.getChunkLevel());
        }
        assertEquals(3, chunks.get(0).getSourcePageFrom());
        assertEquals(4, chunks.get(chunks.size() - 1).getSourcePageTo());
    }

    private static IrBlock block(String text, String type, List<String> path, Integer level)
    {
        IrBlock b = new IrBlock();
        b.setText(text);
        b.setBlockType(type);
        b.setHeadingPath(path);
        b.setLevel(level);
        return b;
    }
}
