package com.ruoyi.system.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.AiProject;
import com.ruoyi.system.mapper.AiChatSessionMapper;
import com.ruoyi.system.mapper.AiProjectMapper;

class WorkspaceScopeServiceTest
{
    @TempDir
    Path tmp;

    @Test
    void projectSessionsResolveToSameWorkspace()
    {
        AiChatSessionMapper sessions = mock(AiChatSessionMapper.class);
        AiProjectMapper projects = mock(AiProjectMapper.class);
        when(sessions.selectOwnedProjectIdBySessionId("session-a")).thenReturn(9L);
        when(sessions.selectOwnedProjectIdBySessionId("session-b")).thenReturn(9L);
        WorkspaceScopeService service = new WorkspaceScopeService(sessions, projects);

        assertEquals("project-9", service.resolveWorkspaceKey("session-a"));
        assertEquals("project-9", service.resolveWorkspaceKey("session-b"));
    }

    @Test
    void standaloneSessionKeepsItsOwnWorkspace()
    {
        AiChatSessionMapper sessions = mock(AiChatSessionMapper.class);
        AiProjectMapper projects = mock(AiProjectMapper.class);
        when(sessions.selectOwnedProjectIdBySessionId("session-a")).thenReturn(null);
        WorkspaceScopeService service = new WorkspaceScopeService(sessions, projects);

        assertEquals("session-a", service.resolveWorkspaceKey("session-a"));
    }

    @Test
    void projectWorkspaceRequiresOwnership()
    {
        AiChatSessionMapper sessions = mock(AiChatSessionMapper.class);
        AiProjectMapper projects = mock(AiProjectMapper.class);
        AiProject project = new AiProject();
        project.setProjectId(9L);
        project.setUserId(2L);
        when(projects.selectAiProjectById(9L)).thenReturn(project);
        WorkspaceScopeService service = new WorkspaceScopeService(sessions, projects);

        assertEquals("project-9", service.requireProjectWorkspaceKey(9L, 2L));
        assertThrows(ServiceException.class, () -> service.requireProjectWorkspaceKey(9L, 3L));
    }

    @Test
    void legacySessionFilesAreCopiedWithoutOverwritingConflicts() throws Exception
    {
        AiChatSessionMapper sessions = mock(AiChatSessionMapper.class);
        AiProjectMapper projects = mock(AiProjectMapper.class);
        when(sessions.selectOwnedProjectIdBySessionId("session-a")).thenReturn(9L);
        when(sessions.selectOwnedSessionIdsByProjectId(9L)).thenReturn(List.of("session-a", "session-b"));
        AiToolProperties props = new AiToolProperties();
        props.setWorkspaceRoot(tmp.toString());
        props.setWorkspacePerSession(true);
        Files.createDirectories(tmp.resolve("session-a"));
        Files.createDirectories(tmp.resolve("session-b"));
        Files.writeString(tmp.resolve("session-a/report.md"), "A");
        Files.writeString(tmp.resolve("session-b/report.md"), "B");
        Files.writeString(tmp.resolve("session-b/data.csv"), "1,2");

        Path root = new WorkspaceScopeService(sessions, projects).resolveRoot(props, "session-a", true);

        assertEquals("A", Files.readString(root.resolve("report.md")));
        assertEquals("B", Files.readString(root.resolve("_legacy/session-b/report.md")));
        assertEquals("1,2", Files.readString(root.resolve("data.csv")));
        assertTrue(Files.exists(tmp.resolve("session-a/report.md")), "迁移不能删除旧目录");
    }
}
