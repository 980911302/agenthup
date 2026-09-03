package com.ruoyi.system.kb.ingest;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.constant.Constants;
import com.ruoyi.common.enums.DataSourceType;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.utils.file.FileUploadUtils;
import com.ruoyi.common.datasource.DynamicDataSourceContextHolder;
import com.ruoyi.system.ai.EmbeddingModelFactory;
import com.ruoyi.system.domain.AiModel;
import com.ruoyi.system.domain.KbChunk;
import com.ruoyi.system.domain.KbDocument;
import com.ruoyi.system.domain.KbKnowledge;
import com.ruoyi.system.kb.KbConstants;
import com.ruoyi.system.kb.KbDocStatus;
import com.ruoyi.system.kb.graph.KbGraphExecutor;
import com.ruoyi.system.kb.chunker.ChunkDraft;
import com.ruoyi.system.kb.chunker.ChunkLevels;
import com.ruoyi.system.kb.chunker.ChunkParams;
import com.ruoyi.system.kb.chunker.KbChunkerRegistry;
import com.ruoyi.system.kb.chunker.ParentChunkBuilder;
import com.ruoyi.system.kb.parser.IrDoc;
import com.ruoyi.system.kb.parser.IrFileStore;
import com.ruoyi.system.kb.parser.IrQualityReport;
import com.ruoyi.system.kb.parser.IrQualityValidator;
import com.ruoyi.system.kb.parser.KbParserRegistry;
import com.ruoyi.system.kb.vector.KbChunkVector;
import com.ruoyi.system.kb.vector.KbVectorStore;
import com.ruoyi.system.mapper.KbChunkMapper;
import com.ruoyi.system.mapper.KbDocumentMapper;
import com.ruoyi.system.mapper.KbKnowledgeMapper;
import com.ruoyi.system.service.IAiModelService;

/**
 * 摄入编排:解析 → 分块 → 嵌入 → 落库(PG) + 向量(pgvector)。
 * <p>手动切换数据源:KB 写 PG(SLAVE),取嵌入模型走 MySQL(MASTER)。
 * 不用嵌套 {@code @DataSource} —— 若依 AOP 的 finally 会 clear 而不恢复父上下文。
 */
@Component
public class KbIngestPipeline
{
    private static final Logger log = LoggerFactory.getLogger(KbIngestPipeline.class);

    @Autowired
    private KbDocumentMapper kbDocumentMapper;
    @Autowired
    private KbChunkMapper kbChunkMapper;
    @Autowired
    private KbKnowledgeMapper kbKnowledgeMapper;
    @Autowired
    private IAiModelService aiModelService;
    @Autowired
    private KbParserRegistry parserRegistry;
    @Autowired
    private IrFileStore irFileStore;
    @Autowired
    private IrQualityValidator irQualityValidator;
    @Autowired
    private KbChunkerRegistry chunkerRegistry;
    @Autowired
    private EmbeddingModelFactory embeddingModelFactory;
    @Autowired
    private KbVectorStore kbVectorStore;
    @Autowired
    private KbIngestProgressHub progressHub;
    @Autowired
    private KbGraphExecutor kbGraphExecutor;

    public void process(Long docId)
    {
        try
        {
            DynamicDataSourceContextHolder.setDataSourceType(DataSourceType.SLAVE.name());
            doProcess(docId);
        }
        finally
        {
            DynamicDataSourceContextHolder.clearDataSourceType();
        }
    }

    private void doProcess(Long docId)
    {
        KbDocument doc = kbDocumentMapper.selectKbDocumentById(docId);
        if (doc == null)
        {
            log.warn("摄入跳过:文档不存在 docId={}", docId);
            return;
        }
        KbKnowledge kb = kbKnowledgeMapper.selectKbKnowledgeById(doc.getKbId());
        if (kb == null)
        {
            fail(doc, "kb_missing", "parse", "所属知识库不存在");
            return;
        }
        if (StringUtils.isEmpty(kb.getEmbeddingModelCode()))
        {
            fail(doc, "embedding_model_missing", "embed", "知识库未配置嵌入模型");
            return;
        }

        // 嵌入模型在 MySQL:短暂切 MASTER,取完立刻回 SLAVE
        AiModel embModel;
        try
        {
            DynamicDataSourceContextHolder.setDataSourceType(DataSourceType.MASTER.name());
            embModel = aiModelService.selectByModelCode(kb.getEmbeddingModelCode());
        }
        finally
        {
            DynamicDataSourceContextHolder.setDataSourceType(DataSourceType.SLAVE.name());
        }
        if (embModel == null)
        {
            fail(doc, "embedding_model_missing", "embed", "嵌入模型不存在: " + kb.getEmbeddingModelCode());
            return;
        }

        try
        {
            updateProgress(doc, KbDocStatus.PARSING, "PARSING", 5);
            File file = resolveFile(doc.getFilePath());
            if (file == null || !file.isFile())
            {
                fail(doc, "file_not_found", "parse", "文件不存在: " + doc.getFilePath());
                return;
            }
            IrDoc irDoc = parserRegistry.parse(file, doc.getDocName());
            IrQualityReport quality = irQualityValidator.inspect(irDoc);
            if (!quality.isAccepted() && !irDoc.isFallbackUsed()
                && parserRegistry.hasFallback(doc.getDocName()))
            {
                try
                {
                    IrDoc fallbackDoc = parserRegistry.parseFallback(file, doc.getDocName());
                    IrQualityReport fallbackQuality = irQualityValidator.inspect(fallbackDoc);
                    if (fallbackQuality.isAccepted())
                    {
                        log.warn("专用解析质量未通过,Tika 兜底成功 docId={} primary={} reason={}",
                            docId, irDoc.getExtractor(), quality.summary());
                        irDoc = fallbackDoc;
                        quality = fallbackQuality;
                    }
                    else
                    {
                        quality.getWarnings().add("Apache Tika 兜底同样未通过: " + fallbackQuality.summary());
                    }
                }
                catch (Exception fallbackError)
                {
                    log.warn("Tika 质量兜底失败 docId={}", docId, fallbackError);
                    quality.getWarnings().add("Apache Tika 兜底失败: "
                        + fallbackError.getClass().getSimpleName());
                }
            }
            irDoc.setQuality(quality);
            String irPath = irFileStore.save(doc.getKbId(), doc.getDocId(), irDoc);
            doc.setIrPath(irPath);
            doc.setParserVersion(KbConstants.PARSER_VERSION);
            if (!quality.isAccepted())
            {
                fail(doc, "extraction_quality_failed", "parse", quality.summary());
                return;
            }
            updateProgress(doc, KbDocStatus.PARSING, "PARSING_COMPLETE", KbConstants.PROGRESS_PARSE);

            updateProgress(doc, KbDocStatus.CHUNKING, "CHUNKING_START", KbConstants.PROGRESS_PARSE + 2);
            ChunkParams params = new ChunkParams(kb.getChunkStrategy(), kb.getChunkSize(), kb.getChunkOverlap());
            List<ChunkDraft> leafDrafts = chunkerRegistry.chunk(irDoc, params);
            if (leafDrafts.isEmpty())
            {
                fail(doc, "empty_content", "chunk", "文档解析后无有效文本内容");
                return;
            }
            // 父子切片：PARENT 不参与嵌入；chunk_count 仅计 LEAF
            int parentMax = ParentChunkBuilder.defaultParentMaxTokens(params.getChunkSize());
            List<ChunkDraft> parentDrafts = ParentChunkBuilder.buildParents(leafDrafts, parentMax);
            updateProgress(doc, KbDocStatus.CHUNKING, "CHUNKING_COMPLETE",
                KbConstants.PROGRESS_PARSE + KbConstants.PROGRESS_CHUNK);

            updateProgress(doc, KbDocStatus.EMBEDDING, "EMBEDDING_START",
                KbConstants.PROGRESS_PARSE + KbConstants.PROGRESS_CHUNK + 1);

            // EmbeddingModel 工厂读 MySQL 渠道配置
            EmbeddingModel embeddingModel;
            try
            {
                DynamicDataSourceContextHolder.setDataSourceType(DataSourceType.MASTER.name());
                embeddingModel = embeddingModelFactory.get(embModel.getModelId());
            }
            finally
            {
                DynamicDataSourceContextHolder.setDataSourceType(DataSourceType.SLAVE.name());
            }

            // 先清旧 chunk + 向量
            kbVectorStore.deleteByDoc(doc.getKbId(), doc.getDocId());
            kbChunkMapper.deleteByDocId(doc.getDocId());

            // 阶段 1：先插 PARENT 取 ID
            Map<Integer, Long> leafIndexToParentId = new HashMap<>();
            if (!parentDrafts.isEmpty())
            {
                List<KbChunk> parentRows = new ArrayList<>(parentDrafts.size());
                for (ChunkDraft pd : parentDrafts)
                {
                    parentRows.add(toChunkRow(doc, kb, pd, 0, null));
                }
                batchInsert(parentRows);
                List<KbChunk> savedParents = kbChunkMapper.selectByDocIdAndLevel(
                    doc.getDocId(), ChunkLevels.PARENT);
                Map<Integer, Long> parentIndexToId = new HashMap<>();
                for (KbChunk p : savedParents)
                {
                    if (p.getChunkIndex() != null)
                    {
                        parentIndexToId.put(p.getChunkIndex(), p.getChunkId());
                    }
                }
                for (ChunkDraft pd : parentDrafts)
                {
                    Long parentId = parentIndexToId.get(pd.getChunkIndex());
                    if (parentId == null || pd.getChildLeafIndices() == null)
                    {
                        continue;
                    }
                    for (Integer leafIdx : pd.getChildLeafIndices())
                    {
                        if (leafIdx != null)
                        {
                            leafIndexToParentId.put(leafIdx, parentId);
                        }
                    }
                }
            }

            // 阶段 2：只嵌 LEAF，回填 parent_chunk_id
            Map<Integer, float[]> embByIndex = new HashMap<>();
            int total = leafDrafts.size();
            int batchSize = KbConstants.EMBED_BATCH_SIZE;
            List<KbChunk> leafRows = new ArrayList<>(total);

            for (int start = 0; start < total; start += batchSize)
            {
                int end = Math.min(total, start + batchSize);
                List<ChunkDraft> batch = leafDrafts.subList(start, end);
                List<String> texts = batch.stream().map(ChunkDraft::getContent).toList();
                List<float[]> embeds = embeddingModel.embed(texts);
                if (embeds == null || embeds.size() != batch.size())
                {
                    throw new ServiceException("嵌入返回数量与输入不一致: expect="
                        + batch.size() + " got=" + (embeds == null ? 0 : embeds.size()));
                }
                for (int i = 0; i < batch.size(); i++)
                {
                    ChunkDraft draft = batch.get(i);
                    float[] emb = embeds.get(i);
                    Long parentId = leafIndexToParentId.get(draft.getChunkIndex());
                    leafRows.add(toChunkRow(doc, kb, draft,
                        emb != null ? emb.length : 0, parentId));
                    embByIndex.put(draft.getChunkIndex(), emb);
                }
                int done = end;
                int embedPct = (int) ((done * 1.0 / total) * KbConstants.PROGRESS_EMBED);
                int progress = KbConstants.PROGRESS_PARSE + KbConstants.PROGRESS_CHUNK + embedPct;
                updateProgress(doc, KbDocStatus.EMBEDDING,
                    "EMBEDDING_BATCH_" + done + "/" + total, Math.min(99, progress));
            }

            if (!leafRows.isEmpty())
            {
                batchInsert(leafRows);
                // 回查 LEAF chunkId 写向量（PARENT embedding_dim=0 不入向量）
                List<KbChunk> savedLeaves = kbChunkMapper.selectByDocIdAndLevel(
                    doc.getDocId(), ChunkLevels.LEAF);
                List<KbChunkVector> vectors = new ArrayList<>(savedLeaves.size());
                for (KbChunk c : savedLeaves)
                {
                    float[] emb = embByIndex.get(c.getChunkIndex());
                    if (emb == null)
                    {
                        continue;
                    }
                    KbChunkVector cv = new KbChunkVector();
                    cv.setChunkId(c.getChunkId());
                    cv.setKbId(c.getKbId());
                    cv.setDocId(c.getDocId());
                    cv.setChunkIndex(c.getChunkIndex());
                    cv.setContent(c.getContent());
                    cv.setHeadingPath(c.getHeadingPath());
                    cv.setDocName(doc.getDocName());
                    cv.setEmbedding(emb);
                    vectors.add(cv);
                }
                kbVectorStore.upsert(doc.getKbId(), vectors);
            }

            doc.setChunkCount(total);
            doc.setParseStatus(KbDocStatus.COMPLETED.name());
            doc.setParseStep("COMPLETED");
            doc.setProgress(100);
            doc.setErrorType(null);
            doc.setErrorStage(null);
            doc.setErrorMsg(null);
            kbDocumentMapper.updateParseProgress(doc);
            progressHub.publish(doc.getKbId(),
                KbIngestEventJson.completed(doc.getDocId(), doc.getKbId(), total));
            log.info("文档摄入完成 docId={} chunks={}", docId, total);
            // 图谱与向量解耦:COMPLETED 后若开启图谱则异步入队,失败不影响检索
            if ("1".equals(kb.getGraphEnabled()) && StringUtils.isNotEmpty(kb.getExtractModelCode()))
            {
                kbGraphExecutor.submit(docId);
            }
        }
        catch (Exception e)
        {
            log.error("文档摄入失败 docId={}", docId, e);
            String stage = guessStage(doc.getParseStatus());
            fail(doc, "ingest_error", stage, fullMessage(e));
        }
    }

    private void updateProgress(KbDocument doc, KbDocStatus status, String step, int progress)
    {
        doc.setParseStatus(status.name());
        doc.setParseStep(step);
        doc.setProgress(progress);
        doc.setErrorType(null);
        doc.setErrorStage(null);
        doc.setErrorMsg(null);
        kbDocumentMapper.updateParseProgress(doc);
        progressHub.publish(doc.getKbId(),
            KbIngestEventJson.progress(doc.getDocId(), doc.getKbId(), status.name(), step, progress));
    }

    private void fail(KbDocument doc, String errorType, String stage, String msg)
    {
        doc.setParseStatus(KbDocStatus.FAILED.name());
        doc.setParseStep("FAILED");
        doc.setErrorType(errorType);
        doc.setErrorStage(stage);
        doc.setErrorMsg(msg);
        kbDocumentMapper.updateParseProgress(doc);
        progressHub.publish(doc.getKbId(),
            KbIngestEventJson.failed(doc.getDocId(), doc.getKbId(), errorType, stage, msg));
    }

    private File resolveFile(String filePath)
    {
        if (StringUtils.isEmpty(filePath))
        {
            return null;
        }
        try
        {
            String relative = StringUtils.substringAfter(filePath, Constants.RESOURCE_PREFIX);
            if (StringUtils.isEmpty(relative))
            {
                relative = filePath;
            }
            return FileUploadUtils.getAbsoluteFile(RuoYiConfig.getProfile(), relative);
        }
        catch (Exception e)
        {
            log.warn("解析文件路径失败: {}", filePath, e);
            return null;
        }
    }

    private static String guessStage(String parseStatus)
    {
        if (parseStatus == null)
        {
            return "parse";
        }
        return switch (parseStatus)
        {
            case "CHUNKING" -> "chunk";
            case "EMBEDDING" -> "embed";
            default -> "parse";
        };
    }

    private static String fullMessage(Throwable e)
    {
        StringBuilder sb = new StringBuilder();
        Throwable cur = e;
        while (cur != null)
        {
            if (sb.length() > 0)
            {
                sb.append(" | caused by: ");
            }
            sb.append(cur.getClass().getSimpleName()).append(": ").append(cur.getMessage());
            cur = cur.getCause();
        }
        return sb.toString();
    }

    private static KbChunk toChunkRow(KbDocument doc, KbKnowledge kb, ChunkDraft draft,
                                      int embeddingDim, Long parentChunkId)
    {
        KbChunk chunk = new KbChunk();
        chunk.setKbId(doc.getKbId());
        chunk.setDocId(doc.getDocId());
        chunk.setChunkIndex(draft.getChunkIndex());
        chunk.setContent(draft.getContent());
        chunk.setHeadingPath(draft.getHeadingPath());
        chunk.setBlockType(draft.getBlockType());
        chunk.setTokenCount(draft.getTokenCount());
        chunk.setEmbeddingDim(embeddingDim);
        chunk.setChunkerStrategy(draft.getChunkerStrategy());
        chunk.setChunkParamsHash(draft.getChunkParamsHash());
        chunk.setEmbeddingModel(kb.getEmbeddingModelCode());
        chunk.setSourcePageFrom(draft.getSourcePageFrom());
        chunk.setSourcePageTo(draft.getSourcePageTo());
        chunk.setSourceLabel(draft.getSourceLabel());
        chunk.setChunkLevel(draft.getChunkLevel() != null ? draft.getChunkLevel() : ChunkLevels.LEAF);
        chunk.setParentChunkId(parentChunkId);
        return chunk;
    }

    private void batchInsert(List<KbChunk> rows)
    {
        if (rows == null || rows.isEmpty())
        {
            return;
        }
        int insertBatch = 50;
        for (int i = 0; i < rows.size(); i += insertBatch)
        {
            int end = Math.min(rows.size(), i + insertBatch);
            kbChunkMapper.batchInsertKbChunk(rows.subList(i, end));
        }
    }
}
