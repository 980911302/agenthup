package com.ruoyi.system.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/**
 * 记忆向量表 mem_vector_{dim} 访问(表名按维度路由,SQL 里用 ${dim})。
 *
 * <p>user_id 永远强制;agent_id 支持 {@code agent_id in (0, ?)} 一次查两层。
 */
public interface MemVectorMapper
{
    int upsert(@Param("dim") int dim,
               @Param("memoryId") Long memoryId,
               @Param("userId") Long userId,
               @Param("agentId") Long agentId,
               @Param("embedding") String embedding);

    /** 按余弦距离检索。返回 map: memoryId, score */
    List<Map<String, Object>> searchLayered(@Param("dim") int dim,
                                            @Param("userId") Long userId,
                                            @Param("agentId") Long agentId,
                                            @Param("queryVector") String queryVector,
                                            @Param("topK") int topK);

    int deleteByMemoryIds(@Param("dim") int dim,
                          @Param("userId") Long userId,
                          @Param("memoryIds") List<Long> memoryIds);

    /** 合规清理:抹掉该用户全部层 */
    int deleteByUser(@Param("dim") int dim, @Param("userId") Long userId);
}
