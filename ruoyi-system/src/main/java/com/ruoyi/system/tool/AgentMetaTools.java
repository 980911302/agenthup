package com.ruoyi.system.tool;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.domain.AiAgent;
import com.ruoyi.system.domain.AiAgentChild;
import com.ruoyi.system.domain.AiModel;
import com.ruoyi.system.domain.AiSkill;
import com.ruoyi.system.domain.AiTool;
import com.ruoyi.system.mapper.AiAgentMapper;
import com.ruoyi.system.mapper.AiModelMapper;
import com.ruoyi.system.mapper.AiSkillMapper;
import com.ruoyi.system.mapper.AiToolMapper;
import com.ruoyi.system.service.IAiAgentService;
import com.ruoyi.system.service.IAiSkillService;

/**
 * 智能体元数据工具
 *
 * 让 agent 能自助查询创建智能体所需的上下文(模型/工具/技能/已有agent),
 * 并能直接调用 createAgent 往数据库写入一条新智能体记录(含技能/工具/子agent关联)。
 *
 * 用 @Tool 注解,由 ToolConfig.builtinToolCallbackProvider 收录,
 * ToolSyncService 启动时同步到 ai_tool 表(tool_type=1)。
 *
 * @author ruoyi
 */
@Component
public class AgentMetaTools
{
    private static final Logger log = LoggerFactory.getLogger(AgentMetaTools.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private AiModelMapper aiModelMapper;

    @Autowired
    private AiToolMapper aiToolMapper;

    @Autowired
    private AiSkillMapper aiSkillMapper;

    @Autowired
    private AiAgentMapper aiAgentMapper;

    @Autowired
    private IAiAgentService aiAgentService;

    @Autowired
    private IAiSkillService aiSkillService;

    /**
     * 查询可用对话模型列表
     * <p>
     * 只返回 CHAT 类型且状态正常的模型,供创建智能体时选择 modelCode。
     */
    @Tool(description = "查询所有可用的对话模型列表,用于创建智能体时选择 modelCode。"
        + "返回 modelCode/displayName/modelType/contextWindow/maxOutputTokens/reasoningEnabled/visionEnabled")
    public String listAvailableModels()
    {
        try
        {
            AiModel query = new AiModel();
            query.setStatus("0");
            query.setModelType("CHAT");
            List<AiModel> list = aiModelMapper.selectAiModelList(query);

            List<Map<String, Object>> rows = new ArrayList<>();
            for (AiModel m : list)
            {
                Map<String, Object> item = new HashMap<>(8);
                item.put("modelId", m.getModelId());
                item.put("modelCode", m.getModelCode());
                item.put("displayName", m.getDisplayName());
                item.put("modelType", m.getModelType());
                item.put("contextWindow", m.getContextWindow());
                item.put("maxOutputTokens", m.getMaxOutputTokens());
                item.put("reasoningEnabled", m.getReasoningEnabled());
                item.put("visionEnabled", m.getVisionEnabled());
                rows.add(item);
            }
            return objectMapper.writeValueAsString(rows);
        }
        catch (Exception e)
        {
            log.warn("listAvailableModels 失败: {}", e.getMessage());
            return "查询模型列表失败: " + e.getMessage();
        }
    }

    /**
     * 查询可用工具列表
     * <p>
     * 返回状态正常的工具(内置 + MCP),供创建智能体时选择 toolIds。
     */
    @Tool(description = "查询所有可用的工具列表,用于创建智能体时选择 toolIds。"
        + "返回 toolId/toolCode/toolName/description/toolType(1内置 2MCP)/category")
    public String listAvailableTools()
    {
        try
        {
            AiTool query = new AiTool();
            query.setStatus("0");
            List<AiTool> list = aiToolMapper.selectAiToolList(query);

            List<Map<String, Object>> rows = new ArrayList<>();
            for (AiTool t : list)
            {
                Map<String, Object> item = new HashMap<>(8);
                item.put("toolId", t.getToolId());
                item.put("toolCode", t.getToolCode());
                item.put("toolName", t.getToolName());
                item.put("description", t.getDescription());
                item.put("toolType", t.getToolType());
                item.put("category", t.getCategory());
                rows.add(item);
            }
            return objectMapper.writeValueAsString(rows);
        }
        catch (Exception e)
        {
            log.warn("listAvailableTools 失败: {}", e.getMessage());
            return "查询工具列表失败: " + e.getMessage();
        }
    }

    /**
     * 查询可用技能列表
     * <p>
     * 全部可见可分配,不做权限过滤。不返回 promptTemplate 大字段以节省 token。
     */
    @Tool(description = "查询所有可用的技能列表,用于创建智能体时选择 skillIds。"
        + "全部技能均可见可分配。返回 skillId/skillCode/skillName/category/description")
    public String listAvailableSkills()
    {
        try
        {
            AiSkill query = new AiSkill();
            query.setStatus("0");
            List<AiSkill> list = aiSkillMapper.selectAiSkillList(query);

            List<Map<String, Object>> rows = new ArrayList<>();
            for (AiSkill s : list)
            {
                Map<String, Object> item = new HashMap<>(8);
                item.put("skillId", s.getSkillId());
                item.put("skillCode", s.getSkillCode());
                item.put("skillName", s.getSkillName());
                item.put("category", s.getCategory());
                item.put("description", s.getDescription());
                rows.add(item);
            }
            return objectMapper.writeValueAsString(rows);
        }
        catch (Exception e)
        {
            log.warn("listAvailableSkills 失败: {}", e.getMessage());
            return "查询技能列表失败: " + e.getMessage();
        }
    }

    /**
     * 查询可用智能体列表
     * <p>
     * 返回状态正常的智能体,供创建新智能体时选择挂载哪些子 agent。
     */
    @Tool(description = "查询所有可用的智能体列表,用于创建新智能体时选择挂载哪些子 agent(childAgentIds)。"
        + "返回 agentId/agentCode/agentName/agentDesc/modelCode")
    public String listAvailableAgents()
    {
        try
        {
            AiAgent query = new AiAgent();
            query.setStatus("0");
            List<AiAgent> list = aiAgentService.selectAiAgentList(query);

            List<Map<String, Object>> rows = new ArrayList<>();
            for (AiAgent a : list)
            {
                Map<String, Object> item = new HashMap<>(8);
                item.put("agentId", a.getAgentId());
                item.put("agentCode", a.getAgentCode());
                item.put("agentName", a.getAgentName());
                item.put("agentDesc", a.getAgentDesc());
                item.put("modelCode", a.getModelCode());
                rows.add(item);
            }
            return objectMapper.writeValueAsString(rows);
        }
        catch (Exception e)
        {
            log.warn("listAvailableAgents 失败: {}", e.getMessage());
            return "查询智能体列表失败: " + e.getMessage();
        }
    }

    /**
     * 创建新智能体(直接写入数据库)
     * <p>
     * agentCode 由系统自动生成(AGT+日期+序号),无需传入。
     * skillIds/toolIds/childAgentIds 为逗号分隔的 ID 字符串,可留空。
     * 例如 skillIds="101,102"。
     *
     * @param agentName      智能体名称(必填)
     * @param agentRole      系统提示词,定义智能体人格和能力(必填)
     * @param modelCode      绑定模型编码,从 listAvailableModels 获取(必填)
     * @param agentDesc      智能体描述(可选)
     * @param loadLocalDoc   是否加载本地文档(可选,0否 1是,默认0)
     * @param icon           智能体图标 emoji(可选)
     * @param theme          主题色索引 0-7(可选)
     * @param skillIds       绑定技能ID,逗号分隔,如 "101,102"(可选)
     * @param toolIds        绑定工具ID,逗号分隔,如 "1,2"(可选)
     * @param childAgentIds  挂载子智能体ID,逗号分隔,如 "3,4"(可选)
     * @return 成功返回 agentId 和 agentCode,失败返回错误信息
     */
    @Tool(description = "创建一个新的智能体并写入数据库(含技能/工具/子智能体关联)。"
        + "agentCode 由系统自动生成。建议先调用 listAvailableModels/listAvailableTools/listAvailableSkills/listAvailableAgents 获取可用 ID。"
        + "成功返回 agentId 和 agentCode,失败返回错误信息。")
    public String createAgent(
        @ToolParam(description = "智能体名称(必填)") String agentName,
        @ToolParam(description = "系统提示词,定义智能体人格和能力(必填)") String agentRole,
        @ToolParam(description = "绑定模型编码,从 listAvailableModels 获取(必填)") String modelCode,
        @ToolParam(description = "智能体描述(可选)", required = false) String agentDesc,
        @ToolParam(description = "是否加载本地文档(可选,0否 1是,默认0)", required = false) String loadLocalDoc,
        @ToolParam(description = "智能体图标 emoji(可选)", required = false) String icon,
        @ToolParam(description = "主题色索引 0-7(可选)", required = false) String theme,
        @ToolParam(description = "绑定技能ID,逗号分隔,如 \"101,102\"(可选)", required = false) String skillIds,
        @ToolParam(description = "绑定工具ID,逗号分隔,如 \"1,2\"(可选)", required = false) String toolIds,
        @ToolParam(description = "挂载子智能体ID,逗号分隔,如 \"3,4\"(可选)", required = false) String childAgentIds)
    {
        try
        {
            // 参数校验
            if (StringUtils.isEmpty(agentName))
            {
                return "创建失败: agentName 不能为空";
            }
            if (StringUtils.isEmpty(agentRole))
            {
                return "创建失败: agentRole 不能为空";
            }
            if (StringUtils.isEmpty(modelCode))
            {
                return "创建失败: modelCode 不能为空";
            }

            AiAgent agent = new AiAgent();
            agent.setAgentName(agentName.trim());
            agent.setAgentRole(agentRole);
            agent.setModelCode(modelCode.trim());
            if (StringUtils.isNotEmpty(agentDesc))
            {
                agent.setAgentDesc(agentDesc);
            }
            agent.setLoadLocalDoc(StringUtils.isEmpty(loadLocalDoc) ? "0" : loadLocalDoc.trim());
            if (StringUtils.isNotEmpty(icon))
            {
                agent.setIcon(icon.trim());
            }
            if (StringUtils.isNotEmpty(theme))
            {
                agent.setTheme(theme.trim());
            }
            agent.setStatus("0");
            // createBy 记真实操作者,后续 update/delete 据此校验归属权限
            // 取不到操作者身份时(非登录上下文)退化成 "agent",标识为程序创建
            String operator = OperatorContext.getUsername();
            agent.setCreateBy(operator != null ? operator : "agent");

            // 技能ID: 逗号分隔 -> Long[]
            Long[] skillIdArr = parseIdArray(skillIds);
            if (skillIdArr != null)
            {
                agent.setSkillIds(skillIdArr);
            }

            // 工具ID: 逗号分隔 -> Long[]
            Long[] toolIdArr = parseIdArray(toolIds);
            if (toolIdArr != null)
            {
                agent.setToolIds(toolIdArr);
            }

            // 子智能体ID: 逗号分隔 -> List<AiAgentChild>
            Long[] childIdArr = parseIdArray(childAgentIds);
            if (childIdArr != null)
            {
                List<AiAgentChild> children = new ArrayList<>();
                int sort = 1;
                for (Long childId : childIdArr)
                {
                    AiAgentChild child = new AiAgentChild();
                    child.setChildAgentId(childId);
                    child.setSort(sort++);
                    children.add(child);
                }
                agent.setChildAgents(children);
            }

            // insertAiAgent 已 @Transactional,会一并写入主表 + 三张关联表
            aiAgentService.insertAiAgent(agent);

            Map<String, Object> result = new HashMap<>(4);
            result.put("success", true);
            result.put("agentId", agent.getAgentId());
            result.put("agentCode", agent.getAgentCode());
            result.put("agentName", agent.getAgentName());
            return objectMapper.writeValueAsString(result);
        }
        catch (Exception e)
        {
            log.warn("createAgent 失败: {}", e.getMessage());
            return "创建智能体失败: " + e.getMessage();
        }
    }

    /**
     * 修改智能体(写库,含技能/工具/子智能体关联重建)
     * <p>
     * 权限校验:超级管理员可修改所有;其它用户只能修改自己 createBy 的智能体。
     * agentCode 不可改(系统唯一引用),modelCode 可换。
     * skillIds/toolIds/childAgentIds 不传时保留原有关联(null 跳过),
     * 传空串 "" 会清空对应关联(因 parseIdArray 返回 null,这里靠显式标志区分)。
     *
     * @param agentId       要修改的智能体ID(必填)
     * @param agentName     智能体名称(可选,不传不改)
     * @param agentRole     系统提示词(可选,不传不改)
     * @param modelCode     绑定模型编码(可选,不传不改)
     * @param agentDesc     智能体描述(可选)
     * @param loadLocalDoc  是否加载本地文档(可选,0否 1是)
     * @param icon          智能体图标 emoji(可选)
     * @param theme         主题色索引 0-7(可选)
     * @param skillIds      绑定技能ID,逗号分隔(可选,不传保留原有关联)
     * @param toolIds       绑定工具ID,逗号分隔(可选,不传保留原有关联)
     * @param childAgentIds 挂载子智能体ID,逗号分隔(可选,不传保留原有关联)
     * @return 成功返回更新后的信息,失败返回错误信息
     */
    @Tool(description = "修改已有智能体(含技能/工具/子智能体关联重建)。"
        + "权限:超管可改所有,其它用户只能改自己创建的。"
        + "不传的字段保持不变;skillIds/toolIds/childAgentIds 不传保留原有关联,传空串则清空。")
    public String updateAgent(
        @ToolParam(description = "要修改的智能体ID(必填)") Long agentId,
        @ToolParam(description = "智能体名称(可选,不传不改)", required = false) String agentName,
        @ToolParam(description = "系统提示词(可选,不传不改)", required = false) String agentRole,
        @ToolParam(description = "绑定模型编码(可选,不传不改)", required = false) String modelCode,
        @ToolParam(description = "智能体描述(可选)", required = false) String agentDesc,
        @ToolParam(description = "是否加载本地文档(可选,0否 1是)", required = false) String loadLocalDoc,
        @ToolParam(description = "智能体图标 emoji(可选)", required = false) String icon,
        @ToolParam(description = "主题色索引 0-7(可选)", required = false) String theme,
        @ToolParam(description = "绑定技能ID,逗号分隔(可选,不传保留原有,传空串清空)", required = false) String skillIds,
        @ToolParam(description = "绑定工具ID,逗号分隔(可选,不传保留原有,传空串清空)", required = false) String toolIds,
        @ToolParam(description = "挂载子智能体ID,逗号分隔(可选,不传保留原有,传空串清空)", required = false) String childAgentIds)
    {
        try
        {
            if (agentId == null)
            {
                return "修改失败: agentId 不能为空";
            }

            // 权限校验:先查现有 agent 拿 createBy,再比对操作者
            AiAgent existing = aiAgentService.selectAiAgentById(agentId);
            if (existing == null)
            {
                return "修改失败: 智能体不存在, agentId=" + agentId;
            }
            String deny = checkPermission(existing.getCreateBy());
            if (deny != null)
            {
                return deny;
            }

            // 构造更新对象:只填传入的字段,updateAiAgent 的 mapper 用 <if> 跳过 null
            AiAgent agent = new AiAgent();
            agent.setAgentId(agentId);
            if (StringUtils.isNotEmpty(agentName))
            {
                agent.setAgentName(agentName.trim());
            }
            if (StringUtils.isNotEmpty(agentRole))
            {
                agent.setAgentRole(agentRole);
            }
            if (StringUtils.isNotEmpty(modelCode))
            {
                agent.setModelCode(modelCode.trim());
            }
            if (agentDesc != null)
            {
                agent.setAgentDesc(agentDesc);
            }
            if (StringUtils.isNotEmpty(loadLocalDoc))
            {
                agent.setLoadLocalDoc(loadLocalDoc.trim());
            }
            if (icon != null)
            {
                agent.setIcon(icon.trim());
            }
            if (theme != null)
            {
                agent.setTheme(theme.trim());
            }
            String operator = OperatorContext.getUsername();
            agent.setUpdateBy(operator != null ? operator : "agent");

            // 关联表:updateAiAgent 内部先全删再重建,所以必须显式传值才改
            // 约定:skillIds/toolIds/childAgentIds 为 null(不传) -> 保留原有;为空串/非空 -> 重建
            if (skillIds != null)
            {
                agent.setSkillIds(parseIdArray(skillIds));
            }
            if (toolIds != null)
            {
                agent.setToolIds(parseIdArray(toolIds));
            }
            if (childAgentIds != null)
            {
                Long[] childIdArr = parseIdArray(childAgentIds);
                if (childIdArr != null)
                {
                    List<AiAgentChild> children = new ArrayList<>();
                    int sort = 1;
                    for (Long childId : childIdArr)
                    {
                        AiAgentChild child = new AiAgentChild();
                        child.setChildAgentId(childId);
                        child.setSort(sort++);
                        children.add(child);
                    }
                    agent.setChildAgents(children);
                }
                else
                {
                    // 空串 -> 清空关联
                    agent.setChildAgents(new ArrayList<>());
                }
            }

            // updateAiAgent 已 @Transactional:先删旧关联再插新关联 + 更新主表
            aiAgentService.updateAiAgent(agent);

            Map<String, Object> result = new HashMap<>(4);
            result.put("success", true);
            result.put("agentId", agentId);
            result.put("agentName", agent.getAgentName() != null ? agent.getAgentName() : existing.getAgentName());
            return objectMapper.writeValueAsString(result);
        }
        catch (Exception e)
        {
            log.warn("updateAgent 失败: {}", e.getMessage());
            return "修改智能体失败: " + e.getMessage();
        }
    }

    /**
     * 删除智能体(逻辑删除,同时清理技能/工具/子智能体关联)
     * <p>
     * 权限校验:超级管理员可删除所有;其它用户只能删除自己 createBy 的智能体。
     *
     * @param agentId 要删除的智能体ID(必填)
     * @return 成功返回确认信息,失败返回错误信息
     */
    @Tool(description = "删除智能体(逻辑删除,同时清理关联配置)。"
        + "权限:超管可删所有,其它用户只能删自己创建的。删除后不可恢复。")
    public String deleteAgent(
        @ToolParam(description = "要删除的智能体ID(必填)") Long agentId)
    {
        try
        {
            if (agentId == null)
            {
                return "删除失败: agentId 不能为空";
            }

            // 权限校验
            AiAgent existing = aiAgentService.selectAiAgentById(agentId);
            if (existing == null)
            {
                return "删除失败: 智能体不存在, agentId=" + agentId;
            }
            String deny = checkPermission(existing.getCreateBy());
            if (deny != null)
            {
                return deny;
            }

            // deleteAiAgentById 已 @Transactional:逻辑删主表 + 清理三张关联表
            aiAgentService.deleteAiAgentById(agentId);

            Map<String, Object> result = new HashMap<>(4);
            result.put("success", true);
            result.put("agentId", agentId);
            result.put("agentName", existing.getAgentName());
            result.put("agentCode", existing.getAgentCode());
            return objectMapper.writeValueAsString(result);
        }
        catch (Exception e)
        {
            log.warn("deleteAgent 失败: {}", e.getMessage());
            return "删除智能体失败: " + e.getMessage();
        }
    }

    // ==================== 技能管理 ====================

    /**
     * 创建技能(写库)
     * <p>
     * skillCode 由系统自动生成(SKL+日期+序号),无需传入。
     * 权限:createBy 记录当前操作者,后续修改/删除据此校验归属。
     *
     * @param skillName      技能名称(必填)
     * @param promptTemplate 提示词模板,支持 {var} 占位符(必填)
     * @param category       技能分类,如 写作/编程/分析(可选)
     * @param description    技能描述(可选)
     * @param sort           显示顺序(可选)
     * @return 成功返回 skillId 和 skillCode,失败返回错误信息
     */
    @Tool(description = "创建一个新的技能并写入数据库。skillCode 由系统自动生成。"
        + "技能 = 提示词模板,智能体可绑定使用。成功返回 skillId 和 skillCode。")
    public String createSkill(
        @ToolParam(description = "技能名称(必填)") String skillName,
        @ToolParam(description = "提示词模板,支持 {var} 占位符(必填)") String promptTemplate,
        @ToolParam(description = "技能分类,如 写作/编程/分析(可选)", required = false) String category,
        @ToolParam(description = "技能描述(可选)", required = false) String description,
        @ToolParam(description = "显示顺序(可选)", required = false) Integer sort)
    {
        try
        {
            if (StringUtils.isEmpty(skillName))
            {
                return "创建失败: skillName 不能为空";
            }
            if (StringUtils.isEmpty(promptTemplate))
            {
                return "创建失败: promptTemplate 不能为空";
            }

            AiSkill skill = new AiSkill();
            skill.setSkillName(skillName.trim());
            skill.setPromptTemplate(promptTemplate);
            if (StringUtils.isNotEmpty(category))
            {
                skill.setCategory(category.trim());
            }
            if (StringUtils.isNotEmpty(description))
            {
                skill.setDescription(description);
            }
            if (sort != null)
            {
                skill.setSort(sort);
            }
            skill.setStatus("0");
            // createBy 记真实操作者,后续 update/delete 据此校验归属权限
            String operator = OperatorContext.getUsername();
            skill.setCreateBy(operator != null ? operator : "agent");

            // insertAiSkill 会自动生成 skillCode(SKL+日期+序号)
            aiSkillService.insertAiSkill(skill);

            Map<String, Object> result = new HashMap<>(4);
            result.put("success", true);
            result.put("skillId", skill.getSkillId());
            result.put("skillCode", skill.getSkillCode());
            result.put("skillName", skill.getSkillName());
            return objectMapper.writeValueAsString(result);
        }
        catch (Exception e)
        {
            log.warn("createSkill 失败: {}", e.getMessage());
            return "创建技能失败: " + e.getMessage();
        }
    }

    /**
     * 修改技能(写库)
     * <p>
     * 权限校验:超级管理员可修改所有;其它用户只能修改自己 createBy 的技能。
     * skillCode 不可改(系统唯一引用)。
     *
     * @param skillId        要修改的技能ID(必填)
     * @param skillName      技能名称(可选,不传不改)
     * @param promptTemplate 提示词模板(可选,不传不改)
     * @param category       技能分类(可选)
     * @param description    技能描述(可选)
     * @param sort           显示顺序(可选)
     * @param status         技能状态(可选,0正常 1停用)
     * @return 成功返回更新后的信息,失败返回错误信息
     */
    @Tool(description = "修改已有技能。权限:超管可改所有,其它用户只能改自己创建的。"
        + "不传的字段保持不变。skillCode 不可改。")
    public String updateSkill(
        @ToolParam(description = "要修改的技能ID(必填)") Long skillId,
        @ToolParam(description = "技能名称(可选,不传不改)", required = false) String skillName,
        @ToolParam(description = "提示词模板,支持 {var} 占位符(可选,不传不改)", required = false) String promptTemplate,
        @ToolParam(description = "技能分类(可选)", required = false) String category,
        @ToolParam(description = "技能描述(可选)", required = false) String description,
        @ToolParam(description = "显示顺序(可选)", required = false) Integer sort,
        @ToolParam(description = "技能状态(可选,0正常 1停用)", required = false) String status)
    {
        try
        {
            if (skillId == null)
            {
                return "修改失败: skillId 不能为空";
            }

            // 权限校验
            AiSkill existing = aiSkillService.selectAiSkillById(skillId);
            if (existing == null)
            {
                return "修改失败: 技能不存在, skillId=" + skillId;
            }
            String deny = checkPermission(existing.getCreateBy());
            if (deny != null)
            {
                return deny;
            }

            // 构造更新对象:只填传入的字段,mapper 用 <if> 跳过 null
            AiSkill skill = new AiSkill();
            skill.setSkillId(skillId);
            if (StringUtils.isNotEmpty(skillName))
            {
                skill.setSkillName(skillName.trim());
            }
            if (StringUtils.isNotEmpty(promptTemplate))
            {
                skill.setPromptTemplate(promptTemplate);
            }
            if (StringUtils.isNotEmpty(category))
            {
                skill.setCategory(category.trim());
            }
            if (description != null)
            {
                skill.setDescription(description);
            }
            if (sort != null)
            {
                skill.setSort(sort);
            }
            if (StringUtils.isNotEmpty(status))
            {
                skill.setStatus(status.trim());
            }
            String operator = OperatorContext.getUsername();
            skill.setUpdateBy(operator != null ? operator : "agent");

            aiSkillService.updateAiSkill(skill);

            Map<String, Object> result = new HashMap<>(4);
            result.put("success", true);
            result.put("skillId", skillId);
            result.put("skillName", skill.getSkillName() != null ? skill.getSkillName() : existing.getSkillName());
            return objectMapper.writeValueAsString(result);
        }
        catch (Exception e)
        {
            log.warn("updateSkill 失败: {}", e.getMessage());
            return "修改技能失败: " + e.getMessage();
        }
    }

    /**
     * 删除技能(逻辑删除)
     * <p>
     * 权限校验:超级管理员可删除所有;其它用户只能删除自己 createBy 的技能。
     *
     * @param skillId 要删除的技能ID(必填)
     * @return 成功返回确认信息,失败返回错误信息
     */
    @Tool(description = "删除技能(逻辑删除)。权限:超管可删所有,其它用户只能删自己创建的。删除后不可恢复。")
    public String deleteSkill(
        @ToolParam(description = "要删除的技能ID(必填)") Long skillId)
    {
        try
        {
            if (skillId == null)
            {
                return "删除失败: skillId 不能为空";
            }

            // 权限校验
            AiSkill existing = aiSkillService.selectAiSkillById(skillId);
            if (existing == null)
            {
                return "删除失败: 技能不存在, skillId=" + skillId;
            }
            String deny = checkPermission(existing.getCreateBy());
            if (deny != null)
            {
                return deny;
            }

            aiSkillService.deleteAiSkillById(skillId);

            Map<String, Object> result = new HashMap<>(4);
            result.put("success", true);
            result.put("skillId", skillId);
            result.put("skillName", existing.getSkillName());
            result.put("skillCode", existing.getSkillCode());
            return objectMapper.writeValueAsString(result);
        }
        catch (Exception e)
        {
            log.warn("deleteSkill 失败: {}", e.getMessage());
            return "删除技能失败: " + e.getMessage();
        }
    }

    /**
     * 权限校验:当前操作者是否有权操作目标智能体。
     * <p>
     * 超级管理员(userId=1)放行;否则操作者用户名必须等于智能体的 createBy。
     * 取不到操作者身份(非登录上下文)直接拒绝。
     *
     * @param createBy 目标智能体的创建者用户名
     * @return null 表示通过;非 null 为拒绝原因(直接返回给 LLM)
     */
    private String checkPermission(String createBy)
    {
        String operator = OperatorContext.getUsername();
        if (operator == null)
        {
            return "操作失败: 无法识别当前操作者身份(未登录上下文),不允许修改/删除智能体";
        }
        if (OperatorContext.isAdmin())
        {
            // 超管放行
            return null;
        }
        if (!operator.equals(createBy))
        {
            return "操作失败: 权限不足。你只能操作自己创建的智能体(当前用户: "
                + operator + ", 该智能体创建者: " + (createBy != null ? createBy : "未知") + ")";
        }
        return null;
    }

    /**
     * 把逗号分隔的 ID 字符串解析为 Long 数组
     *
     * @param idStr 如 "101,102" 或 null/空
     * @return Long 数组,输入为空则返回 null
     */
    private Long[] parseIdArray(String idStr)
    {
        if (StringUtils.isEmpty(idStr))
        {
            return null;
        }
        String[] parts = idStr.split(",");
        List<Long> ids = new ArrayList<>();
        for (String part : parts)
        {
            String trimmed = part.trim();
            if (trimmed.isEmpty())
            {
                continue;
            }
            try
            {
                ids.add(Long.parseLong(trimmed));
            }
            catch (NumberFormatException e)
            {
                log.warn("ID 格式错误,已跳过: {}", trimmed);
            }
        }
        return ids.isEmpty() ? null : ids.toArray(new Long[0]);
    }
}
