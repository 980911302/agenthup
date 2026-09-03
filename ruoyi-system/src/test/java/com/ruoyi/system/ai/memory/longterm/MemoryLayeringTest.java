package com.ruoyi.system.ai.memory.longterm;

import com.ruoyi.system.domain.AiMemory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 记忆分层检索与遮蔽规则测试(spec §6.3)。
 *
 * <p>覆盖:一次 SQL 查两层;冲突时 agent 层遮蔽用户层且不删除;无冲突时两层都可见。
 */
class MemoryLayeringTest extends MemoryTestSupport
{
    /** 测试用固定向量(向量化非本测试关注点) */
    private static final float[] V = {0.1f, 0.2f, 0.3f, 0.4f};

    @Test
    void agentScope_shadowsUserScope_withoutDeleting()
    {
        // 用户层:回复要简洁
        Long userPref = service.add(MemoryTenant.ofUser(1L), "preference", "回复要简洁", "s1", 1L);
        // agent 层:该 agent 要详尽
        Long agentPref = service.add(MemoryTenant.ofAgent(1L, 5L), "preference", "回复要详尽", "s2", 2L);
        vectorStore.upsert(MemoryTenant.ofUser(1L), userPref, V);
        vectorStore.upsert(MemoryTenant.ofAgent(1L, 5L), agentPref, V);

        // 检索两层都返回(遮蔽发生在注入组装层 MemoryRetriever,不在 store 层)
        List<AiMemory> hits = service.search(MemoryTenant.ofAgent(1L, 5L), V, 10, 0.0);
        assertEquals(2, hits.size());
        // 用户层那条没被删(遮蔽不删除,spec §6.3 规则2)
        assertNotNull(service.getById(MemoryTenant.ofUser(1L), userPref));
    }

    @Test
    void userScope_memories_stillVisible_whenNoConflict()
    {
        // 只有用户层有这条事实,没有 agent 层冲突
        Long userFact = service.add(MemoryTenant.ofUser(1L), "fact", "用户在北京工作", "s1", 1L);
        vectorStore.upsert(MemoryTenant.ofUser(1L), userFact, V);

        List<AiMemory> hits = service.search(MemoryTenant.ofAgent(1L, 5L), V, 10, 0.0);
        assertEquals(1, hits.size());
        assertEquals(userFact, hits.get(0).getMemoryId());
    }

    @Test
    void search_usesBothLayers_whenNoConflict()
    {
        Long userFact = service.add(MemoryTenant.ofUser(1L), "fact", "用户在北京工作", "s1", 1L);
        Long agentFact = service.add(MemoryTenant.ofAgent(1L, 5L), "fact", "用户在agent5的工单号是X", "s2", 2L);
        vectorStore.upsert(MemoryTenant.ofUser(1L), userFact, V);
        vectorStore.upsert(MemoryTenant.ofAgent(1L, 5L), agentFact, V);

        List<AiMemory> hits = service.search(MemoryTenant.ofAgent(1L, 5L), V, 10, 0.0);
        assertEquals(2, hits.size());
    }
}
