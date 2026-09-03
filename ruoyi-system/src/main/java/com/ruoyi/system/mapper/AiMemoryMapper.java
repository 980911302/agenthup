package com.ruoyi.system.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.system.domain.AiMemory;

/**
 * 跨会话长期记忆台账 ai_memory 访问。
 *
 * <p>所有查询都强制携带 {@code userId}(跨用户隔离是红线),agent 维度按 {@code agentId}
 * (0=用户层,>0=专属层)过滤。租户键由上层以 {@code MemoryTenant} 传入,此处只收标量参数。
 */
public interface AiMemoryMapper
{
    int insert(AiMemory memory);

    int update(AiMemory memory);

    /** 按主键+租户查单条(归属校验);userId 强制,agentId 精确匹配 */
    AiMemory selectById(@Param("userId") Long userId, @Param("agentId") Long agentId,
                        @Param("memoryId") Long memoryId);

    /** 按 userId + memoryId 集合批量查(检索命中回查正文;userId 强制,含用户层+专属层) */
    List<AiMemory> selectByIds(@Param("userId") Long userId, @Param("memoryIds") List<Long> memoryIds);

    /** 按 userId 查该用户全部层(用于合规清理/观测) */
    List<AiMemory> selectByUser(@Param("userId") Long userId);

    /** 管理端分页列表:允许按用户、层级、类型、状态和正文筛选。 */
    List<AiMemory> selectAdminList(AiMemory memory);

    /** 管理端删除前按主键查询，供清理同一条记忆的向量。 */
    List<AiMemory> selectAdminByIds(@Param("memoryIds") List<Long> memoryIds);

    /** 管理端软删除。向量必须由服务层先清理，避免遗留可检索的孤儿向量。 */
    int softDeleteByIds(@Param("memoryIds") List<Long> memoryIds);

    /** 合规清理:抹掉该用户全部层的台账记录(del_flag=2) */
    int deleteByUser(@Param("userId") Long userId);

    /** 按 userId + content_hash 精确查(去重用),agentId=0 表示仅用户层 */
    AiMemory selectByHash(@Param("userId") Long userId, @Param("agentId") Long agentId,
                          @Param("contentHash") String contentHash);

    /** 同一租户内 active 记忆条数(容量上限用) */
    long countActive(@Param("userId") Long userId, @Param("agentId") Long agentId);

    /** 标记 superseded(仅同层内) */
    int markSuperseded(@Param("userId") Long userId, @Param("agentId") Long agentId,
                       @Param("memoryId") Long memoryId, @Param("supersededBy") Long supersededBy);

    /** 命中回写(异步) */
    int bumpHit(@Param("memoryId") Long memoryId);

    /** 测试专用:建表(H2) */
    void createTableForTest();

    /** 测试专用:删表(H2) */
    void dropTableForTest();
}
