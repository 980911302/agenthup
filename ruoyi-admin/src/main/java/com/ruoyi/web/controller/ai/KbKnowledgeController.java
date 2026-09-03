package com.ruoyi.web.controller.ai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.domain.SysConfig;
import com.ruoyi.system.service.ISysConfigService;
import com.ruoyi.system.domain.AiModel;
import com.ruoyi.system.domain.KbKnowledge;
import com.ruoyi.system.domain.KbDocGraph;
import com.ruoyi.system.kb.graph.KbGraphExploreService;
import com.ruoyi.system.kb.access.KbAccessAction;
import com.ruoyi.system.kb.access.KbAuthorizationService;
import com.ruoyi.system.kb.search.KbSearchMode;
import com.ruoyi.system.kb.search.KbSearchModePolicy;
import com.ruoyi.system.kb.search.KbSearchQualityGate;
import com.ruoyi.system.kb.search.KbSearchRequestNormalizer;
import com.ruoyi.system.kb.ops.KbOpsService;
import com.ruoyi.system.kb.policy.KbIndexPolicyService;
import com.ruoyi.system.kb.search.KbSearchService;
import com.ruoyi.system.kb.vector.KbSearchHit;
import com.ruoyi.system.domain.vo.KbAccessVO;
import com.ruoyi.system.domain.vo.DesktopViews;
import com.ruoyi.system.service.IAiModelService;
import com.ruoyi.system.service.IKbDocGraphService;
import com.ruoyi.system.service.IKbKnowledgeService;
import com.ruoyi.web.controller.ai.dto.DesktopKbCreateRequest;
/**
 * 知识库 信息操作处理
 *
 * @author ruoyi
 */
@RestController
@RequestMapping("/ai/kb")
public class KbKnowledgeController extends BaseController
{
    /** 全局设置的 sys_config 键前缀,见 sql/kb_settings.sql */
    private static final String KB_SETTING_PREFIX = "kb.default.";

    private static final String[] KB_SETTING_KEYS = {
        KB_SETTING_PREFIX + "embeddingModel",
        KB_SETTING_PREFIX + "extractModel",
        KB_SETTING_PREFIX + "chunkStrategy",
        KB_SETTING_PREFIX + "chunkSize",
        KB_SETTING_PREFIX + "chunkOverlap",
        KB_SETTING_PREFIX + "graphEnabled"
    };

    @Autowired
    private ISysConfigService configService;

    @Autowired
    private IKbKnowledgeService kbKnowledgeService;

    @Autowired
    private IAiModelService aiModelService;

    @Autowired
    private KbSearchService kbSearchService;

    @Autowired
    private IKbDocGraphService kbDocGraphService;

    @Autowired(required = false)
    private KbSearchModePolicy searchModePolicy;

    @Autowired(required = false)
    private KbSearchQualityGate searchQualityGate;

    @Autowired
    private KbAuthorizationService kbAuthorizationService;

    @Autowired
    private KbGraphExploreService kbGraphExploreService;

    @Autowired
    private KbIndexPolicyService kbIndexPolicyService;

    @Autowired
    private KbOpsService kbOpsService;

    /**
     * 查询知识库列表
     */
    @GetMapping("/list")
    @PreAuthorize("@ss.hasPermi('ai:kb:list')")
    public TableDataInfo list(KbKnowledge kbKnowledge)
    {
        startPage();
        List<KbKnowledge> list = kbKnowledgeService.selectKbKnowledgeList(kbKnowledge);
        return getDataTable(list);
    }

    /** desktop 知识库选择器：只返回可选择与展示所需字段。 */
    @GetMapping("/options")
    public AjaxResult options(KbKnowledge kbKnowledge)
    {
        List<KbKnowledge> list = kbKnowledgeService.selectKbKnowledgeList(kbKnowledge);
        return success(list.stream().map(DesktopViews::knowledgeOption).toList());
    }

    /** desktop 设置/面板使用的知识库清单，复用 options 的最小字段模型。 */
    @GetMapping("/desktop")
    public AjaxResult desktop(KbKnowledge kbKnowledge)
    {
        return options(kbKnowledge);
    }

    /**
     * 知识资产工作台（聚合文档/状态，禁止前端 N+1）
     */
    @GetMapping("/workbench")
    public AjaxResult workbench(KbKnowledge query)
    {
        return success(kbKnowledgeService.selectWorkbench(query));
    }

    /**
     * 删除影响预览
     */
    @GetMapping("/{kbId}/delete-impact")
    public AjaxResult deleteImpact(@PathVariable Long kbId)
    {
        return success(kbKnowledgeService.deleteImpactPreview(kbId));
    }

    /**
     * 当前用户对该库的 capability（不包含成员/智能体详情）
     */
    @GetMapping("/{kbId}/access")
    public AjaxResult access(@PathVariable Long kbId)
    {
        KbAccessVO access = kbAuthorizationService.resolveAccess(kbId);
        return success(access);
    }

    /**
     * 使用情况：可见范围、成员
     */
    @GetMapping("/{kbId}/usage")
    public AjaxResult usage(@PathVariable Long kbId)
    {
        return success(kbKnowledgeService.selectUsage(kbId));
    }

    /**
     * 成员候选搜索（MANAGE）。不调用 startPage()；分页在 Service MASTER 内完成。
     */
    @GetMapping("/{kbId}/member-candidates")
    public TableDataInfo memberCandidates(@PathVariable Long kbId,
                                          @RequestParam String keyword,
                                          @RequestParam(defaultValue = "1") int pageNum,
                                          @RequestParam(defaultValue = "20") int pageSize)
    {
        Map<String, Object> data = kbKnowledgeService.searchMemberCandidates(kbId, keyword, pageNum, pageSize);
        @SuppressWarnings("unchecked")
        List<?> rows = data == null || data.get("rows") == null
            ? List.of() : (List<?>) data.get("rows");
        long total = 0L;
        if (data != null && data.get("total") instanceof Number n)
        {
            total = n.longValue();
        }
        TableDataInfo rsp = new TableDataInfo(rows, total);
        rsp.setCode(com.ruoyi.common.constant.HttpStatus.SUCCESS);
        rsp.setMsg("查询成功");
        return rsp;
    }

    /**
     * 添加/更新成员
     */
    @Log(title = "知识库成员", businessType = BusinessType.UPDATE)
    @PostMapping("/{kbId}/members")
    public AjaxResult upsertMember(@PathVariable Long kbId, @RequestBody Map<String, Object> body)
    {
        Long userId = body == null || body.get("userId") == null ? null
            : Long.valueOf(body.get("userId").toString());
        String role = body == null ? null : String.valueOf(body.getOrDefault("role", "VIEWER"));
        kbKnowledgeService.upsertMember(kbId, userId, role);
        return success();
    }

    /**
     * 移除成员
     */
    @Log(title = "知识库成员", businessType = BusinessType.DELETE)
    @DeleteMapping("/{kbId}/members/{userId}")
    public AjaxResult removeMember(@PathVariable Long kbId, @PathVariable Long userId)
    {
        kbKnowledgeService.removeMember(kbId, userId);
        return success();
    }

    /**
     * 转移负责人
     */
    @Log(title = "知识库转移负责人", businessType = BusinessType.UPDATE)
    @PostMapping("/{kbId}/transfer-owner")
    public AjaxResult transferOwner(@PathVariable Long kbId, @RequestBody Map<String, Object> body)
    {
        Long newOwner = body == null || body.get("userId") == null ? null
            : Long.valueOf(body.get("userId").toString());
        kbKnowledgeService.transferOwner(kbId, newOwner);
        return success();
    }

    /**
     * 新增知识库。
     * <p>平台权限码 ai:kb:add；名称 @Valid 校验；缺省 visibility 由 service 固定为 PRIVATE。
     */
    @PreAuthorize("@ss.hasPermi('ai:kb:add')")
    @Log(title = "知识库管理", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@Valid @RequestBody KbKnowledge kbKnowledge)
    {
        // 名称空白（含纯空格）统一业务 code 400；@NotBlank 已覆盖 null/空串
        if (kbKnowledge.getKbName() != null && kbKnowledge.getKbName().isBlank())
        {
            throw new ServiceException("知识库名称不能为空", HttpStatus.BAD_REQUEST);
        }
        kbKnowledge.setCreateBy(getUsername());
        int n = kbKnowledgeService.insertKbKnowledge(kbKnowledge);
        if (n <= 0)
        {
            return error("创建失败");
        }
        // 返回 kbId 供工作台向导继续上传/跳转
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("kbId", kbKnowledge.getKbId());
        data.put("kbName", kbKnowledge.getKbName());
        return success(data);
    }

    /**
     * desktop 创建知识库。仅接收名称和描述；归属、可见范围及知识引擎参数均由服务端决定。
     */
    @PreAuthorize("@ss.hasPermi('ai:kb:add')")
    @Log(title = "知识库管理", businessType = BusinessType.INSERT)
    @PostMapping("/desktop")
    public AjaxResult addDesktop(@Valid @RequestBody DesktopKbCreateRequest request)
    {
        KbKnowledge kbKnowledge = new KbKnowledge();
        kbKnowledge.setKbName(request.kbName().trim());
        kbKnowledge.setDescription(request.description() == null ? null : request.description().trim());
        kbKnowledge.setCreateBy(getUsername());
        int n = kbKnowledgeService.insertKbKnowledge(kbKnowledge);
        if (n <= 0)
        {
            return error("创建失败");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("kbId", kbKnowledge.getKbId());
        data.put("kbName", kbKnowledge.getKbName());
        return success(data);
    }

    /**
     * 修改知识库（局部更新：未传名称不强制必填；传入名称时单独校验非空与长度）。
     */
    @Log(title = "知识库管理", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody KbKnowledge kbKnowledge)
    {
        if (kbKnowledge != null && kbKnowledge.getKbName() != null)
        {
            String name = kbKnowledge.getKbName().trim();
            if (name.isEmpty())
            {
                throw new ServiceException("知识库名称不能为空", HttpStatus.BAD_REQUEST);
            }
            if (name.length() > 100)
            {
                throw new ServiceException("知识库名称不能超过100个字符", HttpStatus.BAD_REQUEST);
            }
            kbKnowledge.setKbName(name);
        }
        kbKnowledge.setUpdateBy(getUsername());
        return toAjax(kbKnowledgeService.updateKbKnowledge(kbKnowledge));
    }

    /**
     * 删除知识库
     */
    @Log(title = "知识库管理", businessType = BusinessType.DELETE)
    @DeleteMapping("/{kbIds}")
    public AjaxResult remove(@PathVariable Long[] kbIds)
    {
        return toAjax(kbKnowledgeService.deleteKbKnowledgeByIds(kbIds));
    }

    /**
     * 检索测试。USE 可调；非 MANAGE 强制 mode=auto、debug=false，忽略高级参数并剥离 trace。
     */
    @PostMapping("/{kbId}/search")
    public AjaxResult search(@PathVariable Long kbId, @RequestBody Map<String, Object> body)
    {
        kbAuthorizationService.requireKb(kbId, KbAccessAction.USE);
        var access = kbAuthorizationService.resolveAccess(kbId);
        boolean canManage = access != null && access.getCanManage();

        String query = body != null && body.get("query") != null ? String.valueOf(body.get("query")) : "";
        if (StringUtils.isEmpty(query))
        {
            throw new ServiceException("query 不能为空", com.ruoyi.common.constant.HttpStatus.BAD_REQUEST);
        }

        KbSearchRequestNormalizer.Params params = KbSearchRequestNormalizer.normalize(canManage, body);
        int topK = params.topK();
        double minScore = params.minScore();
        KbSearchMode mode = params.mode();
        boolean debug = params.debug();

        long t0 = System.currentTimeMillis();
        List<KbSearchHit> hits = kbSearchService.search(List.of(kbId), query, topK, minScore, mode, debug);
        long took = System.currentTimeMillis() - t0;

        if (!canManage && hits != null)
        {
            for (KbSearchHit h : hits)
            {
                if (h != null)
                {
                    h.setDebugTrace(null);
                }
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("hits", hits);
        data.put("took", took);
        data.put("total", hits != null ? hits.size() : 0);
        data.put("mode", canManage ? mode.name() : "auto");
        data.put("debug", debug);
        if (canManage && searchModePolicy != null)
        {
            data.put("enabledModes", searchModePolicy.enabledSet().stream()
                .map(Enum::name).sorted().toList());
            data.put("defaultMode", searchModePolicy.defaultMode().name());
        }
        return success(data);
    }

    /**
     * 受控子图探索（节点/边上限，禁止全库加载）
     */
    @PostMapping("/{kbId}/graph/explore")
    public AjaxResult graphExplore(@PathVariable Long kbId, @RequestBody(required = false) Map<String, Object> body)
    {
        kbAuthorizationService.requireKb(kbId, KbAccessAction.READ);
        return success(kbGraphExploreService.explore(kbId, body));
    }

    /**
     * 实体详情（产品字段 + 来源证据 + 关系）
     */
    @GetMapping("/{kbId}/graph/entity")
    public AjaxResult graphEntityDetail(@PathVariable Long kbId, @RequestParam String name)
    {
        kbAuthorizationService.requireKb(kbId, KbAccessAction.READ);
        return success(kbGraphExploreService.entityDetail(kbId, name));
    }

    /**
     * 关系详情 + 来源证据
     */
    @GetMapping("/{kbId}/graph/relation")
    public AjaxResult graphRelationDetail(@PathVariable Long kbId,
                                          @RequestParam String source,
                                          @RequestParam String target,
                                          @RequestParam(required = false) String label)
    {
        kbAuthorizationService.requireKb(kbId, KbAccessAction.READ);
        return success(kbGraphExploreService.relationDetail(kbId, source, target, label));
    }

    /**
     * 文档图谱抽取状态列表
     */
    @GetMapping("/{kbId}/graph/docs")
    public AjaxResult graphDocs(@PathVariable Long kbId)
    {
        // 文件列表要展示图谱进度，有读权限即可（不必库管理员）
        kbAuthorizationService.requireKb(kbId, KbAccessAction.READ);
        // 经 service 切 SLAVE(PG);勿直接 mapper 否则落到 MySQL agent-java
        List<KbDocGraph> list = kbDocGraphService.selectByKbId(kbId);
        return success(list == null ? List.of() : list.stream().map(DesktopViews::documentGraph).toList());
    }

    /**
     * 别名：知识引擎策略（与 /settings 同源）。仅平台管理员。
     */
    @GetMapping("/engine")
    public AjaxResult getEngine()
    {
        requirePlatformAdmin();
        return success(loadEnginePolicy());
    }

    private Map<String, Object> loadEnginePolicy()
    {
        Map<String, Object> data = new LinkedHashMap<>();
        for (String key : KB_SETTING_KEYS)
        {
            data.put(key.substring(KB_SETTING_PREFIX.length()), configService.selectConfigByKey(key));
        }
        data.put("policyLabel", "平台知识引擎");
        data.put("immutableNote", "修改工作草稿不会静默重建已有库；发布新版本后旧库显示可升级。");
        try
        {
            Map<String, Object> st = kbIndexPolicyService.status();
            data.put("versionStatus", st);
        }
        catch (Exception e)
        {
            data.put("versionStatus", Map.of("note", e.getMessage()));
        }
        return data;
    }

    /** 发布前检查（仅平台管理员） */
    @PostMapping("/engine/precheck")
    public AjaxResult enginePrecheck(@RequestBody(required = false) Map<String, Object> body)
    {
        requirePlatformAdmin();
        Map<String, Object> payload = body == null || body.isEmpty()
            ? kbIndexPolicyService.loadWorkingDraftFromConfig()
            : body;
        return success(kbIndexPolicyService.prePublishCheck(payload));
    }

    /** 发布不可变策略版本（不修改已有库 active） */
    @Log(title = "知识引擎发布版本", businessType = BusinessType.UPDATE)
    @PostMapping("/engine/publish")
    public AjaxResult enginePublish(@RequestBody(required = false) Map<String, Object> body)
    {
        requirePlatformAdmin();
        // 若 body 带策略字段则先校验并写入 sys_config，再发布
        if (body != null && body.get("embeddingModel") != null)
        {
            AjaxResult saved = saveSettings(body);
            if (saved != null && saved.isError())
            {
                return saved;
            }
        }
        return success(kbIndexPolicyService.publish(body, getUsername()));
    }

    /**
     * 平台知识库运行观测：检索指标、门禁、策略任务、依赖。
     */
    @GetMapping("/engine/ops")
    public AjaxResult engineOps()
    {
        requirePlatformAdmin();
        return success(kbOpsService.platformOps());
    }

    /**
     * 保存平台知识引擎策略(内部:enginePublish/saveEngine 共用;无独立 HTTP 入口)。
     */
    private AjaxResult saveSettings(Map<String, Object> body)
    {
        requirePlatformAdmin();
        int chunkSize = parseInt(body.get("chunkSize"), 800);
        int chunkOverlap = parseInt(body.get("chunkOverlap"), 100);
        if (chunkSize < 100 || chunkSize > 4000)
        {
            return error("分块大小需在 100~4000 之间");
        }
        if (chunkOverlap < 0 || chunkOverlap > 500)
        {
            return error("分块重叠需在 0~500 之间");
        }
        // 重叠不小于分块时会切出无限循环/全量重复的块,必须拦在保存前
        if (chunkOverlap >= chunkSize)
        {
            return error("分块重叠必须小于分块大小");
        }
        String strategy = StringUtils.trimToEmpty(String.valueOf(body.getOrDefault("chunkStrategy", "P")));
        if (!"P".equals(strategy) && !"F".equals(strategy))
        {
            return error("分块策略只能是 P 或 F");
        }

        String embeddingModel = trimOrEmpty(body.get("embeddingModel"));
        String extractModel = trimOrEmpty(body.get("extractModel"));
        AjaxResult bad = checkModel(embeddingModel, "EMBEDDING", "向量模型");
        if (bad != null)
        {
            return bad;
        }
        bad = checkModel(extractModel, "CHAT", "实体抽取模型");
        if (bad != null)
        {
            return bad;
        }

        Map<String, String> values = new LinkedHashMap<>();
        values.put("embeddingModel", embeddingModel);
        values.put("extractModel", extractModel);
        values.put("chunkStrategy", strategy);
        values.put("chunkSize", String.valueOf(chunkSize));
        values.put("chunkOverlap", String.valueOf(chunkOverlap));
        values.put("graphEnabled", "1".equals(trimOrEmpty(body.get("graphEnabled"))) ? "1" : "0");

        for (Map.Entry<String, String> e : values.entrySet())
        {
            updateSetting(KB_SETTING_PREFIX + e.getKey(), e.getValue());
        }
        return success();
    }

    /** 知识引擎策略保存别名 */
    @Log(title = "知识引擎策略", businessType = BusinessType.UPDATE)
    @PutMapping("/engine")
    public AjaxResult saveEngine(@RequestBody Map<String, Object> body)
    {
        return saveSettings(body);
    }


    /**
     * 模型校验:允许留空(建库后手动选),但填了就必须存在、类型匹配且未停用 ——
     * 选了个停用的模型,新建出来的知识库直接是坏的。
     *
     * @return 校验不通过时返回错误结果,通过返回 null
     */
    private AjaxResult checkModel(String modelCode, String expectType, String label)
    {
        if (StringUtils.isEmpty(modelCode))
        {
            return null;
        }
        AiModel model = aiModelService.selectByModelCode(modelCode);
        if (model == null)
        {
            return error(label + "不存在: " + modelCode);
        }
        if (!expectType.equals(model.getModelType()))
        {
            return error(label + "类型应为 " + expectType + ",当前为 " + model.getModelType());
        }
        if (!"0".equals(model.getStatus()))
        {
            return error(label + "「" + modelCode + "」已停用,请换一个");
        }
        return null;
    }

    /** 存在则更新,不存在则新建 —— DDL 未执行时也不至于静默丢配置。 */
    private void updateSetting(String key, String value)
    {
        SysConfig exist = new SysConfig();
        exist.setConfigKey(key);
        List<SysConfig> list = configService.selectConfigList(exist);
        SysConfig target = null;
        for (SysConfig c : list)
        {
            if (key.equals(c.getConfigKey()))
            {
                target = c;
                break;
            }
        }
        if (target == null)
        {
            SysConfig add = new SysConfig();
            add.setConfigName("知识库-" + key);
            add.setConfigKey(key);
            add.setConfigValue(value);
            add.setConfigType("N");
            add.setCreateBy(getUsername());
            configService.insertConfig(add);
            return;
        }
        target.setConfigValue(value);
        target.setUpdateBy(getUsername());
        // updateConfig 内部会刷新 Redis 缓存,不能绕过它直接写 mapper
        configService.updateConfig(target);
    }

    private static String trimOrEmpty(Object v)
    {
        return v == null ? "" : String.valueOf(v).trim();
    }

    /**
     * 平台超级管理员（仅 {@link com.ruoyi.common.utils.SecurityUtils#isAdmin()}，禁止角色字符串猜测）。
     * 失败时 body code 403。
     */
    private static void requirePlatformAdmin()
    {
        try
        {
            if (com.ruoyi.common.utils.SecurityUtils.isAdmin())
            {
                return;
            }
        }
        catch (Exception ignored)
        {
        }
        throw new ServiceException("仅平台管理员可访问", com.ruoyi.common.constant.HttpStatus.FORBIDDEN);
    }

    private static int parseInt(Object v, int def)
    {
        if (v == null)
        {
            return def;
        }
        try
        {
            return Integer.parseInt(String.valueOf(v));
        }
        catch (NumberFormatException e)
        {
            return def;
        }
    }

    private static double parseDouble(Object v, double def)
    {
        if (v == null)
        {
            return def;
        }
        try
        {
            return Double.parseDouble(String.valueOf(v));
        }
        catch (NumberFormatException e)
        {
            return def;
        }
    }
}
