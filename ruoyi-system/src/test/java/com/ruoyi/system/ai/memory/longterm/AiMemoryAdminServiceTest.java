package com.ruoyi.system.ai.memory.longterm;

import java.util.List;
import org.junit.jupiter.api.Test;
import com.ruoyi.system.domain.AiMemory;
import com.ruoyi.system.service.impl.AiMemoryAdminServiceImpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 管理端删除必须同时处理台账与关联向量。 */
class AiMemoryAdminServiceTest extends MemoryTestSupport
{
    @Test
    void delete_removesVectorBeforeSoftDeletingLedger() throws Exception
    {
        MemoryTenant tenant = MemoryTenant.ofUser(100L);
        Long memoryId = service.add(tenant, "fact", "用户叫张林林", "session-1", 1L);
        vectorStore.upsert(tenant, memoryId, new float[] {1f, 0f});
        session.commit();
        assertTrue(vectorStore.hasVector(memoryId));

        AiMemoryAdminServiceImpl admin = new AiMemoryAdminServiceImpl();
        setField(admin, "memoryMapper", mapper);
        setField(admin, "vectorStore", vectorStore);

        assertEquals(1, admin.deleteAiMemoryByIds(new Long[] {memoryId}));
        session.commit();

        assertFalse(vectorStore.hasVector(memoryId));
        assertTrue(mapper.selectAdminByIds(List.of(memoryId)).isEmpty());
    }
}
