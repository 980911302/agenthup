package com.ruoyi.system.ai.event;

import java.util.List;
import java.util.Map;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.ai.session.SessionAccessGuard;
import com.ruoyi.system.domain.AiChatSpecialEvent;
import com.ruoyi.system.domain.AiChatSpecialEventSummary;
import com.ruoyi.system.mapper.AiChatSpecialEventMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpecialEventServiceTest
{
    @Test
    void missingMessageIdReturnsEmptyAndDoesNotQuery()
    {
        AiChatSpecialEventMapper mapper = mock(AiChatSpecialEventMapper.class);
        SessionAccessGuard guard = mock(SessionAccessGuard.class);
        SpecialEventService service = new SpecialEventService(mapper, guard);

        List<AiChatSpecialEvent> rows = service.payloadsAuthorized("s1", null, "kb.references", 8L, false);

        assertThat(rows).isEmpty();
        verify(guard).requireOwned("s1", 8L, false);
        verify(mapper, never()).selectPayloads(any(), any(), any());
    }

    @Test
    void unknownMessageIdReturnsEmpty()
    {
        AiChatSpecialEventMapper mapper = mock(AiChatSpecialEventMapper.class);
        SessionAccessGuard guard = mock(SessionAccessGuard.class);
        when(mapper.selectPayloads("s1", 99L, "kb.references")).thenReturn(List.of());
        SpecialEventService service = new SpecialEventService(mapper, guard);

        assertThat(service.payloadsAuthorized("s1", 99L, "kb.references", 8L, false)).isEmpty();
    }

    @Test
    void unauthorizedAccessIsRejected()
    {
        AiChatSpecialEventMapper mapper = mock(AiChatSpecialEventMapper.class);
        SessionAccessGuard guard = mock(SessionAccessGuard.class);
        when(guard.requireOwned(eq("s1"), eq(8L), eq(false)))
                .thenThrow(new ServiceException("会话不存在或无权访问"));
        SpecialEventService service = new SpecialEventService(mapper, guard);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.payloadsAuthorized("s1", 1L, "kb.references", 8L, false));
        assertThat(ex.getMessage()).contains("无权访问");
        verify(mapper, never()).selectPayloads(any(), any(), any());
    }

    @Test
    void summariesGroupByMessageAndName()
    {
        AiChatSpecialEventMapper mapper = mock(AiChatSpecialEventMapper.class);
        SessionAccessGuard guard = mock(SessionAccessGuard.class);
        AiChatSpecialEventSummary one = new AiChatSpecialEventSummary();
        one.setMessageId(11L);
        one.setName("kb.references");
        one.setFileCount(3);
        one.setChunkCount(15);
        when(mapper.selectSummariesBySessionId("s1")).thenReturn(List.of(one));
        SpecialEventService service = new SpecialEventService(mapper, guard);

        Map<String, List<Map<String, Object>>> grouped = service.summariesByMessage("s1");
        assertThat(grouped).containsOnlyKeys("11");
        assertThat(grouped.get("11")).singleElement()
                .satisfies(item -> {
                    assertThat(item.get("name")).isEqualTo("kb.references");
                    assertThat(item.get("fileCount")).isEqualTo(3);
                    assertThat(item.get("chunkCount")).isEqualTo(15);
                    assertThat(item.containsKey("count")).isFalse();
                });
    }

    @Test
    void workspaceSummaryKeepsLightweightFileMetadata()
    {
        AiChatSpecialEventMapper mapper = mock(AiChatSpecialEventMapper.class);
        SessionAccessGuard guard = mock(SessionAccessGuard.class);
        AiChatSpecialEventSummary row = new AiChatSpecialEventSummary();
        row.setMessageId(12L);
        row.setName("workspace.changes");
        row.setPayload("{\"fileCount\":1,\"createdCount\":1,\"modifiedCount\":0,"
                + "\"deletedCount\":0,\"files\":[{\"operation\":\"CREATE\","
                + "\"path\":\"reports/result.xlsx\",\"name\":\"result.xlsx\"}]}");
        when(mapper.selectSummariesBySessionId("s1")).thenReturn(List.of(row));

        Map<String, List<Map<String, Object>>> grouped =
                new SpecialEventService(mapper, guard).summariesByMessage("s1");

        assertThat(grouped.get("12")).singleElement().satisfies(item -> {
            assertThat(item.get("name")).isEqualTo("workspace.changes");
            assertThat(item.get("createdCount")).isEqualTo(1);
            assertThat((List<?>) item.get("files")).singleElement();
        });
    }
}
