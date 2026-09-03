package com.ruoyi.system.kb.graph;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.ruoyi.common.datasource.DataSourceScope;
import com.ruoyi.common.enums.DataSourceType;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.domain.KbChunk;
import com.ruoyi.system.domain.KbDocGraph;
import com.ruoyi.system.domain.KbDocument;
import com.ruoyi.system.domain.KbGraphRun;
import com.ruoyi.system.domain.KbGraphTextUnit;
import com.ruoyi.system.domain.KbKnowledge;
import com.ruoyi.system.kb.KbConstants;
import com.ruoyi.system.kb.chunker.ChunkLevels;
import com.ruoyi.system.kb.graph.extract.ExtractResultParser.ExtractResult;
import com.ruoyi.system.kb.graph.extract.GraphExtractionProfile;
import com.ruoyi.system.kb.graph.extract.GraphExtractionProfiles;
import com.ruoyi.system.kb.graph.extract.KbEntityExtractor;
import com.ruoyi.system.kb.graph.merge.KbGraphMerger;
import com.ruoyi.system.kb.graph.merge.KbGraphMerger.MergeResult;
import com.ruoyi.system.kb.graph.provenance.GraphExtractOutcome;
import com.ruoyi.system.kb.graph.provenance.GraphGenerationSupport;
import com.ruoyi.system.kb.graph.provenance.GraphProvenanceModel;
import com.ruoyi.system.kb.graph.provenance.GraphRelationEvidence;
import com.ruoyi.system.kb.graph.provenance.GraphRunStatus;
import com.ruoyi.system.kb.graph.provenance.GraphTextUnit;
import com.ruoyi.system.kb.graph.provenance.GraphWriteBundle;
import com.ruoyi.system.kb.graph.provenance.GraphWriteRejectedException;
import com.ruoyi.system.kb.graph.community.KbCommunityExecutor;
import com.ruoyi.system.kb.graph.textunit.GraphTextUnitBuilder;
import com.ruoyi.system.kb.ingest.KbIngestEventJson;
import com.ruoyi.system.kb.ingest.KbIngestProgressHub;
import com.ruoyi.system.mapper.KbChunkMapper;
import com.ruoyi.system.mapper.KbDocGraphMapper;
import com.ruoyi.system.mapper.KbDocumentMapper;
import com.ruoyi.system.mapper.KbGraphRunMapper;
import com.ruoyi.system.mapper.KbGraphTextUnitMapper;
import com.ruoyi.system.mapper.KbKnowledgeMapper;

/**
 * 图谱抽取编排: LEAF → Graph TextUnit → 并发抽取 → 合并 → 证据图提交。
 * <p>TextUnit 参数独立于 embedding；调整 unit size 只重建图。
 */
@Component
public class KbGraphPipeline
{
    private static final Logger log = LoggerFactory.getLogger(KbGraphPipeline.class);
    private static final AtomicLong RUN_ID_SEQ = new AtomicLong(System.currentTimeMillis());

    private final ConcurrentHashMap<Long, Object> docLocks = new ConcurrentHashMap<>();

    @Autowired
    private KbDocumentMapper kbDocumentMapper;
    @Autowired
    private KbKnowledgeMapper kbKnowledgeMapper;
    @Autowired
    private KbChunkMapper kbChunkMapper;
    @Autowired
    private KbDocGraphMapper kbDocGraphMapper;
    @Autowired
    private KbGraphRunMapper kbGraphRunMapper;
    @Autowired
    private KbGraphTextUnitMapper kbGraphTextUnitMapper;
    @Autowired
    private KbEntityExtractor entityExtractor;
    @Autowired
    private GraphExtractionProfiles graphExtractionProfiles;
    @Autowired
    private KbGraphMerger graphMerger;
    @Autowired
    private KbGraphStore kbGraphStore;
    @Autowired
    private KbIngestProgressHub progressHub;
    @Autowired(required = false)
    private KbCommunityExecutor kbCommunityExecutor;

    @Value("${ai.kb.graph.extract-concurrency:4}")
    private int extractConcurrency;

    /** Graph TextUnit 目标 token，与 LEAF chunk size 解耦 */
    @Value("${ai.kb.graph.graph-unit-size:350}")
    private int graphUnitSize;

    @Value("${ai.kb.graph.graph-unit-max:500}")
    private int graphUnitMax;

    public void process(Long docId)
    {
        if (docId == null)
        {
            return;
        }
        Object lock = docLocks.computeIfAbsent(docId, id -> new Object());
        synchronized (lock)
        {
            try
            {
                doProcess(docId);
            }
            catch (Exception e)
            {
                log.error("图谱抽取失败 docId={}", docId, e);
                fail(docId, null, "graph_error", e.getMessage());
            }
            finally
            {
                docLocks.remove(docId, lock);
            }
        }
    }

    private void doProcess(Long docId)
    {
        if (!kbGraphStore.isAvailable())
        {
            fail(docId, null, "neo4j_unavailable", "Neo4j 未启用或不可用");
            return;
        }

        KbDocument doc = DataSourceScope.runOn(DataSourceType.SLAVE,
            () -> kbDocumentMapper.selectKbDocumentById(docId));
        if (doc == null)
        {
            log.warn("图谱抽取跳过:文档不存在 docId={}", docId);
            return;
        }
        KbKnowledge kb = DataSourceScope.runOn(DataSourceType.SLAVE,
            () -> kbKnowledgeMapper.selectKbKnowledgeById(doc.getKbId()));
        if (kb == null || !"1".equals(kb.getGraphEnabled()))
        {
            log.info("图谱抽取跳过:知识库未启用图谱 docId={} kbId={}", docId, doc.getKbId());
            return;
        }
        if (StringUtils.isEmpty(kb.getExtractModelCode()))
        {
            fail(docId, null, "extract_model_missing", "知识库未配置抽取模型 extract_model_code");
            return;
        }

        // 向量 LEAF → Graph TextUnit（参数独立，不重算 embedding）
        List<KbChunk> leaves = DataSourceScope.runOn(DataSourceType.SLAVE,
            () -> kbChunkMapper.selectByDocIdAndLevel(docId, ChunkLevels.LEAF));
        if (leaves == null || leaves.isEmpty())
        {
            fail(docId, null, "no_chunks", "文档无分块,请先完成向量摄入");
            return;
        }
        List<KbGraphTextUnit> textUnits = GraphTextUnitBuilder.build(leaves, graphUnitSize, graphUnitMax);
        if (textUnits.isEmpty())
        {
            fail(docId, null, "no_text_units", "无法从 LEAF 派生 TextUnit");
            return;
        }
        GraphExtractionProfile extractProfile = graphExtractionProfiles.forKnowledge(kb);

        // 声明 generation / run（抢占 active，旧任务提交时将被拒绝）
        KbDocGraph prev = DataSourceScope.runOn(DataSourceType.SLAVE,
            () -> kbDocGraphMapper.selectByDocId(docId));
        long generation = GraphGenerationSupport.nextGeneration(prev != null ? prev.getGeneration() : null);
        long runId = RUN_ID_SEQ.incrementAndGet();
        // 列宽 varchar(64)：unit/chunk 参数指纹统一再 hash，禁止拼接后直接落库
        String unitParamsRaw = GraphTextUnitBuilder.paramsHash(graphUnitSize, graphUnitMax)
            + "|pf:" + extractProfile.fingerprint();
        String unitParamsHash = fitVarchar(sha256Hex(unitParamsRaw), 64);
        KbGraphRun run = newRun(runId, doc, generation, kb.getExtractModelCode(), leaves, unitParamsHash);
        DataSourceScope.runOn(DataSourceType.SLAVE, () -> kbGraphRunMapper.insert(run));

        // 落库 TextUnit + 映射（先删旧，再写新；不触碰 kb_chunk / 向量）
        try
        {
            persistTextUnits(doc, textUnits, generation, runId, unitParamsHash);
        }
        catch (Exception e)
        {
            fail(docId, run, "text_unit_persist", "TextUnit 落库失败: " + e.getMessage());
            return;
        }

        KbDocGraph state = newState(doc, kb.getExtractModelCode(), textUnits.size(), runId, generation);
        // 抢占 active
        if (prev != null && prev.getActiveRunId() != null
            && !prev.getActiveRunId().equals(runId))
        {
            supersedeRun(prev.getActiveRunId());
        }
        saveState(state);
        markRun(run, GraphRunStatus.RUNNING, "EXTRACT", null, null);
        publishGraph(doc, state);

        List<ExtractResult> results = new ArrayList<>();
        AtomicInteger done = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        int pool = Math.max(1, extractConcurrency);
        int unitTotal = textUnits.size();
        ExecutorService exec = Executors.newFixedThreadPool(pool);
        try
        {
            List<Future<ExtractResult>> futures = new ArrayList<>();
            for (KbGraphTextUnit unit : textUnits)
            {
                futures.add(exec.submit(() -> {
                    try
                    {
                        return entityExtractor.extract(docId, kb.getExtractModelCode(),
                            unit.getHeadingPath(), unit.getContent(),
                            unit.getLeafChunkIds(), doc.getDocName(), extractProfile);
                    }
                    catch (Exception ex)
                    {
                        failCount.incrementAndGet();
                        log.warn("TextUnit 抽取异常 textUnitId={}: {}", unit.getTextUnitId(), ex.getMessage());
                        ExtractResult failed = new ExtractResult();
                        failed.setOutcome(GraphExtractOutcome.LLM_FAILED);
                        failed.setErrorDetail(ex.getMessage());
                        return failed;
                    }
                    finally
                    {
                        int n = done.incrementAndGet();
                        state.setChunkDone(n);
                        state.setGraphStatus(KbGraphStatus.EXTRACTING.name());
                        state.setGraphStep("EXTRACT_" + n + "/" + unitTotal);
                        state.setProgress(Math.min(80, (int) (n * 80.0 / unitTotal)));
                        if (n == unitTotal || n % 3 == 0)
                        {
                            saveState(state);
                            publishGraph(doc, state);
                        }
                    }
                }));
            }
            for (Future<ExtractResult> f : futures)
            {
                try
                {
                    results.add(f.get(180, TimeUnit.SECONDS));
                }
                catch (Exception e)
                {
                    failCount.incrementAndGet();
                    results.add(new ExtractResult());
                }
            }
        }
        finally
        {
            exec.shutdownNow();
        }

        // 提交前确认仍是 active（并发新任务已抢占则拒绝）
        if (!stillActive(docId, runId, generation))
        {
            markRun(run, GraphRunStatus.SUPERSEDED, "SUPERSEDED", null,
                "被更新的 generation 抢占,放弃写图");
            log.info("图谱 run 被抢占 docId={} runId={} gen={}", docId, runId, generation);
            return;
        }

        state.setGraphStatus(KbGraphStatus.MERGING.name());
        state.setGraphStep("MERGING");
        state.setProgress(85);
        saveState(state);
        publishGraph(doc, state);
        markRun(run, GraphRunStatus.RUNNING, "MERGING", null, null);

        MergeResult merged = graphMerger.merge(docId, doc.getKbId(), kb.getExtractModelCode(), results);
        mergeWithExisting(doc.getKbId(), merged);

        GraphWriteBundle bundle = buildBundle(doc, textUnits, merged, generation, runId);

        // 证据替换：清理失败不得继续叠加
        try
        {
            if (!stillActive(docId, runId, generation))
            {
                throw new GraphWriteRejectedException("active run 已变更,拒绝写图");
            }
            kbGraphStore.replaceDocumentEvidence(bundle);
        }
        catch (GraphWriteRejectedException e)
        {
            markRun(run, GraphRunStatus.SUPERSEDED, "SUPERSEDED", null, e.getMessage());
            log.info("图谱写被拒绝 docId={} runId={}: {}", docId, runId, e.getMessage());
            return;
        }
        catch (Exception e)
        {
            fail(docId, run, "neo4j_write", "写图/清理失败: " + e.getMessage());
            return;
        }

        if (!stillActive(docId, runId, generation))
        {
            // 极窄窗口：已写入但 active 被抢 — 记录 superseded，由新 run 覆盖
            markRun(run, GraphRunStatus.SUPERSEDED, "SUPERSEDED", GraphExtractOutcome.SUCCESS,
                "写图后 active 已变更");
            return;
        }

        String outcome = resolveOutcome(results, merged, failCount.get(), unitTotal);
        state.setGraphStatus(KbGraphStatus.COMPLETED.name());
        state.setGraphStep("COMPLETED");
        state.setProgress(100);
        state.setEntityCount(merged.getEntities().size());
        state.setRelationCount(merged.getRelations().size());
        state.setActiveRunId(runId);
        state.setGeneration(generation);
        state.setGraphVersion(graphVersionFingerprint(run, unitParamsHash));
        state.setFinishedAt(new Date());
        if (failCount.get() > 0)
        {
            state.setErrorType("partial_unit_fail");
            state.setErrorMsg("部分 TextUnit 抽取失败: " + failCount.get() + "/" + unitTotal);
        }
        else
        {
            state.setErrorType(null);
            state.setErrorMsg(null);
        }
        saveState(state);
        run.setEntityCount(merged.getEntities().size());
        run.setRelationCount(merged.getRelations().size());
        run.setEvidenceCount(bundle.getEvidences().size());
        run.setExtractOutcome(outcome);
        run.setFinishedAt(new Date());
        markRun(run, GraphRunStatus.SUCCESS, "COMPLETED", outcome, state.getErrorMsg());
        publishGraph(doc, state);
        log.info("图谱抽取完成 docId={} gen={} runId={} textUnits={} entities={} relations={} evidences={} fails={}",
            docId, generation, runId, unitTotal, merged.getEntities().size(), merged.getRelations().size(),
            bundle.getEvidences().size(), failCount.get());
        // 图变更后防抖触发社区重建（默认关闭；失败不影响向量）
        if (kbCommunityExecutor != null)
        {
            try
            {
                kbCommunityExecutor.scheduleRebuild(doc.getKbId(), state.getGraphVersion());
            }
            catch (Exception e)
            {
                log.debug("提交社区重建失败(忽略): {}", e.getMessage());
            }
        }
    }

    private void persistTextUnits(KbDocument doc, List<KbGraphTextUnit> units,
        long generation, long runId, String unitParamsHash)
    {
        DataSourceScope.runOn(DataSourceType.SLAVE, () -> {
            kbGraphTextUnitMapper.deleteMappingsByDocId(doc.getDocId());
            kbGraphTextUnitMapper.deleteByDocId(doc.getDocId());
            for (KbGraphTextUnit u : units)
            {
                long id = RUN_ID_SEQ.incrementAndGet();
                u.setTextUnitId(id);
                u.setKbId(doc.getKbId());
                u.setDocId(doc.getDocId());
                u.setGeneration(generation);
                u.setRunId(runId);
                u.setUnitParamsHash(unitParamsHash);
                u.setGraphUnitVersion(GraphTextUnitBuilder.GRAPH_UNIT_VERSION);
                u.setParserVersion(KbConstants.PARSER_VERSION);
                kbGraphTextUnitMapper.insert(u);
                if (u.getLeafChunkIds() != null && !u.getLeafChunkIds().isEmpty())
                {
                    kbGraphTextUnitMapper.batchInsertChunkMappings(u.getTextUnitId(), u.getLeafChunkIds());
                }
            }
        });
    }

    private GraphWriteBundle buildBundle(KbDocument doc, List<KbGraphTextUnit> textUnits,
        MergeResult merged, long generation, long runId)
    {
        GraphWriteBundle bundle = new GraphWriteBundle();
        bundle.setKbId(doc.getKbId());
        bundle.setDocId(doc.getDocId());
        bundle.setGeneration(generation);
        bundle.setRunId(runId);

        List<GraphTextUnit> units = new ArrayList<>();
        for (KbGraphTextUnit u : textUnits)
        {
            if (u == null)
            {
                continue;
            }
            // Neo4j TextUnit：用 textUnitId 作主键；chunkId 取首个 LEAF 便于兼容旧字段
            GraphTextUnit tu = new GraphTextUnit();
            tu.setKbId(doc.getKbId());
            tu.setDocId(doc.getDocId());
            tu.setChunkId(u.getLeafChunkIds() != null && !u.getLeafChunkIds().isEmpty()
                ? u.getLeafChunkIds().get(0) : u.getTextUnitId());
            tu.setContent(u.getContent());
            tu.setContentHash(u.getContentHash());
            tu.setHeadingPath(u.getHeadingPath());
            tu.setSourceLabel(u.getSourceLabel());
            tu.setSourcePageFrom(u.getSourcePageFrom());
            tu.setSourcePageTo(u.getSourcePageTo());
            tu.setGeneration(generation);
            tu.setRunId(runId);
            units.add(tu);
        }
        bundle.setTextUnits(units);
        bundle.setEntities(merged.getEntities());
        bundle.setRelations(merged.getRelations());

        List<GraphRelationEvidence> evidences = new ArrayList<>();
        for (GraphRelation r : merged.getRelations())
        {
            if (r == null || StringUtils.isEmpty(r.getSourceName()) || StringUtils.isEmpty(r.getTargetName()))
            {
                continue;
            }
            List<Long> srcIds = r.getSourceIds() != null ? r.getSourceIds() : List.of();
            if (srcIds.isEmpty())
            {
                GraphRelationEvidence ev = baseEvidence(doc, r, generation, runId, null);
                evidences.add(ev);
            }
            else
            {
                for (Long chunkId : srcIds)
                {
                    evidences.add(baseEvidence(doc, r, generation, runId, chunkId));
                }
            }
        }
        bundle.setEvidences(evidences);
        return bundle;
    }

    private static GraphRelationEvidence baseEvidence(KbDocument doc, GraphRelation r,
        long generation, long runId, Long chunkId)
    {
        GraphRelationEvidence ev = new GraphRelationEvidence();
        ev.setKbId(doc.getKbId());
        ev.setDocId(doc.getDocId());
        ev.setGeneration(generation);
        ev.setRunId(runId);
        // 优先使用规范化 entityKey，避免 name 唯一约束冲突
        if (StringUtils.isNotEmpty(r.getSourceEntityKey()))
        {
            ev.setSourceEntityKey(r.getSourceEntityKey().trim());
        }
        else
        {
            ev.setSourceEntityKey(r.getSourceName().trim());
        }
        if (StringUtils.isNotEmpty(r.getTargetEntityKey()))
        {
            ev.setTargetEntityKey(r.getTargetEntityKey().trim());
        }
        else
        {
            ev.setTargetEntityKey(r.getTargetName().trim());
        }
        ev.setPredicate(r.getKeywords() != null ? r.getKeywords() : "");
        ev.setDescription(r.getDescription());
        ev.setTextUnitChunkId(chunkId);
        return ev;
    }

    private boolean stillActive(Long docId, Long runId, Long generation)
    {
        KbDocGraph cur = DataSourceScope.runOn(DataSourceType.SLAVE,
            () -> kbDocGraphMapper.selectByDocId(docId));
        if (cur == null)
        {
            return false;
        }
        return GraphGenerationSupport.canCommit(cur.getActiveRunId(), cur.getGeneration(),
            runId, generation);
    }

    private void supersedeRun(Long runId)
    {
        if (runId == null)
        {
            return;
        }
        try
        {
            KbGraphRun old = DataSourceScope.runOn(DataSourceType.SLAVE,
                () -> kbGraphRunMapper.selectByRunId(runId));
            if (old != null && GraphRunStatus.RUNNING.equals(old.getStatus()))
            {
                old.setStatus(GraphRunStatus.SUPERSEDED);
                old.setStep("SUPERSEDED");
                old.setFinishedAt(new Date());
                DataSourceScope.runOn(DataSourceType.SLAVE, () -> kbGraphRunMapper.update(old));
            }
        }
        catch (Exception e)
        {
            log.debug("supersedeRun ignore: {}", e.getMessage());
        }
    }

    private void markRun(KbGraphRun run, String status, String step, String outcome, String err)
    {
        if (run == null)
        {
            return;
        }
        run.setStatus(status);
        run.setStep(step);
        if (outcome != null)
        {
            run.setExtractOutcome(outcome);
        }
        if (err != null)
        {
            run.setErrorMsg(err);
        }
        if (GraphRunStatus.SUCCESS.equals(status) || GraphRunStatus.FAILED.equals(status)
            || GraphRunStatus.SUPERSEDED.equals(status))
        {
            run.setFinishedAt(new Date());
        }
        DataSourceScope.runOn(DataSourceType.SLAVE, () -> kbGraphRunMapper.update(run));
    }

    private KbGraphRun newRun(long runId, KbDocument doc, long generation, String model,
        List<KbChunk> leaves, String unitParamsHash)
    {
        KbGraphRun run = new KbGraphRun();
        run.setRunId(runId);
        run.setKbId(doc.getKbId());
        run.setDocId(doc.getDocId());
        run.setGeneration(generation);
        run.setSourceContentHash(fitVarchar(doc.getContentHash(), 64));
        run.setParserVersion(fitVarchar(KbConstants.PARSER_VERSION, 20));
        run.setExtractorVersion(fitVarchar(
            GraphProvenanceModel.PROVENANCE_VERSION + "+" + GraphTextUnitBuilder.GRAPH_UNIT_VERSION, 40));
        run.setPromptVersion("default");
        run.setModelCode(fitVarchar(model, 100));
        run.setStatus(GraphRunStatus.PENDING);
        run.setStep("CLAIM");
        run.setEntityCount(0);
        run.setRelationCount(0);
        run.setEvidenceCount(0);
        run.setStartedAt(new Date());
        run.setCreateTime(new Date());
        // chunk_params_hash 列 varchar(64)：leaf + unit 复合指纹再 hash
        String leafHash = (leaves != null && !leaves.isEmpty())
            ? leaves.get(0).getChunkParamsHash() : null;
        String composite = (leafHash != null ? leafHash : "") + "|tu:" + unitParamsHash;
        run.setChunkParamsHash(fitVarchar(sha256Hex(composite), 64));
        return run;
    }

    /** 截断到列宽；优先完整写入，超长时用 sha256 截断保持可比较 */
    static String fitVarchar(String value, int maxLen)
    {
        if (value == null)
        {
            return null;
        }
        if (maxLen <= 0 || value.length() <= maxLen)
        {
            return value;
        }
        String h = sha256Hex(value);
        return h.length() <= maxLen ? h : h.substring(0, maxLen);
    }

    private static String resolveOutcome(List<ExtractResult> results, MergeResult merged,
        int failCount, int total)
    {
        int llmFail = 0;
        int parseFail = 0;
        int validEmpty = 0;
        int success = 0;
        if (results != null)
        {
            for (ExtractResult r : results)
            {
                if (r == null || r.getOutcome() == null)
                {
                    continue;
                }
                switch (r.getOutcome())
                {
                    case GraphExtractOutcome.LLM_FAILED -> llmFail++;
                    case GraphExtractOutcome.PARSE_FAILED, GraphExtractOutcome.VALIDATION_FAILED -> parseFail++;
                    case GraphExtractOutcome.VALID_EMPTY -> validEmpty++;
                    case GraphExtractOutcome.SUCCESS -> success++;
                    default -> {
                    }
                }
            }
        }
        if (total > 0 && llmFail + failCount >= total)
        {
            return GraphExtractOutcome.LLM_FAILED;
        }
        if (total > 0 && parseFail >= total)
        {
            return GraphExtractOutcome.PARSE_FAILED;
        }
        if (success > 0 || (merged.getEntities() != null && !merged.getEntities().isEmpty())
            || (merged.getRelations() != null && !merged.getRelations().isEmpty()))
        {
            return GraphExtractOutcome.SUCCESS;
        }
        if (validEmpty > 0 || (llmFail == 0 && parseFail == 0))
        {
            return GraphExtractOutcome.VALID_EMPTY;
        }
        return GraphExtractOutcome.LLM_FAILED;
    }

    private static String graphVersionFingerprint(KbGraphRun run, String unitParamsHash)
    {
        // graph_version 列 varchar(64)，拼接后必须再 hash
        String raw = String.join("|",
            nullToEmpty(run.getParserVersion()),
            nullToEmpty(run.getChunkParamsHash()),
            nullToEmpty(unitParamsHash),
            nullToEmpty(run.getExtractorVersion()),
            nullToEmpty(run.getPromptVersion()),
            nullToEmpty(run.getModelCode()));
        return fitVarchar(sha256Hex(raw), 64);
    }

    private static String nullToEmpty(String s)
    {
        return s == null ? "" : s;
    }

    /** 全量 64 hex，适配 varchar(64) 指纹列 */
    private static String sha256Hex(String text)
    {
        if (text == null)
        {
            return "";
        }
        try
        {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(dig.length * 2);
            for (byte b : dig)
            {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        }
        catch (Exception e)
        {
            return Integer.toHexString(text.hashCode());
        }
    }

    /**
     * 跨文档:读已有实体,合并 sourceIds/描述后再写,避免覆盖掉另一份文档的引用。
     */
    private void mergeWithExisting(Long kbId, MergeResult merged)
    {
        if (merged.getEntities().isEmpty())
        {
            return;
        }
        List<String> names = merged.getEntities().stream().map(GraphEntity::getName).toList();
        List<GraphEntity> existing;
        try
        {
            existing = kbGraphStore.findEntitiesByNames(kbId, names);
        }
        catch (Exception e)
        {
            log.warn("读取已有实体失败,将覆盖写: {}", e.getMessage());
            return;
        }
        if (existing == null || existing.isEmpty())
        {
            return;
        }
        var byName = new java.util.HashMap<String, GraphEntity>();
        for (GraphEntity e : existing)
        {
            byName.put(e.getName(), e);
        }
        for (GraphEntity e : merged.getEntities())
        {
            GraphEntity old = byName.get(e.getName());
            if (old == null)
            {
                continue;
            }
            e.setSourceIds(GraphSourceIds.mergeLongs(old.getSourceIds(), e.getSourceIds(),
                KbGraphConstants.MAX_SOURCE_IDS));
            e.setFilePaths(GraphSourceIds.mergePaths(old.getFilePaths(), e.getFilePaths(),
                KbGraphConstants.MAX_FILE_PATHS));
            if (StringUtils.isNotEmpty(old.getDescription())
                && StringUtils.isNotEmpty(e.getDescription())
                && !old.getDescription().equals(e.getDescription()))
            {
                String combined = old.getDescription() + "；" + e.getDescription();
                if (combined.length() < 800)
                {
                    e.setDescription(combined);
                }
            }
            else if (StringUtils.isEmpty(e.getDescription()))
            {
                e.setDescription(old.getDescription());
            }
        }
    }

    private KbDocGraph newState(KbDocument doc, String extractModel, int chunkTotal,
        long runId, long generation)
    {
        KbDocGraph g = new KbDocGraph();
        g.setDocId(doc.getDocId());
        g.setKbId(doc.getKbId());
        g.setGraphStatus(KbGraphStatus.EXTRACTING.name());
        g.setGraphStep("START");
        g.setProgress(1);
        g.setChunkTotal(chunkTotal);
        g.setChunkDone(0);
        g.setEntityCount(0);
        g.setRelationCount(0);
        g.setExtractModel(extractModel);
        g.setStartedAt(new Date());
        g.setFinishedAt(null);
        g.setErrorType(null);
        g.setErrorMsg(null);
        g.setActiveRunId(runId);
        g.setGeneration(generation);
        return g;
    }

    private void saveState(KbDocGraph state)
    {
        DataSourceScope.runOn(DataSourceType.SLAVE, () -> kbDocGraphMapper.upsert(state));
    }

    private void fail(Long docId, KbGraphRun run, String errorType, String msg)
    {
        try
        {
            if (run != null)
            {
                run.setErrorType(errorType);
                run.setErrorMsg(msg);
                markRun(run, GraphRunStatus.FAILED, "FAILED",
                    GraphExtractOutcome.LLM_FAILED, msg);
            }
            KbDocument doc = DataSourceScope.runOn(DataSourceType.SLAVE,
                () -> kbDocumentMapper.selectKbDocumentById(docId));
            KbDocGraph g = DataSourceScope.runOn(DataSourceType.SLAVE,
                () -> kbDocGraphMapper.selectByDocId(docId));
            if (g == null)
            {
                g = new KbDocGraph();
                g.setDocId(docId);
                g.setKbId(doc != null ? doc.getKbId() : null);
                g.setChunkTotal(0);
                g.setChunkDone(0);
                g.setStartedAt(new Date());
            }
            g.setGraphStatus(KbGraphStatus.FAILED.name());
            g.setGraphStep("FAILED");
            g.setErrorType(errorType);
            g.setErrorMsg(msg);
            g.setFinishedAt(new Date());
            saveState(g);
            if (doc != null)
            {
                publishGraph(doc, g);
            }
        }
        catch (Exception e)
        {
            log.error("写入图谱失败状态出错 docId={}", docId, e);
        }
    }

    private void publishGraph(KbDocument doc, KbDocGraph state)
    {
        String json = KbIngestEventJson.progress(doc.getDocId(), doc.getKbId(),
            "GRAPH_" + state.getGraphStatus(), state.getGraphStep(),
            state.getProgress() != null ? state.getProgress() : 0);
        progressHub.publish(doc.getKbId(), json);
    }
}
