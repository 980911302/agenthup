package com.ruoyi.system.ai.agent;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.system.domain.AiSkill;
import com.ruoyi.system.service.IAiSkillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 按名字取技能完整正文({@code ai_skill.prompt_template})的工具。
 *
 * <p><b>为什么需要它</b>:系统提示词里只放技能的 {@code description}(一句「什么时候用」),
 * 不再内联 {@code promptTemplate} 全文 —— 后者动辄 1~2K token,五个技能就是 4.3K,
 * 全程常驻上下文且压缩不掉。改为按需加载后常驻成本降到十分之一,
 * 详细规则在模型判断需要时才取(渐进披露)。
 *
 * <p><b>只能取本智能体挂载的技能</b>:构造时传入该 agent 的 skillIds,
 * 取不在列表里的技能一律拒绝 —— 否则任意 agent 都能读到全库技能正文。
 *
 * <p>与 {@link ImageGenerationToolCallback} 同模式:装配期动态生成、不进 ai_tool 表、
 * 由 {@code RecordingToolCallback} 包装获得记账与事件流。
 *
 * @author ruoyi
 */
public class SkillLoadToolCallback implements ToolCallback
{
    private static final Logger log = LoggerFactory.getLogger(SkillLoadToolCallback.class);

    public static final String TOOL_NAME = "loadSkill";

    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "skillName": {
                  "type": "string",
                  "description": "技能名称,必须是系统提示词「技能指引」里列出的名字之一"
                }
              },
              "required": ["skillName"]
            }
            """;

    /**
     * 技能附件准备口子:拷贝附件进会话沙箱,并返回给模型看的清单文本。
     *
     * <p>做成函数式接口而不是直接注入 Service,是为了让本类保持「不依赖 Spring」——
     * 它由 {@code AgentContextFactory} 在装配期 new 出来,与 {@code boundSkills} /
     * {@code skillResolver} 同样是构造注入。
     *
     * <p><b>它只影响返回值,不影响工具定义</b>:清单进的是 tool 结果消息(追加在对话尾部),
     * 前缀一字节不动。附件多少、叫什么名字都不会碰缓存。
     */
    @FunctionalInterface
    public interface SkillAttachmentPort
    {
        /** @return 清单文本;无附件或准备失败时返回空串 */
        String prepare(Long skillId, String skillCode);
    }

    /** 该 agent 挂载的技能(id -> name),顺序与系统提示词里的列表一致 */
    private final Map<Long, String> boundSkills;
    /** 技能行解析器:装配方传 AgentAssemblyCache::skill,运行期 loadSkill 也吃到行缓存 */
    private final Function<Long, AiSkill> skillResolver;
    /** 附件准备口子;为 null 表示本次装配没接附件能力(子 agent / 单测),退化成只给正文 */
    private final SkillAttachmentPort attachmentPort;

    /**
     * 本轮已经加载过的技能。<b>实例即作用域</b>:本类由 {@code AgentContextFactory} 每轮装配时
     * new 出来,所以这个集合天然是 per-run 的,跨轮不会误判。
     *
     * <p><b>为什么只在轮内去重</b>:去重的前提是「上文那份正文还在」。一个 run 之内它一定在 ——
     * {@code ContextCleaner} 按 exclude-tools 跳过它,{@code ContextOverflowGuard} 也已改成
     * 优先保留含它的整轮。跨轮就不保证了(可能已被 {@code ContextCompactor} 处理过),
     * 所以跨轮仍然给全文。
     *
     * <p>省的是 miss 的绝对值:技能正文动辄 1.5K+ token,重复一次就白烧一次。
     * 这不影响缓存 —— 去重只改工具返回值(尾部追加),工具定义和前缀一字节不动。
     */
    private final java.util.Set<Long> loadedInRun = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public SkillLoadToolCallback(Map<Long, String> boundSkills, IAiSkillService aiSkillService)
    {
        this(boundSkills, aiSkillService::selectAiSkillById, null);
    }

    public SkillLoadToolCallback(Map<Long, String> boundSkills, Function<Long, AiSkill> skillResolver)
    {
        this(boundSkills, skillResolver, null);
    }

    public SkillLoadToolCallback(Map<Long, String> boundSkills, Function<Long, AiSkill> skillResolver,
                                 SkillAttachmentPort attachmentPort)
    {
        this.boundSkills = boundSkills;
        this.skillResolver = skillResolver;
        this.attachmentPort = attachmentPort;
    }

    @Override
    public ToolDefinition getToolDefinition()
    {
        // 把可选值直接写进 description:模型不必猜名字,也省掉一次试错往返
        String names = String.join("、", boundSkills.values());
        return DefaultToolDefinition.builder()
                .name(TOOL_NAME)
                .description("读取某个技能的完整操作规则。系统提示词里只给了技能的适用场景,"
                        + "当你判断本次任务要用到某个技能时,先调用本工具取回它的详细规则再动手。"
                        + "可取的技能:" + names)
                .inputSchema(INPUT_SCHEMA)
                .build();
    }

    @Override
    public String call(String toolInput)
    {
        return doCall(toolInput);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext)
    {
        return doCall(toolInput);
    }

    private String doCall(String toolInput)
    {
        String requested = parseSkillName(toolInput);
        if (requested == null || requested.isEmpty())
        {
            return "loadSkill 缺少 skillName 参数。可取的技能:" + String.join("、", boundSkills.values());
        }

        Long skillId = null;
        for (Map.Entry<Long, String> e : boundSkills.entrySet())
        {
            if (requested.equals(e.getValue()))
            {
                skillId = e.getKey();
                break;
            }
        }
        if (skillId == null)
        {
            // 越权与拼错走同一条路径:不暴露"存在但没挂给你"这个信息
            return "本智能体没有挂载名为「" + requested + "」的技能。可取的技能:"
                    + String.join("、", boundSkills.values());
        }

        try
        {
            AiSkill skill = skillResolver.apply(skillId);
            if (skill == null || !"0".equals(skill.getStatus()))
            {
                return "技能「" + requested + "」已停用或不存在";
            }
            String files = describeAttachments(skill);
            if (!loadedInRun.add(skillId))
            {
                // 本轮已经取过:正文就在上文,再返一遍纯属重复计费。
                // 附件路径仍然给 —— 它短,而且是模型接下来真正要用的东西。
                return "技能「" + requested + "」的完整规则已在上文加载,直接按上文执行,不必再取。" + files;
            }
            String template = skill.getPromptTemplate();
            if (template == null || template.isEmpty())
            {
                return files.isEmpty()
                        ? "技能「" + requested + "」没有配置操作规则,按你的常规判断处理即可"
                        : "技能「" + requested + "」没有配置正文规则,但带了参考文件:" + files;
            }
            return template + files;
        }
        catch (Exception e)
        {
            log.warn("loadSkill 读取技能失败 skillId={}: {}", skillId, e.getMessage());
            return "读取技能「" + requested + "」失败: " + e.getMessage();
        }
    }

    /** 入参可能是 {"skillName":"x"},也可能被模型直接给成裸字符串 */
    /**
     * 准备并描述技能附件。失败一律降级成「没有附件」——
     * 附件拿不到不该让整个 loadSkill 失败,正文规则才是主体。
     */
    private String describeAttachments(AiSkill skill)
    {
        if (attachmentPort == null || skill == null)
        {
            return "";
        }
        try
        {
            String text = attachmentPort.prepare(skill.getSkillId(), skill.getSkillCode());
            return text == null ? "" : text;
        }
        catch (Exception e)
        {
            log.warn("技能附件准备失败 skillId={}: {}", skill.getSkillId(), e.getMessage());
            return "";
        }
    }

    private static String parseSkillName(String toolInput)
    {
        if (toolInput == null || toolInput.isEmpty())
        {
            return null;
        }
        String trimmed = toolInput.trim();
        if (!trimmed.startsWith("{"))
        {
            return trimmed;
        }
        try
        {
            JSONObject obj = JSON.parseObject(trimmed);
            String name = obj.getString("skillName");
            return name != null ? name.trim() : null;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /** 供装配方按 agent 的 skillIds 构造有序的 id->name 映射 */
    public static Map<Long, String> collectBoundSkills(Long[] skillIds, IAiSkillService service)
    {
        return collectBoundSkills(skillIds, (Function<Long, AiSkill>) service::selectAiSkillById);
    }

    /** resolver 版:装配方传 AgentAssemblyCache::skill,同一轮内的重复解析全部命中缓存 */
    public static Map<Long, String> collectBoundSkills(Long[] skillIds, Function<Long, AiSkill> resolver)
    {
        Map<Long, String> map = new LinkedHashMap<>();
        if (skillIds == null)
        {
            return map;
        }
        List<Long> ids = new ArrayList<>(List.of(skillIds));
        for (Long id : ids)
        {
            AiSkill skill = resolver.apply(id);
            if (skill != null && "0".equals(skill.getStatus()))
            {
                map.put(id, skill.getSkillName());
            }
        }
        return map;
    }
}
