package com.ruoyi.system.kb.graph.community;

import java.util.Date;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.ruoyi.common.datasource.DataSourceScope;
import com.ruoyi.common.enums.DataSourceType;
import com.ruoyi.system.domain.KbGraphIndex;
import com.ruoyi.system.kb.graph.GraphEntity;
import com.ruoyi.system.kb.graph.GraphRelation;
import com.ruoyi.system.kb.graph.KbGraphStore;
import com.ruoyi.system.mapper.KbGraphCommunityMapper;

/**
 * 社区检测编排：探测 GDS → 运行算法 → 落库；失败不污染向量检索。
 */
@Service
public class KbCommunityService
{
    private static final Logger log = LoggerFactory.getLogger(KbCommunityService.class);
    public static final String COMMUNITY_VERSION = "v1";

    @Autowired
    private KbCommunityDetector communityDetector;
    @Autowired
    private KbGraphCommunityMapper communityMapper;
    @Autowired
    private KbGraphStore kbGraphStore;
    @Autowired(required = false)
    private KbCommunityReportService communityReportService;

    /** 无 GDS 时是否使用内存连通分量近似（默认 true，便于开发/测试） */
    @Value("${ai.kb.graph.community.fallback-cc:true}")
    private boolean fallbackCc;

    @Value("${ai.kb.graph.community.random-seed:42}")
    private long randomSeed;

    @Value("${ai.kb.graph.community.enabled:false}")
    private boolean communityEnabled;

    @Value("${ai.kb.graph.community.report-enabled:true}")
    private boolean reportEnabled;

    public void markDirty(Long kbId, String graphVersion)
    {
        if (kbId == null || !communityEnabled)
        {
            return;
        }
        DataSourceScope.runOn(DataSourceType.SLAVE,
            () -> communityMapper.markDirty(kbId, graphVersion));
    }

    public CommunityDetectionResult rebuild(Long kbId, String graphVersion)
    {
        if (kbId == null)
        {
            CommunityDetectionResult r = new CommunityDetectionResult();
            r.setAvailable(false);
            r.setSkipReason("kbId null");
            return r;
        }
        if (!communityEnabled)
        {
            CommunityDetectionResult r = new CommunityDetectionResult();
            r.setAvailable(false);
            r.setSkipReason("community.enabled=false");
            r.setGraphVersion(graphVersion);
            return r;
        }

        String gv = graphVersion != null ? graphVersion : ("gv-" + System.currentTimeMillis());
        KbGraphIndex idx = loadOrCreateIndex(kbId);
        // 推进版本：保留 previous 以便回滚（KB-GR-13）
        if (idx.getGraphVersion() != null && !gv.equals(idx.getGraphVersion()))
        {
            idx.setPreviousGraphVersion(idx.getGraphVersion());
        }
        idx.setStatus("RUNNING");
        idx.setStep("DETECT");
        idx.setStartedAt(new Date());
        idx.setFinishedAt(null);
        idx.setErrorType(null);
        idx.setErrorMsg(null);
        idx.setGraphVersion(gv);
        idx.setCommunityVersion(COMMUNITY_VERSION);
        saveIndex(idx);

        CommunityDetectionResult result;
        try
        {
            GdsCapability cap = communityDetector.probe();
            idx.setGdsAvailable(cap.isAvailable() ? "1" : "0");
            idx.setGdsVersion(cap.getVersion());

            if (cap.isAvailable() && !(communityDetector instanceof NoOpCommunityDetector))
            {
                result = communityDetector.detect(kbId, gv);
            }
            else if (fallbackCc && kbGraphStore.isAvailable())
            {
                List<GraphEntity> entities = kbGraphStore.listEntities(kbId, 50_000);
                List<GraphRelation> relations = kbGraphStore.listRelations(kbId, 200_000);
                ConnectedComponentsCommunityDetector cc =
                    new ConnectedComponentsCommunityDetector(randomSeed);
                result = cc.detect(kbId, gv, entities, relations);
                result.setSkipReason(cap.isAvailable() ? null :
                    "gds_unavailable_fallback_cc:" + cap.getReason());
            }
            else
            {
                result = new CommunityDetectionResult();
                result.setAvailable(false);
                result.setSkipReason(cap.getReason() != null ? cap.getReason() : "detector unavailable");
                result.setGraphVersion(gv);
            }

            if (!result.isAvailable() && result.getMemberships().isEmpty())
            {
                idx.setStatus("SKIPPED");
                idx.setStep("SKIPPED");
                idx.setErrorType("community_skipped");
                idx.setErrorMsg(result.getSkipReason());
                idx.setFinishedAt(new Date());
                saveIndex(idx);
                return result;
            }

            // 替换该 kb 全部社区结果（第一版全量重建）
            DataSourceScope.runOn(DataSourceType.SLAVE, () -> {
                communityMapper.deleteAllEntityCommunitiesByKb(kbId);
                communityMapper.deleteAllCommunitiesByKb(kbId);
                if (result.getCommunities() != null && !result.getCommunities().isEmpty())
                {
                    batchInsert(result.getCommunities(), 200,
                        list -> communityMapper.batchInsertCommunities(list));
                }
                if (result.getMemberships() != null && !result.getMemberships().isEmpty())
                {
                    batchInsert(result.getMemberships(), 500,
                        list -> communityMapper.batchInsertEntityCommunities(list));
                }
            });

            // 社区检测落库后：自底向上报告（失败不回滚社区）
            int reportOk = 0;
            if (reportEnabled && communityReportService != null)
            {
                idx.setStep("REPORT");
                saveIndex(idx);
                try
                {
                    reportOk = communityReportService.generateReports(kbId, gv);
                    idx.setReportVersion(KbCommunityReportService.REPORT_VERSION);
                }
                catch (Exception re)
                {
                    log.warn("社区报告阶段失败 kbId={}: {}", kbId, re.getMessage());
                    idx.setErrorMsg((result.getSkipReason() != null ? result.getSkipReason() + "; " : "")
                        + "report_error:" + re.getMessage());
                }
            }

            idx.setStatus("READY");
            idx.setStep("COMPLETED");
            idx.setEntityCount(result.getEntityCount());
            idx.setRelationCount(result.getRelationCount());
            idx.setCommunityCount(result.getCommunities() != null ? result.getCommunities().size() : 0);
            idx.setLevelCount(result.getLevelCount());
            idx.setFinishedAt(new Date());
            idx.setDirtyAt(null);
            idx.setErrorType(null);
            if (idx.getErrorMsg() == null)
            {
                idx.setErrorMsg(result.getSkipReason());
            }
            saveIndex(idx);
            log.info("社区重建完成 kbId={} algorithm={} levels={} communities={} entities={} reports={}",
                kbId, result.getAlgorithm(), result.getLevelCount(),
                idx.getCommunityCount(), result.getEntityCount(), reportOk);
            return result;
        }
        catch (Exception e)
        {
            log.warn("社区重建失败 kbId={}: {}", kbId, e.getMessage());
            idx.setStatus("FAILED");
            idx.setStep("FAILED");
            idx.setErrorType("community_error");
            idx.setErrorMsg(e.getMessage());
            idx.setFinishedAt(new Date());
            saveIndex(idx);
            CommunityDetectionResult r = new CommunityDetectionResult();
            r.setAvailable(false);
            r.setSkipReason(e.getMessage());
            r.setGraphVersion(gv);
            return r;
        }
    }

    private KbGraphIndex loadOrCreateIndex(Long kbId)
    {
        KbGraphIndex idx = DataSourceScope.runOn(DataSourceType.SLAVE,
            () -> communityMapper.selectIndex(kbId));
        if (idx == null)
        {
            idx = new KbGraphIndex();
            idx.setKbId(kbId);
            idx.setStatus("IDLE");
        }
        return idx;
    }

    private void saveIndex(KbGraphIndex idx)
    {
        DataSourceScope.runOn(DataSourceType.SLAVE, () -> communityMapper.upsertIndex(idx));
    }

    private interface BatchOp<T>
    {
        void accept(List<T> list);
    }

    private static <T> void batchInsert(List<T> all, int batch, BatchOp<T> op)
    {
        for (int i = 0; i < all.size(); i += batch)
        {
            int end = Math.min(all.size(), i + batch);
            op.accept(all.subList(i, end));
        }
    }
}
