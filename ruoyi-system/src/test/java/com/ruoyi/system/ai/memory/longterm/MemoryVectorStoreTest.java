package com.ruoyi.system.ai.memory.longterm;

import org.junit.jupiter.api.Test;
import com.ruoyi.common.datasource.DynamicDataSourceContextHolder;
import com.ruoyi.system.mapper.MemVectorMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * 记忆向量存储的维度路由与字面量测试。
 *
 * <p>{@code PgMemoryVectorStore} 的 SQL 行为走真库集成测试;这里锁纯计算:
 * 维度白名单路由(toVectorLiteral)与不支持维度抛错。
 */
class MemoryVectorStoreTest
{
    @Test
    void toVectorLiteral_formatsPgvectorArray()
    {
        float[] v = {0.1f, 0.2f, 0.3f};
        assertEquals("[0.1,0.2,0.3]", PgMemoryVectorStore.toVectorLiteral(v));
    }

    @Test
    void toVectorLiteral_singleElement()
    {
        float[] v = {1.5f};
        assertEquals("[1.5]", PgMemoryVectorStore.toVectorLiteral(v));
    }

    @Test
    void requireDim_unsupported_throws()
    {
        // 4096 超出预建上限,应抛 ServiceException
        assertThrows(Exception.class, () -> PgMemoryVectorStore.requireDim(4096));
    }

    @Test
    void requireDim_supported_ok()
    {
        // 预建维度不抛
        for (int dim : new int[] {768, 1024, 1536, 3072})
        {
            PgMemoryVectorStore.requireDim(dim);
        }
    }

    @Test
    void upsert_runsOnPostgresSlaveAndRestoresPreviousContext() throws Exception
    {
        PgMemoryVectorStore store = new PgMemoryVectorStore();
        MemVectorMapper mapper = mock(MemVectorMapper.class);
        String[] datasourceAtMapper = new String[1];
        doAnswer(invocation -> {
            datasourceAtMapper[0] = DynamicDataSourceContextHolder.getDataSourceType();
            return 1;
        }).when(mapper).upsert(anyInt(), any(), any(), any(), any());
        var field = PgMemoryVectorStore.class.getDeclaredField("memVectorMapper");
        field.setAccessible(true);
        field.set(store, mapper);

        DynamicDataSourceContextHolder.setDataSourceType("MASTER");
        try
        {
            store.upsert(new MemoryTenant(1L, 0L), 1L, new float[1024]);

            assertEquals("SLAVE", datasourceAtMapper[0]);
            assertEquals("MASTER", DynamicDataSourceContextHolder.getDataSourceType());
        }
        finally
        {
            DynamicDataSourceContextHolder.clearDataSourceType();
        }
    }
}
