package com.ruoyi.framework.datasource;

/**
 * 兼容旧包路径,委托 {@link com.ruoyi.common.datasource.DynamicDataSourceContextHolder}。
 *
 * @deprecated 请直接使用 common 包
 */
@Deprecated
public class DynamicDataSourceContextHolder
{
    public static void setDataSourceType(String dsType)
    {
        com.ruoyi.common.datasource.DynamicDataSourceContextHolder.setDataSourceType(dsType);
    }

    public static String getDataSourceType()
    {
        return com.ruoyi.common.datasource.DynamicDataSourceContextHolder.getDataSourceType();
    }

    public static void clearDataSourceType()
    {
        com.ruoyi.common.datasource.DynamicDataSourceContextHolder.clearDataSourceType();
    }
}
