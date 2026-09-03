package com.ruoyi.common.datasource;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.enums.DataSourceType;

/**
 * 覆盖 ai-kb-issues P1:切库后应恢复外层上下文,而不是 clear。
 */
class DataSourceScopeTest
{
    @AfterEach
    void tearDown()
    {
        DynamicDataSourceContextHolder.clearDataSourceType();
    }

    @Test
    void runOn_restoresPreviousType()
    {
        DynamicDataSourceContextHolder.setDataSourceType(DataSourceType.SLAVE.name());

        String inside = DataSourceScope.runOn(DataSourceType.MASTER,
            DynamicDataSourceContextHolder::getDataSourceType);

        assertEquals(DataSourceType.MASTER.name(), inside);
        assertEquals(DataSourceType.SLAVE.name(),
            DynamicDataSourceContextHolder.getDataSourceType());
    }

    @Test
    void runOn_clearsWhenNoPrevious()
    {
        assertNull(DynamicDataSourceContextHolder.getDataSourceType());

        DataSourceScope.runOn(DataSourceType.SLAVE, () -> {
            assertEquals(DataSourceType.SLAVE.name(),
                DynamicDataSourceContextHolder.getDataSourceType());
        });

        assertNull(DynamicDataSourceContextHolder.getDataSourceType());
    }

    @Test
    void runOn_restoresEvenWhenSupplierThrows()
    {
        DynamicDataSourceContextHolder.setDataSourceType(DataSourceType.SLAVE.name());

        assertThrows(IllegalStateException.class, () ->
            DataSourceScope.runOn(DataSourceType.MASTER, () -> {
                throw new IllegalStateException("boom");
            }));

        assertEquals(DataSourceType.SLAVE.name(),
            DynamicDataSourceContextHolder.getDataSourceType());
    }

    @Test
    void restore_nullClears()
    {
        DynamicDataSourceContextHolder.setDataSourceType(DataSourceType.MASTER.name());
        DataSourceScope.restore(null);
        assertNull(DynamicDataSourceContextHolder.getDataSourceType());
    }
}
