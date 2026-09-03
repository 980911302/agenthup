package com.ruoyi.system.ai.memory.longterm;

import com.ruoyi.system.domain.AiMemory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 跨会话记忆的隔离与分层规则测试(合规红线,优先级最高)。
 *
 * <p>覆盖 spec §6:userId 永远强制;分层只在 agent 维度放松;agent 层不能 supersede 用户层;
 * 检索一次查两层;冲突时 agent 层遮蔽用户层且不删除。
 *
 * <p>说明:{@code MemoryServiceImpl.add} 只落台账,向量化是写侧(提炼器)职责,
 * 测试在需要检索时显式补向量(对齐真实链路边界)。
 */
class MemoryTenantIsolationTest extends MemoryTestSupport
{
    /** 测试用固定向量,4 维;向量化非本测试关注点 */
    private static final float[] V1 = {0.1f, 0.2f, 0.3f, 0.4f};

    @Test
    void tenant_rejectsNullUserId()
    {
        assertThrows(NullPointerException.class, () -> new MemoryTenant(null, 1L));
    }

    @Test
    void tenant_nullAgentId_fallsBackToUserScope()
    {
        MemoryTenant t = new MemoryTenant(100L, null);
        assertEquals(0L, t.agentId());
        assertEquals(true, t.isUserScope());
    }

    @Test
    void add_userScopeAndAgentScope_coexistUnderSameUser()
    {
        Long userOnly = service.add(MemoryTenant.ofUser(1L), "fact", "用户在北京工作", "s1", 10L);
        Long agentOnly = service.add(MemoryTenant.ofAgent(1L, 5L), "preference", "回复要简洁", "s2", 20L);

        assertNotNull(userOnly);
        assertNotNull(agentOnly);
        // 同一用户两层各自可见
        AiMemory u = service.getById(MemoryTenant.ofUser(1L), userOnly);
        AiMemory a = service.getById(MemoryTenant.ofAgent(1L, 5L), agentOnly);
        assertNotNull(u);
        assertNotNull(a);
        assertEquals("用户在北京工作", u.getContent());
        assertEquals("回复要简洁", a.getContent());
    }

    @Test
    void crossUser_readIsRejected()
    {
        Long memory = service.add(MemoryTenant.ofUser(1L), "fact", "用户A的秘密", "s1", 1L);
        assertNotNull(memory);

        // 用户B(即使用户层)读不到用户A的记忆
        AiMemory other = service.getById(MemoryTenant.ofUser(2L), memory);
        assertNull(other);
    }

    @Test
    void crossUser_agentScopeAlsoRejected()
    {
        Long memory = service.add(MemoryTenant.ofAgent(1L, 5L), "fact", "A在agent5的专属事实", "s1", 1L);
        // 用户2 用同一 agentId 也读不到(隔离维度是 user)
        AiMemory other = service.getById(MemoryTenant.ofAgent(2L, 5L), memory);
        assertNull(other);
    }

    @Test
    void search_layered_hitsBothLayers()
    {
        Long m1 = service.add(MemoryTenant.ofUser(1L), "fact", "用户喜欢喝咖啡", "s1", 1L);
        Long m2 = service.add(MemoryTenant.ofAgent(1L, 5L), "preference", "用户在agent5里偏好详尽回答", "s2", 2L);
        vectorStore.upsert(MemoryTenant.ofUser(1L), m1, V1);
        vectorStore.upsert(MemoryTenant.ofAgent(1L, 5L), m2, V1);

        List<AiMemory> hits = service.search(MemoryTenant.ofAgent(1L, 5L), V1, 10, 0.0);
        // 一次查询命中两层
        assertEquals(2, hits.size());
    }

    @Test
    void agentScope_cannotSupersedeUserScope()
    {
        Long userMemory = service.add(MemoryTenant.ofUser(1L), "preference", "回复要简洁", "s1", 1L);
        // agent 层尝试覆盖用户层 → 不匹配同层,返回 null
        Long result = service.supersede(MemoryTenant.ofAgent(1L, 5L), userMemory,
                "preference", "这个agent要详尽", "s2", 2L);
        assertNull(result);
        // 用户层那条仍然 active
        AiMemory still = service.getById(MemoryTenant.ofUser(1L), userMemory);
        assertNotNull(still);
        assertEquals("active", still.getStatus());
    }

    @Test
    void supersede_withinSameLayer_marksOldSuperseded()
    {
        Long old = service.add(MemoryTenant.ofUser(1L), "preference", "用户喜欢苹果", "s1", 1L);
        Long newId = service.supersede(MemoryTenant.ofUser(1L), old, "preference",
                "用户不喜欢苹果", "s2", 2L);
        assertNotNull(newId);
        AiMemory oldRow = service.getById(MemoryTenant.ofUser(1L), old);
        AiMemory newRow = service.getById(MemoryTenant.ofUser(1L), newId);
        assertEquals("superseded", oldRow.getStatus());
        assertEquals(newId, oldRow.getSupersededBy());
        assertEquals("active", newRow.getStatus());
    }

    @Test
    void supersede_deletesOldVectorRow()
    {
        Long old = service.add(MemoryTenant.ofUser(1L), "preference", "用户喜欢苹果", "s1", 1L);
        vectorStore.upsert(MemoryTenant.ofUser(1L), old, V1);
        Long newId = service.supersede(MemoryTenant.ofUser(1L), old, "preference",
                "用户不喜欢苹果", "s2", 2L);
        assertNotNull(newId);

        // 检索同 query:旧向量已删,若新记忆没补向量则返回空,但绝不返回旧的 active
        List<AiMemory> hits = service.search(MemoryTenant.ofUser(1L), V1, 10, 0.0);
        for (AiMemory h : hits)
        {
            assertEquals("active", h.getStatus());
            assertEquals(false, h.getMemoryId().equals(old));
        }
    }
}
