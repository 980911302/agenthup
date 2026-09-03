package com.ruoyi.system.kb;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.system.domain.KbDocument;

/**
 * 文档路径安全解析与 downloadable 计算。
 */
class KbDocumentFileAccessTest
{
    @TempDir
    Path tempDir;

    private String previousProfile;

    @BeforeEach
    void setProfile()
    {
        previousProfile = RuoYiConfig.getProfile();
        // setProfile 为实例 setter，通过 Spring 配置写入 static 字段
        new RuoYiConfig().setProfile(tempDir.toAbsolutePath().toString());
    }

    @AfterEach
    void restoreProfile()
    {
        new RuoYiConfig().setProfile(previousProfile);
    }

    @Test
    void acceptsFileInsideKbDir() throws Exception
    {
        Long kbId = 12L;
        Path file = tempDir.resolve("upload/kb/12/2024/01/a.pdf");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "hello", StandardCharsets.UTF_8);

        String stored = "/profile/upload/kb/12/2024/01/a.pdf";
        Path resolved = KbDocumentFileAccess.resolveLocalFile(kbId, stored);
        assertNotNull(resolved);
        assertTrue(Files.isSameFile(file, resolved));
        assertTrue(KbDocumentFileAccess.isDownloadable(kbId, stored));
    }

    @Test
    void rejectsPathTraversal()
    {
        Long kbId = 12L;
        String evil = "/profile/upload/kb/12/../../../../etc/passwd";
        assertNull(KbDocumentFileAccess.resolveLocalFile(kbId, evil));
        assertFalse(KbDocumentFileAccess.isDownloadable(kbId, evil));
    }

    @Test
    void rejectsOtherKbDir() throws Exception
    {
        Path other = tempDir.resolve("upload/kb/99/secret.pdf");
        Files.createDirectories(other.getParent());
        Files.writeString(other, "x", StandardCharsets.UTF_8);

        // 声称属于 12，实际路径在 99
        String path = "/profile/upload/kb/99/secret.pdf";
        assertNull(KbDocumentFileAccess.resolveLocalFile(12L, path));
        assertFalse(KbDocumentFileAccess.isDownloadable(12L, path));
    }

    @Test
    void missingFileNotDownloadable()
    {
        String path = "/profile/upload/kb/12/missing.pdf";
        Path resolved = KbDocumentFileAccess.resolveLocalFile(12L, path);
        // 路径合法但文件不存在：resolve 可返回 path，isDownloadable 为 false
        if (resolved != null)
        {
            assertFalse(Files.exists(resolved));
        }
        assertFalse(KbDocumentFileAccess.isDownloadable(12L, path));
    }

    @Test
    void sanitizeClearsInternalFieldsAndSetsDownloadable() throws Exception
    {
        Path file = tempDir.resolve("upload/kb/5/f.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "ok", StandardCharsets.UTF_8);

        KbDocument doc = new KbDocument();
        doc.setKbId(5L);
        doc.setFilePath("/profile/upload/kb/5/f.txt");
        doc.setContentHash("abc");
        doc.setIrPath("/tmp/ir.json");

        KbDocumentFileAccess.sanitizeForUser(doc);
        assertTrue(Boolean.TRUE.equals(doc.getDownloadable()));
        assertNull(doc.getFilePath());
        assertNull(doc.getContentHash());
        assertNull(doc.getIrPath());
    }

    @Test
    void sanitizeMissingFileDownloadableFalse()
    {
        KbDocument doc = new KbDocument();
        doc.setKbId(5L);
        doc.setFilePath("/profile/upload/kb/5/gone.pdf");
        doc.setContentHash("x");
        KbDocumentFileAccess.sanitizeForUser(doc);
        assertFalse(Boolean.TRUE.equals(doc.getDownloadable()));
        assertNull(doc.getFilePath());
    }
}
