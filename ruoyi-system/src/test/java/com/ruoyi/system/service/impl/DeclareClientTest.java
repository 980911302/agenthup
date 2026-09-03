package com.ruoyi.system.service.impl;

import com.ruoyi.system.ai.memory.ChatMessageMapperTestSupport;
import com.ruoyi.system.ai.session.SessionAccessGuard;
import com.ruoyi.system.domain.AiChatSession;
import com.ruoyi.system.mapper.AiChatSessionMapper;
import com.ruoyi.system.service.IAiChatSessionService.ClientDeclareResult;
import com.ruoyi.system.tool.channel.ChannelToolProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeclareClientTest
{
    private AiChatSessionMapper mapper;
    private SessionAccessGuard guard;
    private AiChatSessionServiceImpl service;

    @BeforeEach
    void setUp() throws Exception
    {
        mapper = mock(AiChatSessionMapper.class);
        guard = mock(SessionAccessGuard.class);
        service = new AiChatSessionServiceImpl();
        ChatMessageMapperTestSupport.setField(service, "aiChatSessionMapper", mapper);
        ChatMessageMapperTestSupport.setField(service, "sessionGuard", guard);
        ChannelToolProperties props = new ChannelToolProperties();
        props.overrideForTest(true, java.util.List.of(), 120, 16, 32);
        ChatMessageMapperTestSupport.setField(service, "channelToolProperties", props);
    }

    @Test
    void sameVersion_doesNotWrite()
    {
        AiChatSession session = new AiChatSession();
        session.setSessionId("s1");
        session.setClientType("desktop");
        session.setClientToolsVer("v1");
        when(guard.requireOwned("s1", 1L, false)).thenReturn(session);

        ClientDeclareResult r = service.declareClient("s1", "desktop", "v1",
                "[{\"name\":\"ping\",\"description\":\"p\",\"parameters\":{}}]", 1L, false);
        assertFalse(r.applied());
        verify(mapper, never()).updateClientDeclare(any());
    }

    @Test
    void versionChange_writesCanonicalJson()
    {
        AiChatSession session = new AiChatSession();
        session.setSessionId("s1");
        session.setClientType("desktop");
        session.setClientToolsVer("old");
        when(guard.requireOwned("s1", 1L, false)).thenReturn(session);
        when(mapper.updateClientDeclare(any())).thenReturn(1);

        ClientDeclareResult r = service.declareClient("s1", "desktop", "new",
                "[{\"name\":\"zeta\",\"description\":\"z\",\"parameters\":{}},{\"name\":\"alpha\",\"description\":\"a\",\"parameters\":{}}]",
                1L, false);
        assertTrue(r.applied());
        ArgumentCaptor<AiChatSession> cap = ArgumentCaptor.forClass(AiChatSession.class);
        verify(mapper).updateClientDeclare(cap.capture());
        assertEquals("new", cap.getValue().getClientToolsVer());
        assertTrue(cap.getValue().getClientTools().indexOf("alpha")
                < cap.getValue().getClientTools().indexOf("zeta"));
    }

    @Test
    void illegalItems_goToSkipped()
    {
        AiChatSession session = new AiChatSession();
        session.setSessionId("s1");
        when(guard.requireOwned("s1", 1L, false)).thenReturn(session);
        when(mapper.updateClientDeclare(any())).thenReturn(1);

        ClientDeclareResult r = service.declareClient("s1", "desktop", "v2",
                "[{\"name\":\"good\",\"description\":\"ok\",\"parameters\":{}},{\"name\":\"1bad\",\"description\":\"x\",\"parameters\":{}}]",
                1L, false);
        assertTrue(r.applied());
        assertTrue(r.skipped().contains("1bad"));
    }

    @Test
    void clientTypeChange_isLoggedAndStored()
    {
        AiChatSession session = new AiChatSession();
        session.setSessionId("s1");
        session.setClientType("desktop");
        session.setClientToolsVer("old");
        when(guard.requireOwned("s1", 1L, false)).thenReturn(session);
        when(mapper.updateClientDeclare(any())).thenReturn(1);

        ClientDeclareResult r = service.declareClient("s1", "api", "v2", "[]", 1L, false);
        assertTrue(r.applied());
        assertEquals("api", r.clientType());
        ArgumentCaptor<AiChatSession> cap = ArgumentCaptor.forClass(AiChatSession.class);
        verify(mapper).updateClientDeclare(cap.capture());
        assertEquals("api", cap.getValue().getClientType());
    }
}
