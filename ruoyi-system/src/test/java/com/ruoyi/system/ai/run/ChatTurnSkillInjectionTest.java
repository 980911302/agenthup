package com.ruoyi.system.ai.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import com.ruoyi.system.ai.agent.AgentAssemblyCache;
import com.ruoyi.system.ai.agent.AgentContext;
import com.ruoyi.system.ai.agent.AgentContextFactory;
import com.ruoyi.system.ai.agent.AgentContextFactory.OperatorHolder;
import com.ruoyi.system.ai.memory.ChatMessageRecorder;
import com.ruoyi.system.domain.AiAgent;
import com.ruoyi.system.domain.AiSkill;
import com.ruoyi.system.service.IAiAgentService;
import com.ruoyi.system.service.IAiModelService;
import com.ruoyi.system.service.IAiSkillService;
import com.ruoyi.system.service.IAiToolService;

/**
 * 「本轮 @ 技能」的落位红线:@ 进来的技能<b>只能</b>出现在当轮 user 消息里,
 * 绝不能进系统提示词的技能目录。
 *
 * <p><b>为什么必须用测试锁住</b>:技能目录排在 system prompt 第 2 段,而请求前缀是
 * tools/system/messages 连成的一条串 —— 目录里多一行,从那个位置往后(剩余 system 段 +
 * 全部历史 + 整个 tools 数组)全部落空,且用户每 @ 一次、每取消一次就再废一遍。
 * 这种损失在 {@code ai_llm_call} 上只表现为 miss 变大,定位不到是谁干的,
 * 靠人工回归发现不了 —— 与 {@code ChatTurnMessageOrderTest} 同属缓存前缀的地基。
 *
 * <p>同时锁住与长期记忆一致的落库红线:注入只进发送版,{@code messageRecorder.insert}
 * 落的仍是用户原话 —— 否则技能正文会沉进历史,用户把 @ 粘着聊 20 轮就攒 20 份。
 */
class ChatTurnSkillInjectionTest
{
    private static final long AGENT_ID = 7L;
    /** 智能体自带技能:进目录、进 loadSkill */
    private static final long BOUND_SKILL = 21L;
    /** 用户本轮 @ 进来的技能:只进当轮 user 消息 */
    private static final long AT_SKILL = 99L;

    private IAiAgentService agentService;
    private IAiSkillService skillService;
    private AgentContextFactory factory;
    private ChatTurnRunner runner;
    private ChatMessageRecorder messageRecorder;

    @BeforeEach
    void setUp() throws Exception
    {
        agentService = mock(IAiAgentService.class);
        skillService = mock(IAiSkillService.class);
        IAiToolService toolService = mock(IAiToolService.class);
        AgentAssemblyCache cache = new AgentAssemblyCache(agentService, toolService, skillService,
                mock(IAiModelService.class));

        factory = new AgentContextFactory();
        setField(factory, "aiToolService", toolService);
        setField(factory, "aiSkillService", skillService);
        setField(factory, "assemblyCache", cache);
        // @Value 字段在裸 new 出来的实例上是 0,不设会让所有技能都被判超限跳过
        setField(factory, "turnSkillMaxChars", 20000);

        AiAgent agent = new AiAgent();
        agent.setAgentId(AGENT_ID);
        agent.setSkillIds(new Long[] { BOUND_SKILL });
        when(agentService.selectAiAgentById(AGENT_ID)).thenReturn(agent);
        when(skillService.selectAiSkillById(BOUND_SKILL))
                .thenReturn(skill(BOUND_SKILL, "自带技能", "0", "自带技能的详细操作规则正文"));
        when(skillService.selectAiSkillById(AT_SKILL))
                .thenReturn(skill(AT_SKILL, "临时技能", "0", "临时技能的详细操作规则正文"));

        runner = new ChatTurnRunner();
        messageRecorder = mock(ChatMessageRecorder.class);
        setField(runner, "chatMemory", new EmptyChatMemory());
        setField(runner, "messageRecorder", messageRecorder);
        setField(runner, "agentContextFactory", factory);
    }

    /** 核心不变量:@ 的技能进当轮注入段,而系统提示词的技能目录里没有它。 */
    @Test
    void atMentionedSkill_entersTurnSectionButNeverTheCatalog()
    {
        Long[] effective = new Long[] { BOUND_SKILL, AT_SKILL };

        String catalog = factory.buildSkillSection(new Long[] { BOUND_SKILL });
        String turnSection = factory.buildTurnSkillSection(AGENT_ID, effective, "sess-1");

        assertTrue(catalog.contains("自带技能"), "目录应含智能体自带技能: " + catalog);
        assertFalse(catalog.contains("临时技能"),
                "@ 的技能绝不能进系统提示词目录,否则前缀逐轮分叉: " + catalog);
        assertTrue(turnSection.contains("临时技能的详细操作规则正文"),
                "@ 的技能应给出全文正文: " + turnSection);
        assertFalse(turnSection.contains("自带技能的详细操作规则正文"),
                "已在目录里的技能不必重复注入(loadSkill 能取): " + turnSection);
    }

    /** 只 @ 了智能体自带的技能时不产生任何注入 —— 一个字节都不该多。 */
    @Test
    void onlyBoundSkillsSelected_producesNoInjection()
    {
        assertEquals("", factory.buildTurnSkillSection(AGENT_ID, new Long[] { BOUND_SKILL }, "sess-1"));
        assertEquals("", factory.buildTurnSkillSection(AGENT_ID, new Long[0], "sess-1"));
        assertEquals("", factory.buildTurnSkillSection(AGENT_ID, null, "sess-1"));
    }

    /** 停用的技能与没配正文的技能都跳过,不产生空壳注入段。 */
    @Test
    void disabledOrEmptyTemplateSkills_areSkipped()
    {
        when(skillService.selectAiSkillById(101L)).thenReturn(skill(101L, "已停用", "1", "正文"));
        when(skillService.selectAiSkillById(102L)).thenReturn(skill(102L, "无正文", "0", null));

        assertEquals("", factory.buildTurnSkillSection(AGENT_ID, new Long[] { 101L, 102L }, "sess-1"));
    }

    /** 落库红线:注入只进发送版,messageRecorder 收到的仍是用户原话。 */
    @Test
    void injection_goesToModelOnly_recordKeepsOriginal()
    {
        when(messageRecorder.insert(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any())).thenReturn(7L);
        ChatTurnRequest request = new ChatTurnRequest(
                "run-1", "sess-1", AGENT_ID, "帮我写篇文案", List.of(),
                new OperatorHolder("u", 100L, false), null, new Long[] { AT_SKILL });

        List<Message> messages = runner.buildInitialMessagesForRun(
                context(), "帮我写篇文案", null, request).messages();

        UserMessage sent = (UserMessage) messages.get(messages.size() - 1);
        assertTrue(sent.getText().contains("临时技能的详细操作规则正文"), "发送版必须带技能正文");
        assertTrue(sent.getText().endsWith("帮我写篇文案"),
                "注入在前、用户原话在后(与长期记忆同序): " + sent.getText());
        verify(messageRecorder).insert(any(), any(), any(), eq("USER"), eq("帮我写篇文案"),
                any(), any(), any(), any(), any(), any());
    }

    private AgentContext context()
    {
        return new AgentContext(AGENT_ID, "code", null, null, List.of(), "系统提示词",
                "sess-1:" + AGENT_ID, 100L, null, 0);
    }

    private static AiSkill skill(Long id, String name, String status, String template)
    {
        AiSkill skill = new AiSkill();
        skill.setSkillId(id);
        skill.setSkillName(name);
        skill.setDescription(name + " 的适用场景");
        skill.setStatus(status);
        skill.setPromptTemplate(template);
        return skill;
    }

    private static void setField(Object target, String name, Object value) throws Exception
    {
        java.lang.reflect.Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    /** 空历史的假记忆,避免依赖数据库 */
    private static final class EmptyChatMemory implements ChatMemory
    {
        @Override
        public void add(String conversationId, List<Message> messages)
        {
        }

        @Override
        public List<Message> get(String conversationId)
        {
            return new ArrayList<>();
        }

        @Override
        public void clear(String conversationId)
        {
        }
    }
}
