package com.ruoyi.system.kb.ingest;

import com.alibaba.fastjson2.JSONObject;

/**
 * 知识库文档处理进度事件,结构对齐 ChatEventJson 风格。
 */
public final class KbIngestEventJson
{
    private KbIngestEventJson() {}

    public static String progress(Long docId, Long kbId, String status, String step, int progress)
    {
        JSONObject o = new JSONObject();
        o.put("type", "kb_progress");
        o.put("docId", docId);
        o.put("kbId", kbId);
        o.put("status", status);
        o.put("step", step);
        o.put("progress", progress);
        return o.toJSONString();
    }

    public static String completed(Long docId, Long kbId, int chunkCount)
    {
        JSONObject o = new JSONObject();
        o.put("type", "kb_completed");
        o.put("docId", docId);
        o.put("kbId", kbId);
        o.put("status", "COMPLETED");
        o.put("progress", 100);
        o.put("chunkCount", chunkCount);
        return o.toJSONString();
    }

    public static String failed(Long docId, Long kbId, String errorType, String errorStage, String errorMsg)
    {
        JSONObject o = new JSONObject();
        o.put("type", "kb_failed");
        o.put("docId", docId);
        o.put("kbId", kbId);
        o.put("status", "FAILED");
        o.put("errorType", errorType);
        o.put("errorStage", errorStage);
        // 事件流面向有 READ 权限的客户端，不广播内部异常全文；
        // 最终产品化错误由单文档详情接口按用户权限返回。
        o.put("errorMsg", "文件处理失败，请查看文档详情");
        return o.toJSONString();
    }
}
