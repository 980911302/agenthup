package com.ruoyi.system.service.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.ruoyi.common.annotation.DataSource;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.datasource.DataSourceScope;
import com.ruoyi.common.enums.DataSourceType;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.PageUtils;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.domain.KbAclMember;
import com.ruoyi.system.domain.KbGraphIndex;
import com.ruoyi.system.domain.KbKnowledge;
import com.ruoyi.system.domain.vo.KbMemberCandidateVO;
import com.ruoyi.system.domain.vo.KbUserBriefVO;
import com.ruoyi.system.domain.vo.KbWorkbenchItem;
import com.ruoyi.system.kb.KbHealthCalculator;
import com.ruoyi.system.kb.KbOverviewTodos;
import com.ruoyi.system.kb.access.KbAccessAction;
import com.ruoyi.system.kb.access.KbAccessPolicy;
import com.ruoyi.system.kb.access.KbAuthorizationService;
import com.ruoyi.system.kb.access.KbOwnershipTransactionService;
import com.ruoyi.system.kb.graph.KbGraphStore;
import com.ruoyi.system.kb.policy.KbIndexPolicyService;
import com.ruoyi.system.ai.session.AiChatSessionKbCleanup;
import com.ruoyi.system.kb.vector.KbVectorStore;
import com.ruoyi.system.mapper.KbAclMemberMapper;
import com.ruoyi.system.mapper.KbChunkMapper;
import com.ruoyi.system.mapper.KbDocGraphMapper;
import com.ruoyi.system.mapper.KbGraphCommunityMapper;
import com.ruoyi.system.mapper.KbGraphRunMapper;
import com.ruoyi.system.mapper.KbGraphTextUnitMapper;
import com.ruoyi.system.mapper.KbKnowledgeMapper;
import com.ruoyi.system.mapper.SysUserMapper;
import com.ruoyi.system.service.IKbKnowledgeService;
import com.ruoyi.system.service.ISysConfigService;

/**
 * 知识库 服务层实现(数据在 PostgreSQL slave)。
 * <p>SecurityUtils 只读登录态,不碰 MySQL 表,可与 SLAVE 同方法共存。
 */
@Service
@DataSource(DataSourceType.SLAVE)
public class KbKnowledgeServiceImpl implements IKbKnowledgeService
{
    private static final Logger log = LoggerFactory.getLogger(KbKnowledgeServiceImpl.class);

    @Autowired
    private KbKnowledgeMapper kbKnowledgeMapper;

    @Autowired
    private ISysConfigService configService;

    @Autowired
    private KbChunkMapper kbChunkMapper;

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

    @Autowired
    private KbGraphCommunityMapper kbGraphCommunityMapper;

    @Autowired
    private KbAuthorizationService kbAuthorizationService;

    @Autowired(required = false)
    private AiChatSessionKbCleanup aiChatSessionKbCleanup;

    @Autowired(required = false)
    private KbAclMemberMapper kbAclMemberMapper;

    @Autowired(required = false)
    private KbIndexPolicyService kbIndexPolicyService;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private KbOwnershipTransactionService kbOwnershipTransactionService;

    @Override
    public KbKnowledge selectKbKnowledgeById(Long kbId)
    {
        return kbAuthorizationService.requireKb(kbId, KbAccessAction.READ);
    }

    @Override
    public List<KbKnowledge> selectKbKnowledgeList(KbKnowledge kbKnowledge)
    {
        if (kbKnowledge == null)
        {
            kbKnowledge = new KbKnowledge();
        }
        kbAuthorizationService.applyListScope(kbKnowledge);
        List<KbKnowledge> rows = kbKnowledgeMapper.selectKbKnowledgeList(kbKnowledge);
        if (rows == null)
        {
            return new ArrayList<>();
        }
        // 保留旧 /list 接口的兼容性，但最终可见性必须与详情、工作台一致。
        // SQL 仅负责候选集预筛，不能成为授权的唯一防线。
        rows.removeIf(kb -> !kbAuthorizationService.canAccess(kb, KbAccessAction.READ));
        return rows;
    }

    @Override
    public Map<String, Object> selectWorkbench(KbKnowledge query)
    {
        if (query == null)
        {
            query = new KbKnowledge();
        }
        kbAuthorizationService.applyListScope(query);
        List<KbWorkbenchItem> rows = kbKnowledgeMapper.selectWorkbenchList(query);
        if (rows == null)
        {
            rows = new ArrayList<>();
        }
        // SQL 的范围条件负责缩小候选集；最终输出必须复用详情接口的 Java 策略。
        // 这样任何策略升级都不会再产生“卡片可见但点不开”的假可见项。
        rows.removeIf(row -> !kbAuthorizationService.canAccess(toAccessProbe(row), KbAccessAction.READ));
        fillOwners(rows);
        int processing = 0;
        int issue = 0;
        int empty = 0;
        int ready = 0;
        int disabled = 0;
        for (KbWorkbenchItem row : rows)
        {
            KbHealthCalculator.apply(row);
            switch (row.getHealth() == null ? "" : row.getHealth())
            {
                case "PROCESSING" -> processing++;
                case "ISSUE" -> issue++;
                case "EMPTY" -> empty++;
                case "READY" -> ready++;
                case "DISABLED" -> disabled++;
                default -> {
                }
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", rows.size());
        summary.put("processing", processing);
        summary.put("issue", issue);
        summary.put("empty", empty);
        summary.put("ready", ready);
        summary.put("disabled", disabled);
        summary.put("upgrade", 0); // PR10 前无升级语义

        Map<String, Object> out = new LinkedHashMap<>();
        // 平台管理员仅由后端 SecurityUtils.isAdmin() 判定，前端禁止 roles.includes('admin')
        out.put("isPlatformAdmin", currentIsPlatformAdmin());
        out.put("rows", rows);
        out.put("summary", summary);
        return out;
    }

    @Override
    public Map<String, Object> selectOverview(Long kbId)
    {
        KbKnowledge kb = kbAuthorizationService.requireKb(kbId, KbAccessAction.READ);

        KbKnowledge q = new KbKnowledge();
        q.setKbId(kbId);
        List<KbWorkbenchItem> rows = kbKnowledgeMapper.selectWorkbenchList(q);
        KbWorkbenchItem item = (rows != null && !rows.isEmpty()) ? rows.get(0) : fromKb(kb);
        KbHealthCalculator.apply(item);

        Map<String, Object> graph = buildGraphSummary(kb);
        List<Map<String, Object>> todos = KbOverviewTodos.build(item, graph);

        Map<String, Object> availability = new LinkedHashMap<>();
        availability.put("docCount", nz(item.getDocCount()));
        availability.put("readyCount", nz(item.getReadyCount()));
        availability.put("processingCount", nz(item.getProcessingCount()));
        availability.put("failedCount", nz(item.getFailedCount()));
        availability.put("lastContentTime", item.getLastContentTime());

        Map<String, Object> usage = new LinkedHashMap<>();
        // 检索量统计依赖 PR09/观测完善；当前返回占位
        usage.put("recentSearchCount", null);
        usage.put("recentSearchNote", "检索量统计即将提供");

        Map<String, Object> quality = new LinkedHashMap<>();
        quality.put("available", false);
        quality.put("note", "质量评测将在后续版本开放");
        quality.put("action", "open_quality");

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("kbId", kb.getKbId());
        header.put("kbName", kb.getKbName());
        header.put("description", kb.getDescription());
        header.put("status", kb.getStatus());
        header.put("createBy", kb.getCreateBy());
        header.put("createUserId", kb.getCreateUserId());
        header.put("deptId", kb.getDeptId());
        header.put("createTime", kb.getCreateTime());
        header.put("updateTime", kb.getUpdateTime());
        header.put("visibility", item.getVisibility());
        header.put("health", item.getHealth());
        header.put("healthReason", item.getHealthReason());
        header.put("suggestedAction", KbHealthCalculator.suggestedAction(item.getHealth()));
        header.put("lastContentTime", item.getLastContentTime());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("header", header);
        out.put("availability", availability);
        out.put("usage", usage);
        out.put("graph", graph);
        out.put("quality", quality);
        out.put("todos", todos);
        return out;
    }

    private KbWorkbenchItem fromKb(KbKnowledge kb)
    {
        KbWorkbenchItem item = new KbWorkbenchItem();
        item.setKbId(kb.getKbId());
        item.setKbName(kb.getKbName());
        item.setDescription(kb.getDescription());
        item.setStatus(kb.getStatus());
        item.setCreateUserId(kb.getCreateUserId());
        item.setOwnerUserId(kb.getOwnerUserId());
        item.setVisibilityCode(kb.getVisibility());
        item.setDeptId(kb.getDeptId());
        item.setCreateBy(kb.getCreateBy());
        item.setCreateTime(kb.getCreateTime());
        item.setUpdateTime(kb.getUpdateTime());
        item.setDocCount(0);
        item.setProcessingCount(0);
        item.setFailedCount(0);
        item.setReadyCount(0);
        return item;
    }

    /** 工作台聚合行转换为仅供权限判定使用的知识库对象。 */
    private static KbKnowledge toAccessProbe(KbWorkbenchItem item)
    {
        KbKnowledge kb = new KbKnowledge();
        if (item == null)
        {
            return kb;
        }
        kb.setKbId(item.getKbId());
        kb.setCreateUserId(item.getCreateUserId());
        kb.setOwnerUserId(item.getOwnerUserId());
        kb.setDeptId(item.getDeptId());
        kb.setVisibility(item.getVisibilityCode());
        return kb;
    }

    private Map<String, Object> buildGraphSummary(KbKnowledge kb)
    {
        Map<String, Object> graph = new LinkedHashMap<>();
        boolean enabled = "1".equals(kb.getGraphEnabled());
        graph.put("graphEnabled", enabled ? "1" : "0");
        boolean available = kbGraphStore != null && kbGraphStore.isAvailable();
        graph.put("available", available);
        int entities = 0;
        int relations = 0;
        if (available)
        {
            try
            {
                entities = kbGraphStore.countEntities(kb.getKbId());
                relations = kbGraphStore.countRelations(kb.getKbId());
            }
            catch (Exception e)
            {
                graph.put("available", false);
                graph.put("error", e.getMessage());
            }
        }
        graph.put("entityCount", entities);
        graph.put("relationCount", relations);
        int communityCount = 0;
        String indexStatus = null;
        java.util.Date finishedAt = null;
        try
        {
            KbGraphIndex idx = kbGraphCommunityMapper.selectIndex(kb.getKbId());
            if (idx != null)
            {
                if (idx.getCommunityCount() != null)
                {
                    communityCount = idx.getCommunityCount();
                }
                indexStatus = idx.getStatus();
                finishedAt = idx.getFinishedAt();
            }
        }
        catch (Exception e)
        {
            log.debug("读取图谱索引摘要失败: {}", e.getMessage());
        }
        graph.put("communityCount", communityCount);
        graph.put("indexStatus", indexStatus);
        graph.put("lastBuiltAt", finishedAt);
        return graph;
    }

    private static int nz(Integer v)
    {
        return v == null ? 0 : v;
    }

    @Override
    public Map<String, Object> deleteImpactPreview(Long kbId)
    {
        KbKnowledge kb = kbAuthorizationService.requireKb(kbId, KbAccessAction.DELETE);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kbId", kbId);
        out.put("kbName", kb.getKbName());
        int docs = 0;
        List<KbWorkbenchItem> all = kbKnowledgeMapper.selectWorkbenchList(scopeSingle(kbId));
        if (all != null)
        {
            for (KbWorkbenchItem item : all)
            {
                if (kbId.equals(item.getKbId()))
                {
                    docs = item.getDocCount() == null ? 0 : item.getDocCount();
                    break;
                }
            }
        }
        out.put("docCount", docs);
        out.put("warning", "将清理 " + docs + " 篇文档及向量/图谱数据");
        return out;
    }

    private KbKnowledge scopeSingle(Long kbId)
    {
        KbKnowledge q = new KbKnowledge();
        // 已 require DELETE，可绕过列表范围：用超管标记 + 精确 id 过滤不足，改用 workbench 全量后筛
        kbAuthorizationService.applyListScope(q);
        return q;
    }

    /**
     * 批量回填有效负责人展示信息（MySQL MASTER 一次查询，禁止 N+1）。
     */
    private void fillOwners(List<KbWorkbenchItem> rows)
    {
        if (rows == null || rows.isEmpty())
        {
            return;
        }
        Set<Long> ownerIds = new HashSet<>();
        for (KbWorkbenchItem r : rows)
        {
            Long oid = r.effectiveOwnerId();
            if (oid != null)
            {
                ownerIds.add(oid);
            }
        }
        if (ownerIds.isEmpty())
        {
            return;
        }
        Map<Long, KbUserBriefVO> byId = loadUserBriefs(new ArrayList<>(ownerIds));
        for (KbWorkbenchItem r : rows)
        {
            Long oid = r.effectiveOwnerId();
            if (oid == null)
            {
                continue;
            }
            KbUserBriefVO brief = byId.get(oid);
            if (brief != null)
            {
                r.setOwner(brief);
            }
            else
            {
                // 用户已删除或查不到时仍给出 userId，避免前端回退 createBy
                r.setOwner(new KbUserBriefVO(oid, null, null));
            }
        }
    }

    /**
     * MySQL 批量查用户简要信息；失败时返回空 map，不阻断工作台主路径。
     */
    private Map<Long, KbUserBriefVO> loadUserBriefs(List<Long> userIds)
    {
        Map<Long, KbUserBriefVO> map = new HashMap<>();
        if (userIds == null || userIds.isEmpty() || sysUserMapper == null)
        {
            return map;
        }
        try
        {
            List<SysUser> users = DataSourceScope.runOn(DataSourceType.MASTER,
                () -> sysUserMapper.selectUsersByIds(userIds));
            if (users == null)
            {
                return map;
            }
            for (SysUser u : users)
            {
                if (u == null || u.getUserId() == null)
                {
                    continue;
                }
                String deptName = null;
                if (u.getDept() != null)
                {
                    deptName = u.getDept().getDeptName();
                }
                map.put(u.getUserId(), new KbUserBriefVO(
                    u.getUserId(), u.getUserName(), u.getNickName(), deptName));
            }
        }
        catch (Exception e)
        {
            log.debug("批量回填用户展示信息失败: {}", e.getMessage());
        }
        return map;
    }

    private static boolean currentIsPlatformAdmin()
    {
        try
        {
            return SecurityUtils.isAdmin();
        }
        catch (Exception e)
        {
            return false;
        }
    }

    private static Long toLong(Object o)
    {
        if (o == null)
        {
            return null;
        }
        if (o instanceof Number n)
        {
            return n.longValue();
        }
        try
        {
            return Long.parseLong(o.toString());
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static Integer toInt(Object o)
    {
        if (o == null)
        {
            return null;
        }
        if (o instanceof Number n)
        {
            return n.intValue();
        }
        try
        {
            return Integer.parseInt(o.toString());
        }
        catch (Exception e)
        {
            return null;
        }
    }

    @Override
    public int insertKbKnowledge(KbKnowledge kbKnowledge)
    {
        if (kbKnowledge == null)
        {
            throw new ServiceException("请求体不能为空", HttpStatus.BAD_REQUEST);
        }
        // 名称业务校验（与 @Valid 双保险；空白/超长 body code 400）
        validateKbNameRequired(kbKnowledge.getKbName());

        // 创建即归属:记录创建者与所在部门,供后续部门过滤使用
        Long uid = SecurityUtils.getUserId();
        kbKnowledge.setCreateUserId(uid);
        kbKnowledge.setOwnerUserId(uid);
        kbKnowledge.setDeptId(SecurityUtils.getDeptId());
        // 缺省 visibility 固定 PRIVATE（不再因有部门默认 DEPT）
        if (StringUtils.isEmpty(kbKnowledge.getVisibility()))
        {
            kbKnowledge.setVisibility(KbAccessPolicy.VIS_PRIVATE);
        }
        else
        {
            kbKnowledge.setVisibility(normalizeVisibility(kbKnowledge.getVisibility()));
        }
        // KB-PV2-01：技术字段一律来自平台知识引擎快照，忽略客户端传入
        clearTechFields(kbKnowledge);
        applyPlatformEngineSnapshot(kbKnowledge);
        if (StringUtils.isEmpty(kbKnowledge.getChunkStrategy()))
        {
            kbKnowledge.setChunkStrategy("P");
        }
        if (kbKnowledge.getChunkSize() == null)
        {
            kbKnowledge.setChunkSize(800);
        }
        if (kbKnowledge.getChunkOverlap() == null)
        {
            kbKnowledge.setChunkOverlap(100);
        }
        if (StringUtils.isEmpty(kbKnowledge.getGraphEnabled()))
        {
            kbKnowledge.setGraphEnabled("0");
        }
        if (StringUtils.isEmpty(kbKnowledge.getEmbeddingModelCode()))
        {
            throw new ServiceException("平台未配置知识引擎向量模型，请联系管理员在「知识引擎」中配置后再建库");
        }
        int n = kbKnowledgeMapper.insertKbKnowledge(kbKnowledge);
        // KB-PV2-10：绑定当前已发布策略版本（不静默改已有库）
        if (n > 0 && kbIndexPolicyService != null)
        {
            try
            {
                kbIndexPolicyService.bindPublishedOnCreate(kbKnowledge);
            }
            catch (Exception e)
            {
                log.debug("绑定策略版本跳过: {}", e.getMessage());
            }
        }
        return n;
    }

    /**
     * 平台知识引擎策略快照（sys_config kb.default.*）。
     * <p><b>只在创建时固化一次</b>：之后改平台策略不影响已有库。
     * <p>必须切 MASTER 读 MySQL 的 sys_config。
     */
    private void applyPlatformEngineSnapshot(KbKnowledge kb)
    {
        try
        {
            DataSourceScope.runOn(DataSourceType.MASTER, () -> {
                // 强制覆盖：产品 V2 不允许客户端指定技术字段
                kb.setEmbeddingModelCode(
                    StringUtils.trimToNull(configService.selectConfigByKey("kb.default.embeddingModel")));
                kb.setExtractModelCode(
                    StringUtils.trimToNull(configService.selectConfigByKey("kb.default.extractModel")));
                String strategy = StringUtils.trimToNull(
                    configService.selectConfigByKey("kb.default.chunkStrategy"));
                kb.setChunkStrategy(strategy != null ? strategy : "P");
                Integer size = parseInt(configService.selectConfigByKey("kb.default.chunkSize"));
                kb.setChunkSize(size != null ? size : 800);
                Integer overlap = parseInt(configService.selectConfigByKey("kb.default.chunkOverlap"));
                kb.setChunkOverlap(overlap != null ? overlap : 100);
                String graph = StringUtils.trimToNull(
                    configService.selectConfigByKey("kb.default.graphEnabled"));
                kb.setGraphEnabled("1".equals(graph) ? "1" : "0");
            });
        }
        catch (Exception e)
        {
            log.warn("读取平台知识引擎策略失败,降级为内置兜底值: {}", e.getMessage());
            if (StringUtils.isEmpty(kb.getChunkStrategy()))
            {
                kb.setChunkStrategy("P");
            }
            if (kb.getChunkSize() == null)
            {
                kb.setChunkSize(800);
            }
            if (kb.getChunkOverlap() == null)
            {
                kb.setChunkOverlap(100);
            }
            if (StringUtils.isEmpty(kb.getGraphEnabled()))
            {
                kb.setGraphEnabled("0");
            }
        }
    }

    private static void clearTechFields(KbKnowledge kb)
    {
        if (kb == null)
        {
            return;
        }
        kb.setEmbeddingModelCode(null);
        kb.setExtractModelCode(null);
        kb.setChunkStrategy(null);
        kb.setChunkSize(null);
        kb.setChunkOverlap(null);
        kb.setGraphEnabled(null);
    }

    private static Integer parseInt(String v)
    {
        if (StringUtils.isEmpty(v))
        {
            return null;
        }
        try
        {
            return Integer.valueOf(v.trim());
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    @Override
    public int updateKbKnowledge(KbKnowledge kbKnowledge)
    {
        if (kbKnowledge == null || kbKnowledge.getKbId() == null)
        {
            throw new ServiceException(KbAuthorizationService.DENY_MSG, HttpStatus.FORBIDDEN);
        }
        // status 或 visibility 变更需 MANAGE；仅名称/说明可用 WRITE
        boolean changeStatus = StringUtils.isNotEmpty(kbKnowledge.getStatus());
        boolean changeVis = StringUtils.isNotEmpty(kbKnowledge.getVisibility());
        boolean needManage = changeStatus || changeVis;
        kbAuthorizationService.requireKb(kbKnowledge.getKbId(),
            needManage ? KbAccessAction.MANAGE : KbAccessAction.WRITE);

        // 局部更新：仅当请求显式传入名称时校验非空与长度
        if (kbKnowledge.getKbName() != null)
        {
            validateKbNameRequired(kbKnowledge.getKbName());
            kbKnowledge.setKbName(kbKnowledge.getKbName().trim());
        }

        // KB-PV2-01：编辑只允许业务字段，技术字段不可通过 API 改写
        KbKnowledge patch = new KbKnowledge();
        patch.setKbId(kbKnowledge.getKbId());
        if (kbKnowledge.getKbName() != null)
        {
            patch.setKbName(kbKnowledge.getKbName());
        }
        // description 允许置空：null 表示未传；空串表示清空
        if (kbKnowledge.getDescription() != null)
        {
            patch.setDescription(kbKnowledge.getDescription());
        }
        if (changeStatus)
        {
            patch.setStatus(kbKnowledge.getStatus());
        }
        if (kbKnowledge.getRemark() != null)
        {
            patch.setRemark(kbKnowledge.getRemark());
        }
        patch.setUpdateBy(kbKnowledge.getUpdateBy());
        if (changeVis)
        {
            patch.setVisibility(normalizeVisibility(kbKnowledge.getVisibility()));
        }
        return kbKnowledgeMapper.updateKbKnowledge(patch);
    }

    /** 名称必填且最长 100 字（创建 / 传入名称的更新） */
    private static void validateKbNameRequired(String kbName)
    {
        if (kbName == null || kbName.isBlank())
        {
            throw new ServiceException("知识库名称不能为空", HttpStatus.BAD_REQUEST);
        }
        if (kbName.trim().length() > 100)
        {
            throw new ServiceException("知识库名称不能超过100个字符", HttpStatus.BAD_REQUEST);
        }
    }

    @Override
    public Map<String, Object> selectUsage(Long kbId)
    {
        // 管理抽屉专用：MANAGE；不向普通首屏提供 access
        KbKnowledge kb = kbAuthorizationService.requireKb(kbId, KbAccessAction.MANAGE);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kbId", kbId);
        out.put("kbName", kb.getKbName());
        out.put("status", kb.getStatus());
        out.put("visibility", KbAccessPolicy.effectiveVisibility(kb));
        Long ownerId = KbAccessPolicy.effectiveOwnerId(kb);
        out.put("ownerUserId", ownerId);
        out.put("createBy", kb.getCreateBy());
        out.put("deptId", kb.getDeptId());

        List<KbAclMember> members = List.of();
        if (kbAclMemberMapper != null)
        {
            try
            {
                List<KbAclMember> ms = kbAclMemberMapper.selectByKbId(kbId);
                if (ms != null)
                {
                    members = ms;
                }
            }
            catch (Exception e)
            {
                log.debug("列出 ACL 成员失败(可能未迁移): {}", e.getMessage());
            }
        }
        fillMemberDisplay(members);
        out.put("members", members);

        // 展示用 owner（一次批量查询）
        if (ownerId != null)
        {
            Map<Long, KbUserBriefVO> briefs = loadUserBriefs(List.of(ownerId));
            KbUserBriefVO owner = briefs.get(ownerId);
            if (owner == null)
            {
                owner = new KbUserBriefVO(ownerId, null, null, null);
            }
            out.put("owner", owner);
        }
        return out;
    }

    @Override
    public Map<String, Object> searchMemberCandidates(Long kbId, String keyword, int pageNum, int pageSize)
    {
        // 1) PG：MANAGE 鉴权 + 收集排除 ID（owner + 现有成员）
        KbKnowledge kb = kbAuthorizationService.requireKb(kbId, KbAccessAction.MANAGE);
        // 关键词可空：空串返回默认候选(按 userId 升序前 pageSize 条)，供前端打开成员弹窗即预载；
        // 非空则按姓名/用户名模糊过滤。长度不再设下限，1 个字也能搜。
        String kw = keyword == null ? "" : keyword.trim();
        if (pageNum < 1)
        {
            pageNum = 1;
        }
        if (pageSize < 1)
        {
            pageSize = 20;
        }
        if (pageSize > 20)
        {
            pageSize = 20;
        }

        Set<Long> exclude = new HashSet<>();
        Long ownerId = KbAccessPolicy.effectiveOwnerId(kb);
        if (ownerId != null)
        {
            exclude.add(ownerId);
        }
        if (kbAclMemberMapper != null)
        {
            try
            {
                List<KbAclMember> ms = kbAclMemberMapper.selectByKbId(kbId);
                if (ms != null)
                {
                    for (KbAclMember m : ms)
                    {
                        if (m != null && m.getUserId() != null)
                        {
                            exclude.add(m.getUserId());
                        }
                    }
                }
            }
            catch (Exception e)
            {
                log.debug("读取成员排除列表失败: {}", e.getMessage());
            }
        }
        List<Long> excludeIds = new ArrayList<>(exclude);
        final int pn = pageNum;
        final int ps = pageSize;
        final String keywordFinal = kw;

        // 2) MySQL MASTER：PageHelper 紧邻用户查询，finally 清理
        return DataSourceScope.runOn(DataSourceType.MASTER, () -> {
            try
            {
                PageHelper.startPage(pn, ps);
                List<SysUser> users = sysUserMapper.selectKbMemberCandidates(keywordFinal, excludeIds);
                PageInfo<SysUser> page = new PageInfo<>(users == null ? List.of() : users);
                List<KbMemberCandidateVO> rows = new ArrayList<>();
                if (users != null)
                {
                    for (SysUser u : users)
                    {
                        if (u == null || u.getUserId() == null)
                        {
                            continue;
                        }
                        String deptName = u.getDept() != null ? u.getDept().getDeptName() : null;
                        rows.add(new KbMemberCandidateVO(
                            u.getUserId(), u.getUserName(), u.getNickName(), deptName));
                    }
                }
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("rows", rows);
                out.put("total", page.getTotal());
                return out;
            }
            finally
            {
                PageUtils.clearPage();
            }
        });
    }

    /** 批量回填成员 userName/nickName/deptName */
    private void fillMemberDisplay(List<KbAclMember> members)
    {
        if (members == null || members.isEmpty())
        {
            return;
        }
        Set<Long> ids = new HashSet<>();
        for (KbAclMember m : members)
        {
            if (m != null && m.getUserId() != null)
            {
                ids.add(m.getUserId());
            }
        }
        if (ids.isEmpty())
        {
            return;
        }
        Map<Long, KbUserBriefVO> briefs = loadUserBriefs(new ArrayList<>(ids));
        for (KbAclMember m : members)
        {
            if (m == null || m.getUserId() == null)
            {
                continue;
            }
            KbUserBriefVO b = briefs.get(m.getUserId());
            if (b != null)
            {
                m.setUserName(b.getUserName());
                m.setNickName(b.getNickName());
                m.setDeptName(b.getDeptName());
            }
        }
    }

    @Override
    public void upsertMember(Long kbId, Long userId, String role)
    {
        kbAuthorizationService.requireKb(kbId, KbAccessAction.MANAGE);
        if (userId == null)
        {
            throw new ServiceException("请指定用户", HttpStatus.BAD_REQUEST);
        }
        requireActiveUser(userId);
        String r = normalizeRole(role);
        if (KbAccessPolicy.ROLE_OWNER.equals(r))
        {
            throw new ServiceException("请使用转移负责人接口设置 OWNER", HttpStatus.BAD_REQUEST);
        }
        if (kbAclMemberMapper == null)
        {
            throw new ServiceException("ACL 表未就绪，请执行 sql/kb_acl_v2.sql");
        }
        Long ownerId = KbAccessPolicy.effectiveOwnerId(
            kbKnowledgeMapper.selectKbKnowledgeById(kbId));
        if (ownerId != null && ownerId.equals(userId))
        {
            throw new ServiceException("负责人无需再添加为成员", HttpStatus.BAD_REQUEST);
        }
        KbAclMember row = new KbAclMember();
        row.setKbId(kbId);
        row.setUserId(userId);
        row.setRole(r);
        try
        {
            row.setCreateBy(SecurityUtils.getUsername());
        }
        catch (Exception ignored)
        {
        }
        // 幂等：已存在则更新角色
        kbAclMemberMapper.upsert(row);
    }

    @Override
    public void removeMember(Long kbId, Long userId)
    {
        kbAuthorizationService.requireKb(kbId, KbAccessAction.MANAGE);
        if (kbAclMemberMapper == null || userId == null)
        {
            return;
        }
        kbAclMemberMapper.deleteByKbAndUser(kbId, userId);
    }

    @Override
    public void transferOwner(Long kbId, Long newOwnerUserId)
    {
        // 入口先鉴权；真正写入在 PG 事务内再次锁定并复核
        kbAuthorizationService.requireKb(kbId, KbAccessAction.DELETE);
        if (newOwnerUserId == null)
        {
            throw new ServiceException("请指定新负责人", HttpStatus.BAD_REQUEST);
        }
        // MySQL 只读前置：目标用户必须存在、未删除、启用
        requireActiveUser(newOwnerUserId);

        String updateBy = null;
        try
        {
            updateBy = SecurityUtils.getUsername();
        }
        catch (Exception ignored)
        {
        }
        // PostgreSQL 单事务：锁库 → 复核 DELETE → 目标须为成员 → 更新 owner → 旧 owner upsert EDITOR → 删新 owner ACL
        kbOwnershipTransactionService.transferOwner(kbId, newOwnerUserId, updateBy);
    }

    /**
     * 目标用户有效性校验（MySQL MASTER）：存在、未删除、启用。
     * 添加成员 / 改角色 / 转移负责人前必须调用。
     */
    private void requireActiveUser(Long userId)
    {
        if (userId == null)
        {
            throw new ServiceException("请指定用户", HttpStatus.BAD_REQUEST);
        }
        SysUser user;
        try
        {
            user = DataSourceScope.runOn(DataSourceType.MASTER,
                () -> sysUserMapper.selectUserById(userId));
        }
        catch (Exception e)
        {
            throw new ServiceException("校验用户失败: " + e.getMessage());
        }
        if (user == null || "2".equals(user.getDelFlag()))
        {
            throw new ServiceException("用户不存在", HttpStatus.BAD_REQUEST);
        }
        if (!"0".equals(user.getStatus()))
        {
            throw new ServiceException("用户已停用", HttpStatus.BAD_REQUEST);
        }
    }

    private static String normalizeVisibility(String v)
    {
        if (v == null)
        {
            return KbAccessPolicy.VIS_PRIVATE;
        }
        String u = v.trim().toUpperCase();
        return switch (u)
        {
            case "PRIVATE", "MEMBERS", "DEPT", "ORG" -> u;
            default -> KbAccessPolicy.VIS_PRIVATE;
        };
    }

    private static String normalizeRole(String role)
    {
        if (role == null || role.isBlank())
        {
            return KbAccessPolicy.ROLE_VIEWER;
        }
        String r = role.trim().toUpperCase();
        return switch (r)
        {
            case "VIEWER", "EDITOR", "QUALITY", "OWNER" -> r;
            default -> KbAccessPolicy.ROLE_VIEWER;
        };
    }

    @Override
    @Transactional
    public int deleteKbKnowledgeById(Long kbId)
    {
        kbAuthorizationService.requireKb(kbId, KbAccessAction.DELETE);
        purgeKbArtifacts(kbId);
        return kbKnowledgeMapper.deleteKbKnowledgeById(kbId);
    }

    @Override
    @Transactional
    public int deleteKbKnowledgeByIds(Long[] kbIds)
    {
        kbAuthorizationService.requireKbIds(kbIds, KbAccessAction.DELETE);
        if (kbIds != null)
        {
            for (Long kbId : kbIds)
            {
                purgeKbArtifacts(kbId);
            }
        }
        return kbKnowledgeMapper.deleteKbKnowledgeByIds(kbIds);
    }

    /**
     * 清图/切片/向量/会话关联。PG 上可选步骤失败必须回滚到保存点,
     * 否则事务进入 aborted,后面的软删会变成 25P02。
     * 会话关联在独立 MASTER 事务里删,不能跟 PG 连接混用。
     */
    private void purgeKbArtifacts(Long kbId)
    {
        try
        {
            kbGraphStore.deleteByKb(kbId);
        }
        catch (Exception e)
        {
            log.warn("删除知识库时图存储清理失败 kbId={}: {}", kbId, e.getMessage());
        }
        kbDocGraphMapper.deleteByKbId(kbId);
        optionalPgCleanup("graphRun", () -> kbGraphRunMapper.deleteByKbId(kbId));
        optionalPgCleanup("graphTextUnit", () -> {
            kbGraphTextUnitMapper.deleteMappingsByKbId(kbId);
            kbGraphTextUnitMapper.deleteByKbId(kbId);
        });
        optionalPgCleanup("graphCommunity", () -> {
            kbGraphCommunityMapper.deleteReportSourcesByKb(kbId);
            kbGraphCommunityMapper.deleteReportsByKb(kbId);
            kbGraphCommunityMapper.deleteAllEntityCommunitiesByKb(kbId);
            kbGraphCommunityMapper.deleteAllCommunitiesByKb(kbId);
        });
        kbChunkMapper.deleteByKbId(kbId);
        kbVectorStore.deleteByKb(kbId);
        if (aiChatSessionKbCleanup != null)
        {
            try
            {
                aiChatSessionKbCleanup.deleteByKbId(kbId);
            }
            catch (Exception e)
            {
                log.warn("删除知识库时会话关联清理失败 kbId={}: {}", kbId, e.getMessage());
            }
        }
    }

    private void optionalPgCleanup(String name, Runnable action)
    {
        Object savepoint = null;
        boolean inTx = TransactionSynchronizationManager.isActualTransactionActive();
        try
        {
            if (inTx)
            {
                savepoint = TransactionAspectSupport.currentTransactionStatus().createSavepoint();
            }
            action.run();
            if (savepoint != null)
            {
                TransactionAspectSupport.currentTransactionStatus().releaseSavepoint(savepoint);
            }
        }
        catch (Exception e)
        {
            if (savepoint != null)
            {
                try
                {
                    TransactionAspectSupport.currentTransactionStatus().rollbackToSavepoint(savepoint);
                }
                catch (Exception rollbackEx)
                {
                    log.warn("删除知识库时回滚保存点失败({}): {}", name, rollbackEx.getMessage());
                }
            }
            log.warn("删除知识库时 {} 失败: {}", name, e.getMessage());
        }
    }
}
