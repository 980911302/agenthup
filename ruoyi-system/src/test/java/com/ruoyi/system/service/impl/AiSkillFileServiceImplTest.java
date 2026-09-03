package com.ruoyi.system.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.AiSkillFile;
import com.ruoyi.system.mapper.AiSkillFileMapper;
import com.ruoyi.system.tool.AiToolProperties;

/**
 * 技能附件的两条红线:路径不能逃出技能目录,拷贝不能逃出会话沙箱。
 *
 * <p>relPath 最终会同时拼进技能资源根和会话沙箱两个位置,放过一个 {@code ..}
 * 等于一次开两个口子 —— 所以在入口一次性卡死,并用测试锁住。
 */
class AiSkillFileServiceImplTest
{
    /** 逃逸类路径一律拒绝,不做「清洗后放行」——清洗规则一复杂就会有绕过。 */
    @Test
    void sanitizeRelPath_rejectsEscapes()
    {
        assertThrows(ServiceException.class, () -> AiSkillFileServiceImpl.sanitizeRelPath("../etc/passwd"));
        assertThrows(ServiceException.class, () -> AiSkillFileServiceImpl.sanitizeRelPath("a/../../b.md"));
        assertThrows(ServiceException.class, () -> AiSkillFileServiceImpl.sanitizeRelPath("..\\..\\b.md"));
        assertThrows(ServiceException.class, () -> AiSkillFileServiceImpl.sanitizeRelPath("./x.md"));
        assertThrows(ServiceException.class, () -> AiSkillFileServiceImpl.sanitizeRelPath("a//b.md"));
        assertThrows(ServiceException.class, () -> AiSkillFileServiceImpl.sanitizeRelPath("dir/"));
        assertThrows(ServiceException.class, () -> AiSkillFileServiceImpl.sanitizeRelPath("   "));
        assertThrows(ServiceException.class, () -> AiSkillFileServiceImpl.sanitizeRelPath(null));
    }

    /** 正常路径保留层级;前导斜杠剥掉后当相对路径,反斜杠统一成正斜杠。 */
    @Test
    void sanitizeRelPath_keepsNormalPaths()
    {
        assertEquals("REFERENCE.md", AiSkillFileServiceImpl.sanitizeRelPath("REFERENCE.md"));
        assertEquals("docs/api.md", AiSkillFileServiceImpl.sanitizeRelPath("docs/api.md"));
        assertEquals("docs/api.md", AiSkillFileServiceImpl.sanitizeRelPath("/docs/api.md"));
        assertEquals("docs/api.md", AiSkillFileServiceImpl.sanitizeRelPath("docs\\api.md"));
    }

    /** 技能目录名带异常字符时退回 skillId,不能让 skillCode 成为拼路径的注入点。 */
    @Test
    void safeDirName_fallsBackToIdOnUnsafeCode()
    {
        assertEquals("writing", AiSkillFileServiceImpl.safeDirName("writing", 7L));
        assertEquals("7", AiSkillFileServiceImpl.safeDirName("../evil", 7L));
        assertEquals("7", AiSkillFileServiceImpl.safeDirName("有中文", 7L));
        assertEquals("7", AiSkillFileServiceImpl.safeDirName(null, 7L));
        assertEquals("7", AiSkillFileServiceImpl.safeDirName("", 7L));
    }

    /** 拷贝落在 {sessionRoot}/.skills/{code}/ 下,清单只给路径不给内容。 */
    @Test
    void copyToSession_landsInsideSandbox_andManifestListsPathsOnly() throws Exception
    {
        @SuppressWarnings("unused")
        Path unused = null;
        Path base = Files.createTempDirectory("skill-test");
        Path skillRoot = base.resolve("skills");
        Path sessionRoot = base.resolve("workspace/sess-1");
        Files.createDirectories(sessionRoot);

        Path src = skillRoot.resolve("42").resolve("REFERENCE.md");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "详细字段说明正文");

        AiSkillFileMapper mapper = mock(AiSkillFileMapper.class);
        AiSkillFile row = new AiSkillFile();
        row.setFileId(1L);
        row.setSkillId(42L);
        row.setRelPath("REFERENCE.md");
        row.setSummary("详细字段说明");
        when(mapper.selectBySkillId(42L)).thenReturn(List.of(row));

        AiToolProperties props = new AiToolProperties();
        props.setSkillRoot(skillRoot.toString());
        AiSkillFileServiceImpl service = new AiSkillFileServiceImpl();
        setField(service, "skillFileMapper", mapper);
        setField(service, "aiToolProperties", props);

        assertEquals(1, service.copyToSession(42L, "writing", sessionRoot));
        Path copied = sessionRoot.resolve(".skills/writing/REFERENCE.md");
        assertTrue(Files.isRegularFile(copied), "附件应拷进会话沙箱: " + copied);
        assertTrue(copied.toAbsolutePath().normalize()
                .startsWith(sessionRoot.toAbsolutePath().normalize()), "拷贝目标不得逃出沙箱");

        String manifest = service.describeForPrompt(42L, "writing");
        assertTrue(manifest.contains(".skills/writing/REFERENCE.md"), "清单应给路径: " + manifest);
        assertTrue(manifest.contains("详细字段说明"), "清单应带一句话说明: " + manifest);
        assertFalse(manifest.contains("详细字段说明正文"),
                "清单绝不能内联文件内容,否则渐进披露就白做了: " + manifest);
    }

    /** 没有附件时清单为空串,不该给模型一段「本技能附带以下文件」然后底下什么都没有。 */
    @Test
    void describeForPrompt_noFiles_returnsEmpty()
    {
        AiSkillFileMapper mapper = mock(AiSkillFileMapper.class);
        when(mapper.selectBySkillId(42L)).thenReturn(List.of());
        AiSkillFileServiceImpl service = new AiSkillFileServiceImpl();
        setField(service, "skillFileMapper", mapper);
        assertEquals("", service.describeForPrompt(42L, "writing"));
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
            throw new AssertionError(e);
        }
    }
}
