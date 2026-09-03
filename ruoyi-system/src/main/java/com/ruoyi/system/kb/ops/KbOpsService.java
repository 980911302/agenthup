package com.ruoyi.system.kb.ops;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.common.annotation.DataSource;
import com.ruoyi.common.enums.DataSourceType;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.kb.access.KbAuthorizationService;
import com.ruoyi.system.kb.graph.KbGraphStore;
import com.ruoyi.system.kb.policy.KbIndexPolicyService;
import com.ruoyi.system.kb.search.KbSearchMetrics;
import com.ruoyi.system.kb.search.KbSearchModePolicy;
import com.ruoyi.system.kb.search.KbSearchQualityGate;
import com.ruoyi.system.mapper.KbIndexPolicyMapper;
import com.ruoyi.system.service.IKbKnowledgeService;

/**
 * 平台知识库运行观测与诊断聚合（KB-PV2-11）。
 */
@Service
@DataSource(DataSourceType.SLAVE)
public class KbOpsService
{
    private static final Logger log = LoggerFactory.getLogger(KbOpsService.class);

    @Autowired(required = false)
    private KbSearchMetrics searchMetrics;
    @Autowired(required = false)
    private KbSearchModePolicy searchModePolicy;
    @Autowired(required = false)
    private KbSearchQualityGate searchQualityGate;
    @Autowired
    private KbGraphStore kbGraphStore;
    @Autowired(required = false)
    private KbIndexPolicyService policyService;
    @Autowired(required = false)
    private KbIndexPolicyMapper policyMapper;
    @Autowired
    private KbAuthorizationService kbAuthorizationService;
    @Autowired
    private IKbKnowledgeService kbKnowledgeService;

    /** 平台管理员运行总览 */
    public Map<String, Object> platformOps()
    {
        requireAdmin();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generatedAt", System.currentTimeMillis());

        // 依赖
        Map<String, Object> deps = new LinkedHashMap<>();
        deps.put("neo4jAvailable", kbGraphStore != null && kbGraphStore.isAvailable());
        deps.put("searchMetrics", searchMetrics != null);
        deps.put("policySchema", policyMapper != null);
        out.put("dependencies", deps);

        // 检索观测
        if (searchMetrics != null)
        {
            out.put("searchMetrics", searchMetrics.snapshot());
        }
        else
        {
            out.put("searchMetrics", Map.of("ok", false, "reason", "metrics_unavailable"));
        }

        // 灰度 / 门禁
        Map<String, Object> rollout = new LinkedHashMap<>();
        if (searchModePolicy != null)
        {
            rollout.put("enabledModes", searchModePolicy.enabledSet().stream().map(Enum::name).sorted().toList());
            rollout.put("defaultMode", searchModePolicy.defaultMode().name());
            rollout.put("maxConcurrent", searchModePolicy.getMaxConcurrent());
        }
        if (searchQualityGate != null)
        {
            try
            {
                var gate = searchQualityGate.evaluate();
                rollout.put("gatePassed", gate.passed);
                rollout.put("gateFailures", gate.failures);
                rollout.put("gateChecks", gate.checks);
            }
            catch (Exception e)
            {
                rollout.put("gatePassed", null);
                rollout.put("gateError", e.getMessage());
            }
        }
        out.put("rollout", rollout);

        // 策略版本与任务
        if (policyService != null)
        {
            try
            {
                Map<String, Object> st = policyService.status();
                out.put("policy", Map.of(
                    "published", st.get("published"),
                    "runningJobs", st.get("runningJobs"),
                    "upgradeCandidateCount",
                    st.get("upgradeCandidates") instanceof List<?> l ? l.size() : 0));
                out.put("recentJobs", policyService.recentJobs());
            }
            catch (Exception e)
            {
                out.put("policy", Map.of("note", e.getMessage()));
                out.put("recentJobs", List.of());
            }
        }

        // 产品态说明
        out.put("productNotes", List.of(
            "图谱或向量降级时知识库保持「部分可用」，不整库判死。",
            "删除为软删除（del_flag）；物理清理/回收站延迟清理列入后续运维 PR。",
            "SSE 仅通知进度，事实源在文档 parse 状态与 index job 表。"
        ));
        return out;
    }

    /** 仅 {@link SecurityUtils#isAdmin()}，body code 403 */
    private static void requireAdmin()
    {
        try
        {
            if (SecurityUtils.isAdmin())
            {
                return;
            }
        }
        catch (Exception ignored)
        {
        }
        throw new ServiceException("仅平台管理员可访问", com.ruoyi.common.constant.HttpStatus.FORBIDDEN);
    }

    private static String nullToEmpty(String s)
    {
        return s == null ? "" : s;
    }
}
