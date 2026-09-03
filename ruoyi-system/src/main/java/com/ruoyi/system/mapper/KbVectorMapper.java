package com.ruoyi.system.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/**
 * pgvector 向量表访问(表名按维度路由,SQL 里用 ${dim})。
 */
public interface KbVectorMapper
{
    int upsert(@Param("dim") int dim,
               @Param("chunkId") Long chunkId,
               @Param("kbId") Long kbId,
               @Param("embedding") String embedding);

    int batchUpsert(@Param("dim") int dim, @Param("list") List<Map<String, Object>> list);

    /**
     * 按余弦距离检索。返回 map: chunkId, score
     */
    List<Map<String, Object>> search(@Param("dim") int dim,
                                     @Param("kbId") Long kbId,
                                     @Param("queryVector") String queryVector,
                                     @Param("topK") int topK);

    int deleteByDocId(@Param("dim") int dim, @Param("docId") Long docId);

    int deleteByKbId(@Param("dim") int dim, @Param("kbId") Long kbId);

    int deleteByChunkIds(@Param("dim") int dim, @Param("chunkIds") List<Long> chunkIds);
}
