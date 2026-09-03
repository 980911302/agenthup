package com.ruoyi.system.kb.parser;

/**
 * 知识库上传与解析共用的格式清单,避免上传白名单和 Parser 能力漂移。
 */
public final class KbFileFormats
{
    private KbFileFormats()
    {
    }

    private static final String[] ALLOWED_EXTENSIONS = {
        "pdf", "doc", "docx", "rtf",
        "xls", "xlsx", "ppt", "pptx",
        "txt", "md", "markdown", "html", "htm",
        "csv", "tsv", "json", "xml"
    };

    public static String[] allowedExtensions()
    {
        return ALLOWED_EXTENSIONS.clone();
    }
}
