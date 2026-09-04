package com.ruoyi.system.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ruoyi.common.exception.ServiceException;

class WorkspaceTreeWalkerTest
{
    @TempDir
    Path tmp;

    @Test
    void uploadSourceMapsToStableWorkspacePartitions()
    {
        assertEquals("uploads", WorkspaceTreeWalker.uploadDirectory(null));
        assertEquals("uploads", WorkspaceTreeWalker.uploadDirectory("user"));
        assertEquals("outputs", WorkspaceTreeWalker.uploadDirectory("ai"));
        assertThrows(ServiceException.class, () -> WorkspaceTreeWalker.uploadDirectory("other"));
    }

    @Test
    void topLevelPartitionsHaveUserFacingLabelsWithoutChangingPaths() throws Exception
    {
        Files.createDirectories(tmp.resolve("uploads"));
        Files.createDirectories(tmp.resolve("outputs"));
        Files.writeString(tmp.resolve("uploads/input.txt"), "in");
        Files.writeString(tmp.resolve("outputs/report.txt"), "out");

        WorkspaceTreeWalker.TreeResult tree = WorkspaceTreeWalker.walk(tmp);

        WorkspaceTreeWalker.Node uploads = tree.nodes.stream()
                .filter(n -> "uploads".equals(n.path)).findFirst().orElseThrow();
        WorkspaceTreeWalker.Node outputs = tree.nodes.stream()
                .filter(n -> "outputs".equals(n.path)).findFirst().orElseThrow();
        assertEquals("个人文件上传", uploads.displayName);
        assertEquals("AI 生成文件", outputs.displayName);
        assertEquals("uploads/input.txt", uploads.children.get(0).path);
        assertEquals("outputs/report.txt", outputs.children.get(0).path);
    }
}
