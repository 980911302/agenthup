package com.ruoyi.system.ai.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ruoyi.system.domain.AiSkill;

/**
 * 轮内重复 {@code loadSkill} 只返一次全文。
 *
 * <p>模型在一轮里忘了自己加载过并不罕见,而技能正文动辄 1.5K+ token,又被
 * {@code ai.chat.context.clean.exclude-tools} 列为永不清理 —— 重复一次就永久多占一份。
 *
 * <p><b>只在轮内去重</b>:前提是「上文那份还在」。一个 run 内这点成立(清理层跳过它、
 * 兜底层也已改成优先保留含它的整轮);跨轮不保证,所以换个实例仍给全文。
 */
class SkillLoadDedupTest
{
    private static final long SKILL_ID = 42L;

    @Test
    void secondLoadInSameRun_returnsPointerNotFullText()
    {
        SkillLoadToolCallback cb = callback(null);

        String first = cb.call("{\"skillName\":\"写作技能\"}");
        String second = cb.call("{\"skillName\":\"写作技能\"}");

        assertTrue(first.contains("完整操作规则正文"), "首次必须给全文: " + first);
        assertFalse(second.contains("完整操作规则正文"), "同轮第二次不该再返全文: " + second);
        assertTrue(second.contains("已在上文加载"), "应提示去上文看: " + second);
    }

    /** 换实例 = 换一轮:跨轮那份可能已被压缩处理过,必须重新给全文。 */
    @Test
    void newRunInstance_givesFullTextAgain()
    {
        assertTrue(callback(null).call("{\"skillName\":\"写作技能\"}").contains("完整操作规则正文"));
        assertTrue(callback(null).call("{\"skillName\":\"写作技能\"}").contains("完整操作规则正文"));
    }

    /** 去重后仍要给附件路径:它短,而且是模型接下来真正要用的东西。 */
    @Test
    void dedupedResponse_stillCarriesAttachmentPaths()
    {
        SkillLoadToolCallback cb = callback((id, code) -> "\n附件: .skills/writing/REFERENCE.md");

        cb.call("{\"skillName\":\"写作技能\"}");
        String second = cb.call("{\"skillName\":\"写作技能\"}");

        assertTrue(second.contains(".skills/writing/REFERENCE.md"), "附件路径应保留: " + second);
    }

    /** 去重不改工具定义 —— 工具定义变了会作废整块缓存,这正是要避免的。 */
    @Test
    void toolDefinition_isUnaffectedByDedup()
    {
        SkillLoadToolCallback cb = callback(null);
        String before = cb.getToolDefinition().description();
        cb.call("{\"skillName\":\"写作技能\"}");
        cb.call("{\"skillName\":\"写作技能\"}");
        assertEquals(before, cb.getToolDefinition().description(), "工具描述必须逐字节不变");
    }

    private static SkillLoadToolCallback callback(SkillLoadToolCallback.SkillAttachmentPort port)
    {
        Map<Long, String> bound = new LinkedHashMap<>();
        bound.put(SKILL_ID, "写作技能");
        AiSkill skill = new AiSkill();
        skill.setSkillId(SKILL_ID);
        skill.setSkillCode("writing");
        skill.setSkillName("写作技能");
        skill.setStatus("0");
        skill.setPromptTemplate("写作技能的完整操作规则正文");
        return new SkillLoadToolCallback(bound, id -> SKILL_ID == id ? skill : null, port);
    }
}
