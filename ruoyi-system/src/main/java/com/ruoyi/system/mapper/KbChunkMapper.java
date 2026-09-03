package com.ruoyi.system.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.system.domain.KbChunk;

/**
 * 知识库分块表 数据层
 *
 * @author ruoyi
 */
public interface KbChunkMapper
{
    public KbChunk selectKbChunkById(Long chunkId);

    public List<KbChunk> selectKbChunkList(KbChunk query);

    /** 按知识库加载带向量的分块(内存索引懒加载用) */
    public List<KbChunk> selectChunksWithEmbeddingByKbId(Long kbId);

    /** 按文档查分块 */
    public List<KbChunk> selectByDocId(Long docId);

    /** 按文档 + 层级查分块（LEAF/PARENT） */
    public List<KbChunk> selectByDocIdAndLevel(@Param("docId") Long docId, @Param("chunkLevel") String chunkLevel);

    /** 相邻 LEAF（不含自身），用于检索上下文扩展 */
    public List<KbChunk> selectAdjacentLeaves(@Param("docId") Long docId,
        @Param("chunkIndex") int chunkIndex, @Param("window") int window);

    /** 按 chunkId 批量查(检索回填正文) */
    public List<KbChunk> selectByChunkIds(@Param("chunkIds") List<Long> chunkIds);

    /**
     * 知识库下每篇文档取一条切片血统(用于重建判定)。
     * 返回字段:docId/docName + chunkerStrategy/chunkParamsHash/embeddingModel
     */
    public List<KbChunk> selectLineageByKbId(Long kbId);

    public int insertKbChunk(KbChunk chunk);

    public int batchInsertKbChunk(@Param("list") List<KbChunk> list);

    /** 按文档查 chunkId 列表(删除向量时先取 id,避免依赖 kb_chunk 子查询顺序) */
    public List<Long> selectChunkIdsByDocId(Long docId);

    public int deleteByDocId(Long docId);

    public int deleteByKbId(Long kbId);

    public int countByDocId(Long docId);
}
