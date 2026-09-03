package com.ruoyi.system.ai.memory.longterm;

import java.util.List;
import com.ruoyi.system.domain.AiMemory;

/**
 * 记忆业务门面。
 *
 * <p>统一入口,收敛「隔离、去重、supersede、命中回写」等业务规则:
 * <ul>
 *   <li><b>分层</b>:检索一次 SQL 查两层;冲突时 agent 层遮蔽用户层;agent 层不能 supersede 用户层;</li>
 *   <li><b>只增不删</b>:覆盖走 supersede,物理删除只留给合规清理;</li>
 *   <li><b>可观测</b>:source / hit_count / last_hit_time 在此维护。</li>
 * </ul>
 */
public interface MemoryService
{
    /**
     * 新增一条 active 记忆(时间线语义:并存)。返回 memoryId。
     *
     * @param tenant 归属租户(用户层或用户×agent 层)
     * @param type   fact|preference|event|goal|rule
     * @param content 记忆正文
     * @param sourceSessionId 来源会话(可溯源)
     * @param sourceMessageId 提炼覆盖到的消息位点
     */
    Long add(MemoryTenant tenant, String type, String content,
             String sourceSessionId, Long sourceMessageId);

    /**
     * 覆盖语义:旧记忆 status=superseded + superseded_by=新id,新记忆 active。
     * <b>只在同层内发生</b>(agent 层不得覆盖用户层,spec §6.3 规则3)。
     *
     * @return 新记忆 id;找不到旧记忆(或归属不匹配)返回 null
     */
    Long supersede(MemoryTenant tenant, Long oldMemoryId, String type, String newContent,
                   String sourceSessionId, Long sourceMessageId);

    /** 归属校验查询:租户不匹配返回 null。 */
    AiMemory getById(MemoryTenant tenant, Long memoryId);

    /** 检索:分层一次查询,按相似度过滤,返回已回填正文的记忆条目。 */
    List<AiMemory> search(MemoryTenant tenant, float[] query, int topK, double minScore);

    /** 命中回写(异步)。 */
    void onHit(Long memoryId);

    /** 合规清理:抹掉该用户全部层(台账 + 向量)。系统能力,不对 agent 暴露。 */
    void deleteByUser(Long userId);
}
