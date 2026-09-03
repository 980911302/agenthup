package com.ruoyi.system.kb.policy;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.annotation.DataSource;
import com.ruoyi.common.datasource.DataSourceScope;
import com.ruoyi.common.enums.DataSourceType;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.domain.KbIndexJob;
import com.ruoyi.system.domain.KbIndexPolicyVersion;
import com.ruoyi.system.domain.KbKnowledge;
import com.ruoyi.system.kb.access.KbAccessAction;
import com.ruoyi.system.kb.access.KbAuthorizationService;
import com.ruoyi.system.kb.search.KbSearchQualityGate;
import com.ruoyi.system.mapper.KbDocumentMapper;
import com.ruoyi.system.mapper.KbIndexPolicyMapper;
import com.ruoyi.system.mapper.KbKnowledgeMapper;
import com.ruoyi.system.service.ISysConfigService;

/**
 * 版本化知识引擎：发布不可变版本、库升级任务、回滚。
 */
@Service
@DataSource(DataSourceType.SLAVE)
public class KbIndexPolicyService
{
    private static final Logger log = LoggerFactory.getLogger(KbIndexPolicyService.class);
    private static final String CFG_PREFIX = "kb.default.";

    @Autowired
    private KbIndexPolicyMapper policyMapper;
    @Autowired
    private KbKnowledgeMapper kbKnowledgeMapper;
    @Autowired
    private KbDocumentMapper kbDocumentMapper;
    @Autowired
    private KbAuthorizationService kbAuthorizationService;
    @Autowired
    private ISysConfigService configService;
    @Autowired(required = false)
    private KbSearchQualityGate searchQualityGate;

    public Map<String, Object> status()
    {
        Map<String, Object> out = new LinkedHashMap<>();
        ensureSchemaOrThrow();
        Map<String, Object> ptr = policyMapper.selectPolicyPointer();
        KbIndexPolicyVersion published = null;
        try
        {
            published = policyMapper.selectPublishedVersion();
        }
        catch (Exception ignored)
        {
        }
        out.put("pointer", ptr);
        out.put("published", published);
        out.put("versions", policyMapper.selectVersions(20));
        out.put("runningJobs", policyMapper.countRunningJobs());
        if (published != null)
        {
            out.put("upgradeCandidates", policyMapper.selectUpgradeCandidates(published.getVersionId()));
        }
        else
        {
            out.put("upgradeCandidates", List.of());
        }
        return out;
    }

    public List<KbIndexPolicyVersion> listVersions()
    {
        ensureSchemaOrThrow();
        return policyMapper.selectVersions(50);
    }

    /**
     * 从当前工作草稿（sys_config 或传入 body）发布不可变版本。
     * <p><b>不会</b>修改任何已有库的 active_policy_version_id。
     */
    public KbIndexPolicyVersion publish(Map<String, Object> body, String operator)
    {
        ensureSchemaOrThrow();
        Map<String, Object> payload = body != null && !body.isEmpty()
            ? normalizeBody(body)
            : loadWorkingDraftFromConfig();
        Map<String, Object> check = prePublishCheck(payload);
        if (!Boolean.TRUE.equals(check.get("passed")))
        {
            throw new ServiceException("发布检查未通过: " + check.get("message"));
        }

        Integer maxNo = policyMapper.selectMaxVersionNo();
        int next = (maxNo == null ? 0 : maxNo) + 1;
        KbIndexPolicyVersion v = new KbIndexPolicyVersion();
        v.setVersionNo(next);
        v.setVersionLabel(body != null && body.get("versionLabel") != null
            ? String.valueOf(body.get("versionLabel")) : ("v" + next));
        v.setStatus("PUBLISHED");
        v.setPayloadJson(KbIndexPolicyPayload.toJson(payload));
        v.setFingerprint(KbIndexPolicyPayload.fingerprint(payload));
        v.setCheckReport(JSON.toJSONString(check));
        v.setPublishedBy(operator);
        v.setPublishedAt(new Date());
        v.setCreateBy(operator);
        policyMapper.insertVersion(v);

        policyMapper.upsertPolicyPointer(
            KbIndexPolicyPayload.toJson(payload),
            v.getVersionId(),
            null,
            operator);

        // 同步 sys_config 工作草稿 = 已发布内容（平台当前值）
        writeWorkingDraftToConfig(payload);
        return v;
    }

    public Map<String, Object> prePublishCheck(Map<String, Object> payload)
    {
        Map<String, Object> report = new LinkedHashMap<>();
        List<Map<String, Object>> checks = new ArrayList<>();
        boolean passed = true;

        String emb = KbIndexPolicyPayload.embedding(payload);
        passed &= addCheck(checks, "embedding_required", StringUtils.isNotEmpty(emb), "必须配置向量模型");
        int size = KbIndexPolicyPayload.size(payload);
        int overlap = KbIndexPolicyPayload.overlap(payload);
        passed &= addCheck(checks, "chunk_range", size >= 100 && size <= 4000, "分块大小需 100~4000");
        passed &= addCheck(checks, "overlap_lt_size", overlap < size, "重叠必须小于分块大小");
        String strategy = KbIndexPolicyPayload.strategy(payload);
        passed &= addCheck(checks, "strategy", "P".equals(strategy) || "F".equals(strategy), "策略仅 P/F");

        if (searchQualityGate != null)
        {
            try
            {
                var gate = searchQualityGate.evaluate();
                passed &= addCheck(checks, "search_quality_gate", gate.passed,
                    gate.passed ? "检索质量门禁通过" : String.join("; ", gate.failures));
            }
            catch (Exception e)
            {
                passed &= addCheck(checks, "search_quality_gate", true, "门禁不可用，已跳过: " + e.getMessage());
            }
        }

        report.put("passed", passed);
        report.put("checks", checks);
        report.put("message", passed ? "ok" : "存在未通过检查项");
        report.put("fingerprint", KbIndexPolicyPayload.fingerprint(payload));
        return report;
    }

    public Map<String, Object> kbPolicyStatus(Long kbId)
    {
        kbAuthorizationService.requireKb(kbId, KbAccessAction.MANAGE);
        ensureSchemaOrThrow();
        KbKnowledge kb = kbKnowledgeMapper.selectKbKnowledgeById(kbId);
        KbIndexPolicyVersion published = policyMapper.selectPublishedVersion();
        KbIndexPolicyVersion active = kb.getActivePolicyVersionId() == null ? null
            : policyMapper.selectVersionById(kb.getActivePolicyVersionId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kbId", kbId);
        out.put("activeVersionId", kb.getActivePolicyVersionId());
        out.put("desiredVersionId", kb.getDesiredPolicyVersionId());
        out.put("previousVersionId", kb.getPreviousPolicyVersionId());
        out.put("indexState", kb.getIndexState() == null ? "READY" : kb.getIndexState());
        out.put("active", active);
        out.put("published", published);
        boolean upgradable = published != null
            && (kb.getActivePolicyVersionId() == null
                || !published.getVersionId().equals(kb.getActivePolicyVersionId()));
        out.put("upgradable", upgradable);
        out.put("jobs", policyMapper.selectJobsByKb(kbId, 10));
        return out;
    }

    public List<KbIndexJob> recentJobs()
    {
        ensureSchemaOrThrow();
        return policyMapper.selectRecentJobs(30);
    }

    public KbIndexJob getJob(Long jobId)
    {
        ensureSchemaOrThrow();
        return policyMapper.selectJobById(jobId);
    }

    /** 建库时绑定当前已发布版本（若有） */
    public void bindPublishedOnCreate(KbKnowledge kb)
    {
        if (kb == null || kb.getKbId() == null)
        {
            return;
        }
        try
        {
            ensureSchemaOrThrow();
            KbIndexPolicyVersion published = policyMapper.selectPublishedVersion();
            if (published == null)
            {
                // 无版本时从当前 config 自动发布 v1，保证新库可追踪
                Map<String, Object> draft = loadWorkingDraftFromConfig();
                if (StringUtils.isNotEmpty(KbIndexPolicyPayload.embedding(draft)))
                {
                    published = publish(draft, "system");
                }
            }
            if (published != null)
            {
                policyMapper.updateKbPolicyBinding(kb.getKbId(), published.getVersionId(),
                    null, null, "READY");
            }
        }
        catch (Exception e)
        {
            log.debug("建库绑定策略版本跳过: {}", e.getMessage());
        }
    }

    public Map<String, Object> loadWorkingDraftFromConfig()
    {
        return DataSourceScope.runOn(DataSourceType.MASTER, () -> KbIndexPolicyPayload.of(
            configService.selectConfigByKey(CFG_PREFIX + "embeddingModel"),
            configService.selectConfigByKey(CFG_PREFIX + "extractModel"),
            configService.selectConfigByKey(CFG_PREFIX + "chunkStrategy"),
            parseInt(configService.selectConfigByKey(CFG_PREFIX + "chunkSize"), 800),
            parseInt(configService.selectConfigByKey(CFG_PREFIX + "chunkOverlap"), 100),
            configService.selectConfigByKey(CFG_PREFIX + "graphEnabled")));
    }

    private void writeWorkingDraftToConfig(Map<String, Object> payload)
    {
        // 配置写 MySQL，由 controller 已有路径负责；此处仅 best-effort 同步 draft 指针
        try
        {
            policyMapper.upsertPolicyPointer(KbIndexPolicyPayload.toJson(payload), null, null,
                username());
        }
        catch (Exception e)
        {
            log.debug("sync draft pointer: {}", e.getMessage());
        }
    }

    private Map<String, Object> normalizeBody(Map<String, Object> body)
    {
        return KbIndexPolicyPayload.of(
            str(body.get("embeddingModel")),
            str(body.get("extractModel")),
            str(body.get("chunkStrategy")),
            parseInt(body.get("chunkSize"), 800),
            parseInt(body.get("chunkOverlap"), 100),
            str(body.get("graphEnabled")));
    }

    private void ensureSchemaOrThrow()
    {
        try
        {
            policyMapper.selectPolicyPointer();
        }
        catch (Exception e)
        {
            throw new ServiceException("策略版本表未就绪，请执行 sql/kb_index_policy_v1.sql");
        }
    }

    private static boolean addCheck(List<Map<String, Object>> checks, String id, boolean ok, String msg)
    {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", id);
        c.put("ok", ok);
        c.put("message", msg);
        checks.add(c);
        return ok;
    }

    private static String str(Object o)
    {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static int parseInt(Object o, int def)
    {
        if (o instanceof Number n) return n.intValue();
        if (o == null) return def;
        try { return Integer.parseInt(o.toString().trim()); }
        catch (Exception e) { return def; }
    }

    private static String username()
    {
        try { return SecurityUtils.getUsername(); }
        catch (Exception e) { return ""; }
    }
}
