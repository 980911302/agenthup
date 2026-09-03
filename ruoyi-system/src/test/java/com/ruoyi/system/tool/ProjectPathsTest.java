package com.ruoyi.system.tool;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectPathsTest
{
    @TempDir
    Path tmp;

    @Test
    void relativePathResolvesAgainstConfiguredCwd()
    {
        AiToolProperties props = new AiToolProperties();
        props.setCwd(tmp.toString());
        Path resolved = ProjectPaths.resolve(props, "src/A.java");
        assertEquals(tmp.resolve("src/A.java").toAbsolutePath().normalize(), resolved);
    }

    @Test
    void boundSessionPutsRelativeFilesInSandbox()
    {
        AiToolProperties props = new AiToolProperties();
        props.setWorkspaceRoot(tmp.toString());
        props.setWorkspacePerSession(true);
        props.setCwd(tmp.resolve("should-not-use").toString());
        String prev = WorkspaceContext.bind("sess-workspace-1");
        try
        {
            Path resolved = ProjectPaths.resolve(props, "阿干正传.docx");
            assertEquals(tmp.resolve("sess-workspace-1").resolve("阿干正传.docx").toAbsolutePath().normalize(),
                    resolved);
            Path leaked = Path.of(System.getProperty("user.dir")).toAbsolutePath().resolve("阿干正传_故事关系梳理.docx");
            assertEquals(tmp.resolve("sess-workspace-1").resolve("阿干正传_故事关系梳理.docx").toAbsolutePath().normalize(),
                    ProjectPaths.resolve(props, leaked.toString()));
        }
        finally
        {
            WorkspaceContext.restore(prev);
        }
    }

    @Test
    void projectWorkspaceDoesNotReplaceConversationIdentity()
    {
        AiToolProperties props = new AiToolProperties();
        props.setWorkspaceRoot(tmp.toString());
        props.setWorkspacePerSession(true);
        String prevSession = WorkspaceContext.bind("session-1");
        String prevWorkspace = WorkspaceContext.bindWorkspaceKey("project-7");
        try
        {
            assertEquals("session-1", WorkspaceContext.getOrNull());
            assertEquals(tmp.resolve("project-7").toAbsolutePath().normalize(), ProjectPaths.cwd(props));
        }
        finally
        {
            WorkspaceContext.restoreWorkspaceKey(prevWorkspace);
            WorkspaceContext.restore(prevSession);
        }
    }
}
