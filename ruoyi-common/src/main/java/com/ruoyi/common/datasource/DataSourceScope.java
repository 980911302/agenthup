package com.ruoyi.common.datasource;

import java.util.function.Supplier;
import com.ruoyi.common.enums.DataSourceType;

/**
 * 手动切库的作用域工具:保存进入前的数据源上下文,退出时恢复(而不是 clear)。
 * <p>避免嵌套调用时外层 {@code @DataSource} / ThreadLocal 被误清,
 * 导致后续 mapper 掉回 MASTER。
 *
 * <pre>{@code
 * DataSourceScope.runOn(DataSourceType.SLAVE, () -> mapper.selectById(id));
 * }</pre>
 */
public final class DataSourceScope
{
    private DataSourceScope()
    {
    }

    /**
     * 在指定数据源上执行,结束后恢复进入前的上下文。
     *
     * @param type     目标数据源
     * @param supplier 业务逻辑
     * @return supplier 的返回值
     */
    public static <T> T runOn(DataSourceType type, Supplier<T> supplier)
    {
        if (type == null)
        {
            throw new IllegalArgumentException("DataSourceType 不能为空");
        }
        if (supplier == null)
        {
            throw new IllegalArgumentException("supplier 不能为空");
        }
        String prev = DynamicDataSourceContextHolder.getDataSourceType();
        try
        {
            DynamicDataSourceContextHolder.setDataSourceType(type.name());
            return supplier.get();
        }
        finally
        {
            restore(prev);
        }
    }

    /**
     * 在指定数据源上执行无返回值逻辑,结束后恢复进入前的上下文。
     */
    public static void runOn(DataSourceType type, Runnable action)
    {
        if (type == null)
        {
            throw new IllegalArgumentException("DataSourceType 不能为空");
        }
        if (action == null)
        {
            throw new IllegalArgumentException("action 不能为空");
        }
        String prev = DynamicDataSourceContextHolder.getDataSourceType();
        try
        {
            DynamicDataSourceContextHolder.setDataSourceType(type.name());
            action.run();
        }
        finally
        {
            restore(prev);
        }
    }

    /** 恢复进入前的数据源类型;原先无上下文则 clear。 */
    public static void restore(String previousType)
    {
        if (previousType != null)
        {
            DynamicDataSourceContextHolder.setDataSourceType(previousType);
        }
        else
        {
            DynamicDataSourceContextHolder.clearDataSourceType();
        }
    }
}
