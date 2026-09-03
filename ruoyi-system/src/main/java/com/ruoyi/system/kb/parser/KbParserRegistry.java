package com.ruoyi.system.kb.parser;

import java.io.File;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;

/**
 * 按扩展名路由到具体解析器。
 */
@Component
public class KbParserRegistry
{
    private static final Logger log = LoggerFactory.getLogger(KbParserRegistry.class);

    @Autowired
    private List<KbParser> parsers;

    public IrDoc parse(File file, String fileName) throws Exception
    {
        String ext = extensionOf(fileName);
        Exception primaryFailure = null;
        for (KbParser parser : parsers)
        {
            if (!parser.isFallback() && parser.supports(ext))
            {
                try
                {
                    return mark(parser.parse(file, fileName), parser);
                }
                catch (Exception e)
                {
                    primaryFailure = e;
                    log.warn("专用解析器失败,准备尝试兜底 file={} parser={} error={}",
                        fileName, parser.getClass().getSimpleName(), e.toString());
                    log.debug("专用解析器异常详情 file={}", fileName, e);
                    break;
                }
            }
        }
        try
        {
            IrDoc fallback = parseFallback(file, fileName);
            if (fallback != null)
            {
                return fallback;
            }
        }
        catch (Exception fallbackFailure)
        {
            if (primaryFailure != null)
            {
                fallbackFailure.addSuppressed(primaryFailure);
            }
            throw fallbackFailure;
        }
        if (primaryFailure != null)
        {
            throw primaryFailure;
        }
        throw new ServiceException("不支持的文件格式: " + ext);
    }

    /**
     * 显式调用兜底解析器,供质量门禁失败后进行第二次提取。
     */
    public IrDoc parseFallback(File file, String fileName) throws Exception
    {
        String ext = extensionOf(fileName);
        for (KbParser parser : parsers)
        {
            if (parser.isFallback() && parser.supports(ext))
            {
                return mark(parser.parse(file, fileName), parser);
            }
        }
        return null;
    }

    public boolean hasFallback(String fileName)
    {
        String ext = extensionOf(fileName);
        return parsers.stream().anyMatch(parser -> parser.isFallback() && parser.supports(ext));
    }

    public boolean supports(String fileName)
    {
        String ext = extensionOf(fileName);
        for (KbParser parser : parsers)
        {
            if (parser.supports(ext))
            {
                return true;
            }
        }
        return false;
    }

    private static String extensionOf(String fileName)
    {
        if (StringUtils.isEmpty(fileName))
        {
            return "";
        }
        int i = fileName.lastIndexOf('.');
        return i >= 0 ? fileName.substring(i + 1).toLowerCase() : "";
    }

    private static IrDoc mark(IrDoc doc, KbParser parser)
    {
        if (doc == null)
        {
            throw new IllegalStateException("解析器未返回文档: " + parser.getClass().getSimpleName());
        }
        if (StringUtils.isEmpty(doc.getExtractor()))
        {
            doc.setExtractor(parser.getClass().getSimpleName());
        }
        doc.setFallbackUsed(parser.isFallback());
        return doc;
    }
}
