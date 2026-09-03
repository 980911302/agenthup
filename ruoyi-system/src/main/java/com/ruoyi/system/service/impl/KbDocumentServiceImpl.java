package com.ruoyi.system.service.impl;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import com.ruoyi.common.annotation.DataSource;
import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.enums.DataSourceType;
import com.ruoyi.common.constant.Constants;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.utils.file.FileUploadUtils;
import com.ruoyi.common.utils.file.FileUtils;
import com.ruoyi.system.domain.KbDocument;
import com.ruoyi.system.domain.KbKnowledge;
import com.ruoyi.system.kb.KbDocProductError;
import com.ruoyi.system.kb.KbDocProductStatus;
import com.ruoyi.system.kb.KbDocPreviewBuilder;
import com.ruoyi.system.kb.KbDocStatus;
import com.ruoyi.system.kb.KbDocumentFileAccess;
import com.ruoyi.system.kb.graph.KbGraphStore;
import com.ruoyi.system.kb.ingest.KbIngestExecutor;
import com.ruoyi.system.kb.parser.IrDoc;
import com.ruoyi.system.kb.parser.IrFileStore;
import com.ruoyi.system.kb.parser.KbFileFormats;
import com.ruoyi.system.kb.vector.KbVectorStore;
import com.ruoyi.system.mapper.KbChunkMapper;
import com.ruoyi.system.mapper.KbDocGraphMapper;
import com.ruoyi.system.mapper.KbDocumentMapper;
import com.ruoyi.system.mapper.KbGraphRunMapper;
import com.ruoyi.system.mapper.KbGraphTextUnitMapper;
import com.ruoyi.system.mapper.KbKnowledgeMapper;
import com.ruoyi.system.service.IKbDocumentService;

/**
 * 知识库文档 服务层实现(数据在 PostgreSQL slave)。
 *
 * @author ruoyi
 */
@Service
@DataSource(DataSourceType.SLAVE)
public class KbDocumentServiceImpl implements IKbDocumentService
{
    private static final Logger log = LoggerFactory.getLogger(KbDocumentServiceImpl.class);

    /** 知识库支持解析的格式(必须与 KbParser 实现保持一致) */
    private static final String[] KB_ALLOWED_EXTENSION = KbFileFormats.allowedExtensions();

    @Autowired
    private KbDocumentMapper kbDocumentMapper;
    @Autowired
    private KbChunkMapper kbChunkMapper;
    @Autowired
    private KbKnowledgeMapper kbKnowledgeMapper;
    @Autowired
    private KbIngestExecutor kbIngestExecutor;
    @Autowired
    private IrFileStore irFileStore;
    @Autowired
    private KbVectorStore kbVectorStore;
    @Autowired
    private KbGraphStore kbGraphStore;
    @Autowired
    private KbDocGraphMapper kbDocGraphMapper;
    @Autowired
    private KbGraphRunMapper kbGraphRunMapper;
    @Autowired
    private KbGraphTextUnitMapper kbGraphTextUnitMapper;

    @Override
    public KbDocument selectKbDocumentById(Long docId)
    {
        return kbDocumentMapper.selectKbDocumentById(docId);
    }

    @Override
    public List<KbDocument> selectKbDocumentList(KbDocument kbDocument)
    {
        return kbDocumentMapper.selectKbDocumentList(kbDocument);
    }

    @Override
    @Transactional
    public KbDocument renameDocument(Long docId, String docName, String updateBy)
    {
        KbDocument update = new KbDocument();
        update.setDocId(docId);
        update.setDocName(docName);
        update.setUpdateBy(updateBy);
        if (kbDocumentMapper.updateKbDocument(update) <= 0)
        {
            throw new ServiceException("文档不存在或已被删除");
        }
        return kbDocumentMapper.selectKbDocumentById(docId);
    }

    @Override
    public KbDocument uploadDocument(Long kbId, MultipartFile file, String createBy, String onDuplicate)
    {
        if (file == null || file.isEmpty())
        {
            throw new ServiceException("请选择要上传的文件", HttpStatus.BAD_REQUEST);
        }
        String policy = StringUtils.isEmpty(onDuplicate) ? "skip" : onDuplicate.trim().toLowerCase();
        // replace 当前实现先删旧再传，失败会丢原文；本期前后端均禁用，旧文档不得有任何删除或状态变化
        if ("replace".equals(policy))
        {
            throw new ServiceException(
                "不支持替换上传（onDuplicate=replace）。请使用 skip 跳过重复，或 force 保留副本",
                HttpStatus.BAD_REQUEST);
        }
        if (!"skip".equals(policy) && !"force".equals(policy))
        {
            policy = "skip";
        }
        // 直接 mapper,避免嵌套 @DataSource 服务清掉 SLAVE 上下文
        KbKnowledge kb = kbKnowledgeMapper.selectKbKnowledgeById(kbId);
        if (kb == null)
        {
            throw new ServiceException("知识库不存在");
        }

        String contentHash = sha256(file);
        // 同一知识库内容重复
        if (StringUtils.isNotEmpty(contentHash) && !"force".equals(policy))
        {
            KbDocument existing = kbDocumentMapper.selectByKbIdAndContentHash(kbId, contentHash);
            if (existing != null)
            {
                // skip：返回已有，前端可识别 duplicate；不得删除旧文档
                log.info("知识库[{}]文档内容重复,返回已有 docId={}", kbId, existing.getDocId());
                existing.setRemark("DUPLICATE");
                return existing;
            }
        }

        String baseDir = RuoYiConfig.getUploadPath() + "/kb/" + kbId;
        String fileName;
        try
        {
            fileName = FileUploadUtils.upload(baseDir, file, KB_ALLOWED_EXTENSION);
        }
        catch (Exception e)
        {
            throw new ServiceException("上传失败: " + e.getMessage());
        }

        KbDocument doc = new KbDocument();
        doc.setKbId(kbId);
        doc.setDocName(file.getOriginalFilename());
        doc.setFilePath(fileName);
        doc.setFileSize(file.getSize());
        doc.setFileType(FileUploadUtils.getExtension(file));
        doc.setContentHash(contentHash);
        doc.setParseStatus(KbDocStatus.PENDING.name());
        doc.setParseStep("PENDING");
        doc.setProgress(0);
        doc.setChunkCount(0);
        doc.setStatus("0");
        doc.setCreateBy(createBy);
        kbDocumentMapper.insertKbDocument(doc);

        // 异步摄入
        kbIngestExecutor.submit(doc.getDocId());
        return doc;
    }

    @Override
    public Map<String, Object> buildProductPreview(Long kbId, Long docId)
    {
        KbDocument doc = requireDoc(kbId, docId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("docId", doc.getDocId());
        out.put("kbId", doc.getKbId());
        out.put("docName", doc.getDocName());
        out.put("fileType", doc.getFileType());
        out.put("fileSize", doc.getFileSize());
        // 不向普通用户暴露内部 filePath
        out.put("downloadable", KbDocumentFileAccess.isDownloadable(doc));
        out.put("createBy", doc.getCreateBy());
        out.put("createTime", doc.getCreateTime());
        out.put("updateTime", doc.getUpdateTime());
        out.put("chunkCount", doc.getChunkCount());
        out.put("parseStatus", doc.getParseStatus());
        out.put("productStatus", KbDocProductStatus.fromParseStatus(doc.getParseStatus()));
        out.put("progress", doc.getProgress());
        out.put("productError", KbDocProductError.message(doc));
        out.put("productSuggestion", KbDocProductError.suggestion(doc));

        String product = KbDocProductStatus.fromParseStatus(doc.getParseStatus());
        if (KbDocProductStatus.FAILED.equals(product))
        {
            out.put("preview", Map.of("available", false, "reason", "FAILED"));
            out.put("quality", Map.of(
                "status", "FAIL",
                "grade", "POOR",
                "warnings", List.of(KbDocProductError.message(doc)),
                "suggestions", List.of(KbDocProductError.suggestion(doc) == null
                    ? "重新处理" : KbDocProductError.suggestion(doc))
            ));
            return out;
        }
        if (KbDocProductStatus.isBusy(doc.getParseStatus()))
        {
            out.put("preview", Map.of("available", false, "reason", "PROCESSING",
                "progress", doc.getProgress() == null ? 0 : doc.getProgress()));
            out.put("quality", Map.of("status", "PENDING", "grade", "UNKNOWN", "warnings", List.of()));
            return out;
        }
        if (StringUtils.isEmpty(doc.getIrPath()))
        {
            out.put("preview", Map.of("available", false, "reason", "NO_IR"));
            out.put("quality", Map.of("status", "UNKNOWN", "grade", "UNKNOWN", "warnings",
                List.of("尚无解析产物，可尝试重新处理")));
            return out;
        }
        try
        {
            IrDoc ir = irFileStore.load(doc.getIrPath());
            Map<String, Object> preview = KbDocPreviewBuilder.build(ir, KbDocPreviewBuilder.DEFAULT_BLOCK_LIMIT);
            out.put("preview", preview);
            out.put("quality", preview.get("quality"));
        }
        catch (Exception e)
        {
            log.warn("加载预览失败 docId={}: {}", docId, e.getMessage());
            out.put("preview", Map.of("available", false, "reason", "IR_LOAD_ERROR"));
            out.put("quality", Map.of("status", "UNKNOWN", "grade", "UNKNOWN",
                "warnings", List.of("预览暂时不可用")));
        }
        return out;
    }

    private KbDocument requireDoc(Long kbId, Long docId)
    {
        KbDocument doc = kbDocumentMapper.selectKbDocumentById(docId);
        if (doc == null || (kbId != null && !kbId.equals(doc.getKbId())))
        {
            throw new ServiceException("文档不存在");
        }
        return doc;
    }

    @Override
    public void reprocess(Long docId)
    {
        KbDocument doc = kbDocumentMapper.selectKbDocumentById(docId);
        if (doc == null)
        {
            throw new ServiceException("文档不存在");
        }
        doc.setParseStatus(KbDocStatus.PENDING.name());
        doc.setParseStep("PENDING");
        doc.setProgress(0);
        doc.setErrorType(null);
        doc.setErrorStage(null);
        doc.setErrorMsg(null);
        kbDocumentMapper.updateParseProgress(doc);
        kbIngestExecutor.submit(docId);
    }

    @Override
    @Transactional
    public int deleteDocumentByIds(Long[] docIds)
    {
        if (docIds == null || docIds.length == 0)
        {
            return 0;
        }
        List<KbDocument> docs = kbDocumentMapper.selectKbDocumentByIds(docIds);
        // 连带清理:图引用 → 向量 → chunk → IR/文件。删图必须在删 chunk 之前(要知道 sourceIds)。
        for (KbDocument doc : docs)
        {
            // 顺序不可反:必须先取 chunkId 再删向量/图,最后删 chunk。
            List<Long> chunkIds = kbChunkMapper.selectChunkIdsByDocId(doc.getDocId());
            try
            {
                // 血统 v2：按 doc 精确删除证据并重算共享实体/关系
                kbGraphStore.deleteDocumentEvidence(doc.getKbId(), doc.getDocId());
            }
            catch (Exception e)
            {
                log.warn("删除文档时清理图证据失败 docId={}: {}", doc.getDocId(), e.getMessage());
                // 兼容回退
                if (chunkIds != null && !chunkIds.isEmpty())
                {
                    try
                    {
                        kbGraphStore.removeChunkRefs(doc.getKbId(), chunkIds);
                    }
                    catch (Exception ex)
                    {
                        log.warn("删除文档时摘图引用失败 docId={}: {}", doc.getDocId(), ex.getMessage());
                    }
                }
            }
            if (chunkIds != null && !chunkIds.isEmpty())
            {
                kbVectorStore.deleteByChunkIds(doc.getKbId(), chunkIds);
            }
            kbDocGraphMapper.deleteByDocId(doc.getDocId());
            try
            {
                kbGraphRunMapper.deleteByDocId(doc.getDocId());
            }
            catch (Exception e)
            {
                log.debug("删除 kb_graph_run 失败(可忽略): {}", e.getMessage());
            }
            try
            {
                kbGraphTextUnitMapper.deleteMappingsByDocId(doc.getDocId());
                kbGraphTextUnitMapper.deleteByDocId(doc.getDocId());
            }
            catch (Exception e)
            {
                log.debug("删除 graph text unit 失败(可忽略): {}", e.getMessage());
            }
            kbChunkMapper.deleteByDocId(doc.getDocId());
            irFileStore.deleteQuietly(doc.getIrPath());
            deleteFileQuietly(doc.getFilePath());
        }
        return kbDocumentMapper.deleteKbDocumentByIds(docIds);
    }

    @Override
    public Path resolveReadableFile(KbDocument doc)
    {
        if (doc == null)
        {
            return null;
        }
        Path path = KbDocumentFileAccess.resolveLocalFile(doc.getKbId(), doc.getFilePath());
        if (path == null || !KbDocumentFileAccess.isDownloadable(doc.getKbId(), doc.getFilePath()))
        {
            return null;
        }
        return path;
    }

    @Override
    public void sanitizeForUser(KbDocument doc)
    {
        KbDocumentFileAccess.sanitizeForUser(doc);
    }

    private void deleteFileQuietly(String filePath)
    {
        if (StringUtils.isEmpty(filePath))
        {
            return;
        }
        try
        {
            String relative = StringUtils.substringAfter(filePath, Constants.RESOURCE_PREFIX);
            File file = FileUploadUtils.getAbsoluteFile(RuoYiConfig.getProfile(), relative);
            if (file.exists() && file.isFile())
            {
                FileUtils.deleteFile(file.getAbsolutePath());
            }
        }
        catch (Exception e)
        {
            log.warn("删除知识库文档文件失败: {} - {}", filePath, e.getMessage());
        }
    }

    private static String sha256(MultipartFile file)
    {
        try
        {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = file.getInputStream();
                 DigestInputStream din = new DigestInputStream(in, md))
            {
                byte[] buf = new byte[8192];
                while (din.read(buf) != -1)
                {
                    // drain
                }
            }
            return HexFormat.of().formatHex(md.digest());
        }
        catch (Exception e)
        {
            log.warn("计算文件 hash 失败: {}", e.getMessage());
            return null;
        }
    }
}
