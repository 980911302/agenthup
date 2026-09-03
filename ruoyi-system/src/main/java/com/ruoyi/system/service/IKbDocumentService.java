package com.ruoyi.system.service;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.web.multipart.MultipartFile;
import com.ruoyi.system.domain.KbDocument;

/**
 * 知识库文档 服务层
 *
 * @author ruoyi
 */
public interface IKbDocumentService
{
    public KbDocument selectKbDocumentById(Long docId);

    public List<KbDocument> selectKbDocumentList(KbDocument kbDocument);

    /**
     * 上传文档到知识库:落盘 + 写记录 + 异步摄入。
     * @param onDuplicate skip=内容重复返回已有；force=忽略判重强制新建；replace 本期拒绝（业务 code 400）
     */
    public KbDocument uploadDocument(Long kbId, MultipartFile file, String createBy, String onDuplicate);

    /** @deprecated 使用带 onDuplicate 的重载 */
    default KbDocument uploadDocument(Long kbId, MultipartFile file, String createBy)
    {
        return uploadDocument(kbId, file, createBy, "skip");
    }

    /**
     * 重新处理(解析/分块/嵌入)
     */
    public void reprocess(Long docId);

    /**
     * 修改文档展示名称，不移动底层原文件。
     */
    public KbDocument renameDocument(Long docId, String docName, String updateBy);

    /**
     * 删除文档:软删记录 + 清 chunk/内存索引/IR/原文件
     */
    public int deleteDocumentByIds(Long[] docIds);

    /**
     * 产品预览：安全 HTML/文本、目录、质量摘要（不含堆栈与原始 chunk）
     */
    public Map<String, Object> buildProductPreview(Long kbId, Long docId);

    /**
     * 解析可下载本地文件；路径越界、缺失或不可读时返回 null。
     */
    public Path resolveReadableFile(KbDocument doc);

    /**
     * 面向用户响应：计算 downloadable 并清除内部路径字段。
     */
    public void sanitizeForUser(KbDocument doc);
}
