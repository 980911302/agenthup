package com.ruoyi.system.kb.parser;

import java.io.File;

/**
 * 文档解析器:File → IrDoc。
 */
public interface KbParser
{
    /**
     * 是否为兜底解析器。兜底解析器只在专用解析器异常或质量门禁失败时使用。
     */
    default boolean isFallback()
    {
        return false;
    }

    /**
     * 是否支持该扩展名(小写,无点)。
     */
    boolean supports(String extension);

    /**
     * 解析文件为 IR。
     *
     * @param file     本地文件
     * @param fileName 原始文件名(用于元信息)
     * @return IR
     */
    IrDoc parse(File file, String fileName) throws Exception;
}
