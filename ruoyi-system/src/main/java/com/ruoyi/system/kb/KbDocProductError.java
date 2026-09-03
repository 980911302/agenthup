package com.ruoyi.system.kb;

import com.ruoyi.system.domain.KbDocument;

/**
 * 文档失败原因产品化文案（普通用户可见；堆栈仅诊断接口返回）。
 */
public final class KbDocProductError
{
    private KbDocProductError() {}

    public static String message(KbDocument doc)
    {
        if (doc == null)
        {
            return "处理失败";
        }
        if (!KbDocStatus.FAILED.name().equals(doc.getParseStatus()))
        {
            return null;
        }
        String type = doc.getErrorType() == null ? "" : doc.getErrorType().toUpperCase();
        String stage = doc.getErrorStage() == null ? "" : doc.getErrorStage().toLowerCase();
        String raw = doc.getErrorMsg() == null ? "" : doc.getErrorMsg();

        if (type.contains("OCR") || raw.contains("扫描") || raw.contains("OCR"))
        {
            return "疑似扫描件或图片型 PDF，当前无法提取文字。请提供可检索文本版，或等待 OCR 能力。";
        }
        if (type.contains("FORMAT") || type.contains("UNSUPPORTED") || raw.contains("不支持"))
        {
            return "文件格式不受支持或已损坏，请转换为 PDF/DOCX/XLSX/PPTX/Markdown 等正式格式后重试。";
        }
        if (type.contains("ENCODE") || raw.contains("乱码") || raw.contains("编码"))
        {
            return "文本编码异常导致解析失败，请检查文件编码后重新上传。";
        }
        if ("parse".equals(stage) || stage.contains("parse"))
        {
            return "文档解析失败，可能文件损坏或结构异常。可尝试重新处理或更换文件。";
        }
        if ("chunk".equals(stage) || stage.contains("chunk"))
        {
            return "内容切片失败，请重新处理；若持续失败请联系管理员查看诊断。";
        }
        if ("embed".equals(stage) || stage.contains("embed") || stage.contains("vector"))
        {
            return "向量化失败，通常是嵌入模型或渠道暂时不可用，请稍后重新处理。";
        }
        if (raw.length() > 0 && raw.length() < 120 && !looksLikeStack(raw))
        {
            return raw;
        }
        return "文档处理失败，请重新处理；若仍失败请联系管理员查看处理诊断。";
    }

    public static String suggestion(KbDocument doc)
    {
        if (doc == null || !KbDocStatus.FAILED.name().equals(doc.getParseStatus()))
        {
            return null;
        }
        String type = doc.getErrorType() == null ? "" : doc.getErrorType().toUpperCase();
        String stage = doc.getErrorStage() == null ? "" : doc.getErrorStage().toLowerCase();
        if (type.contains("OCR") || (doc.getErrorMsg() != null && doc.getErrorMsg().contains("扫描")))
        {
            return "替换为文字版 PDF 或 DOCX 后重新上传";
        }
        if ("embed".equals(stage) || stage.contains("embed"))
        {
            return "确认知识引擎向量模型可用后，点击「重新处理」";
        }
        return "点击「重新处理」；仍失败时由管理员打开处理诊断";
    }

    private static boolean looksLikeStack(String raw)
    {
        return raw.contains("\tat ") || raw.contains("Exception") || raw.contains("Caused by:");
    }
}
