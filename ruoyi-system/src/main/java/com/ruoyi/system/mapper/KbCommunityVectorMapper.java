package com.ruoyi.system.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/**
 * 社区报告向量表 kb_community_vector_{dim}
 */
public interface KbCommunityVectorMapper
{
    int upsert(@Param("dim") int dim, @Param("row") Map<String, Object> row);

    int deleteByKbId(@Param("dim") int dim, @Param("kbId") Long kbId);

    List<Map<String, Object>> search(@Param("dim") int dim,
        @Param("kbId") Long kbId,
        @Param("queryVector") String queryVector,
        @Param("topK") int topK);
}
