package com.ruoyi.system.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.ruoyi.system.ai.session.SessionAccessGuard;
import com.ruoyi.system.domain.AiChatSession;
import com.ruoyi.system.mapper.AiChatSessionMapper;
import com.ruoyi.system.service.IAiChatSessionService.ClientDeclareResult;

/**
 * 客户端工具声明的覆盖规则。
 *
 * <p>会话行 {@code ai_chat_session.client_tools} 只存得下<b>一份</b>清单,谁后声明谁说了算。
 * 线上因此出过事:desktop 一个 {@code defineClientTool} 都没调用(snapshot 恒为空),
 * 而它每次 {@code sessionPersisted} 都 declare 一次 —— 用户在 desktop 点开插件建的会话,
 * 插件那 15 个浏览器工具就被就地抹平,之后那个会话的模型再也看不到浏览器能力。
 *
 * <p>规则:空清单不许<b>跨端</b>清空已有清单;同端自己清空是合法的(工具下线)。
 */
class AiChatSessionDeclareClientTest
{
    private AiChatSessionServiceImpl service;
    private AiChatSessionMapper sessionMapper;
    private SessionAccessGuard guard;

    /** 插件声明的清单(规范化后长这样),用来当"已有非空清单"的底。 */
    private static final String EXT_TOOLS =
            "[{\"description\":\"读当前页正文\",\"inputSchema\":{\"properties\":{},\"type\":\"object\"},"
                    + "\"name\":\"getPageContent\"}]";

    @BeforeEach
    void setUp()
    {
        service = new AiChatSessionServiceImpl();
        sessionMapper = mock(AiChatSessionMapper.class);
        guard = mock(SessionAccessGuard.class);
        setField(service, "aiChatSessionMapper", sessionMapper);
        setField(service, "sessionGuard", guard);
    }

    /** 核心回归:desktop 的空清单不能抹掉 browser_ext 已声明的浏览器工具。 */
    @Test
    void emptyDeclare_fromOtherClient_doesNotWipeExistingTools()
    {
        stubSession("browser_ext", EXT_TOOLS, "0.1.0+ext");

        ClientDeclareResult result = service.declareClient(
                "s1", "desktop", "0.1.0+desktop", "[]", 100L, false);

        assertFalse(result.applied(), "跨端空声明不该落库");
        assertEquals("browser_ext", result.clientType(), "回报的仍是原有的端");
        verify(sessionMapper, never()).updateClientDeclare(any());
    }

    /** 同端自己把工具清空是合法的:工具确实下线了,不该被这道闸拦住。 */
    @Test
    void emptyDeclare_fromSameClient_isAllowed()
    {
        stubSession("browser_ext", EXT_TOOLS, "0.1.0+ext");

        ClientDeclareResult result = service.declareClient(
                "s1", "browser_ext", "0.1.0+ext-empty", "[]", 100L, false);

        assertTrue(result.applied(), "同端清空是正常的清单变更");
        ArgumentCaptor<AiChatSession> patch = ArgumentCaptor.forClass(AiChatSession.class);
        verify(sessionMapper).updateClientDeclare(patch.capture());
        assertEquals("[]", patch.getValue().getClientTools());
    }

    /** 会话本来就没有清单时,空声明照常写入(它建立的是 clientType 这条事实)。 */
    @Test
    void emptyDeclare_onSessionWithoutTools_stillApplies()
    {
        stubSession("desktop", null, null);

        ClientDeclareResult result = service.declareClient(
                "s1", "desktop", "0.1.0+desktop", "[]", 100L, false);

        assertTrue(result.applied());
        verify(sessionMapper).updateClientDeclare(any());
    }

    /** 非空清单跨端覆盖不受影响:那是真的换了个端在用,清单该跟着换。 */
    @Test
    void nonEmptyDeclare_fromOtherClient_overwritesNormally()
    {
        stubSession("desktop", "[]", "0.1.0+desktop");

        ClientDeclareResult result = service.declareClient(
                "s1", "browser_ext", "0.1.0+ext", EXT_TOOLS, 100L, false);

        assertTrue(result.applied());
        ArgumentCaptor<AiChatSession> patch = ArgumentCaptor.forClass(AiChatSession.class);
        verify(sessionMapper).updateClientDeclare(patch.capture());
        assertEquals("browser_ext", patch.getValue().getClientType());
        assertTrue(patch.getValue().getClientTools().contains("getPageContent"));
    }

    /** 版本相同仍旧幂等跳过(既有行为,这道闸不能把它顶掉)。 */
    @Test
    void sameVersion_staysIdempotent()
    {
        stubSession("browser_ext", EXT_TOOLS, "0.1.0+ext");

        ClientDeclareResult result = service.declareClient(
                "s1", "browser_ext", "0.1.0+ext", EXT_TOOLS, 100L, false);

        assertFalse(result.applied());
        verify(sessionMapper, never()).updateClientDeclare(any());
    }

    // ---------- helpers ----------

    private void stubSession(String clientType, String clientTools, String version)
    {
        AiChatSession session = new AiChatSession();
        session.setSessionId("s1");
        session.setClientType(clientType);
        session.setClientTools(clientTools);
        session.setClientToolsVer(version);
        when(guard.requireOwned(anyString(), anyLong(), anyBoolean())).thenReturn(session);
    }

    private static void setField(Object target, String name, Object value)
    {
        try
        {
            java.lang.reflect.Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        }
        catch (Exception e)
        {
            throw new IllegalStateException("注入字段失败: " + name, e);
        }
    }
}
