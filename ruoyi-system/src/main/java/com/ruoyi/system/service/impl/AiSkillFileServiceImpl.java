package com.ruoyi.system.service.impl;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.AiSkillFile;
import com.ruoyi.system.mapper.AiSkillFileMapper;
import com.ruoyi.system.service.IAiSkillFileService;
import com.ruoyi.system.tool.AiToolProperties;

/**
 * 技能附件实现。
 *
 * @author ruoyi
 */
@Service
public class AiSkillFileServiceImpl implements IAiSkillFileService
{
    private static final Logger log = LoggerFactory.getLogger(AiSkillFileServiceImpl.class);

    /** 会话沙箱内存放技能附件副本的目录名。点开头是为了不跟用户自己建的目录撞名。 */
    public static final String SESSION_SKILL_DIR = ".skills";

    /**
     * V1 只收纯文本参考文件。
     *
     * <p><b>刻意不收脚本</b>(.py/.sh/.js 等):公共技能带可执行脚本,等于让 A 用户写的代码
     * 跑在 B 用户的会话里,是实打实的权限升级面。要支持得先有审核机制,单独一期做。
     */
    private static final Set<String> ALLOWED_EXTENSIONS =
            Set.of("md", "txt", "json", "csv", "yaml", "yml", "xml", "sql");

    /** 单文件上限 1MB:参考文档正常都是几十 KB,超过这个量级该进知识库而不是技能附件。 */
    private static final long MAX_FILE_BYTES = 1024 * 1024L;

    /** 单技能附件数上限,防止把技能目录当网盘用。 */
    private static final int MAX_FILES_PER_SKILL = 50;

    @Autowired
    private AiSkillFileMapper skillFileMapper;

    @Autowired
    private AiToolProperties aiToolProperties;

    @Override
    public List<AiSkillFile> listBySkill(Long skillId)
    {
        return skillId == null ? List.of() : skillFileMapper.selectBySkillId(skillId);
    }

    @Override
    public AiSkillFile upload(Long skillId, MultipartFile file, String relPath, String summary, String createBy)
    {
        if (skillId == null)
        {
            throw new ServiceException("技能不存在");
        }
        if (file == null || file.isEmpty())
        {
            throw new ServiceException("上传文件为空");
        }
        if (file.getSize() > MAX_FILE_BYTES)
        {
            throw new ServiceException("单个技能附件不能超过 1MB,更大的内容请放知识库");
        }
        String targetPath = sanitizeRelPath(StringUtils.hasText(relPath)
                ? relPath : file.getOriginalFilename());
        requireAllowedExtension(targetPath);

        AiSkillFile existing = skillFileMapper.selectBySkillAndPath(skillId, targetPath);
        if (existing == null && skillFileMapper.selectBySkillId(skillId).size() >= MAX_FILES_PER_SKILL)
        {
            throw new ServiceException("单个技能最多 " + MAX_FILES_PER_SKILL + " 个附件");
        }

        Path dest = skillDir(skillId).resolve(targetPath);
        try
        {
            Files.createDirectories(dest.getParent());
            try (InputStream in = file.getInputStream())
            {
                Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        catch (IOException e)
        {
            throw new ServiceException("技能附件写入失败: " + e.getMessage());
        }

        AiSkillFile row = existing != null ? existing : new AiSkillFile();
        row.setSkillId(skillId);
        row.setRelPath(targetPath);
        row.setFileSize(file.getSize());
        row.setContentType(file.getContentType());
        if (StringUtils.hasText(summary)) row.setSummary(summary.trim());
        row.setCreateBy(createBy);
        row.setCreateTime(new Date());
        if (existing != null)
        {
            skillFileMapper.updateAiSkillFile(row);
        }
        else
        {
            skillFileMapper.insertAiSkillFile(row);
        }
        return row;
    }

    @Override
    public void delete(Long fileId)
    {
        AiSkillFile row = skillFileMapper.selectById(fileId);
        if (row == null)
        {
            return;
        }
        skillFileMapper.deleteById(fileId);
        try
        {
            Files.deleteIfExists(skillDir(row.getSkillId()).resolve(row.getRelPath()));
        }
        catch (Exception e)
        {
            // 元数据已经删了,残留文件不影响功能(不会再被列出/拷贝),下次同名上传会覆盖
            log.warn("技能附件落盘文件删除失败 fileId={} path={}: {}",
                    fileId, row.getRelPath(), e.getMessage());
        }
    }

    @Override
    public void deleteBySkillId(Long skillId)
    {
        if (skillId != null) skillFileMapper.deleteBySkillId(skillId);
    }

    @Override
    public int copyToSession(Long skillId, String skillCode, Path sessionRoot)
    {
        if (sessionRoot == null || skillId == null)
        {
            return 0;
        }
        List<AiSkillFile> files = skillFileMapper.selectBySkillId(skillId);
        if (files.isEmpty())
        {
            return 0;
        }
        Path targetDir = sessionRoot.resolve(SESSION_SKILL_DIR).resolve(safeDirName(skillCode, skillId));
        int copied = 0;
        for (AiSkillFile f : files)
        {
            Path src = skillDir(skillId).resolve(f.getRelPath());
            Path dest = targetDir.resolve(f.getRelPath());
            try
            {
                if (!Files.isRegularFile(src))
                {
                    // 元数据在、文件没了(手工清理过盘/换过卷):跳过而不是让整个 loadSkill 失败
                    log.warn("技能附件源文件缺失 skillId={} path={}", skillId, f.getRelPath());
                    continue;
                }
                Files.createDirectories(dest.getParent());
                Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                copied++;
            }
            catch (IOException e)
            {
                log.warn("技能附件拷贝失败 skillId={} path={}: {}", skillId, f.getRelPath(), e.getMessage());
            }
        }
        return copied;
    }

    @Override
    public String describeForPrompt(Long skillId, String skillCode)
    {
        List<AiSkillFile> files = skillId == null ? List.of() : skillFileMapper.selectBySkillId(skillId);
        if (files.isEmpty())
        {
            return "";
        }
        String base = SESSION_SKILL_DIR + "/" + safeDirName(skillCode, skillId) + "/";
        StringBuilder sb = new StringBuilder("\n\n本技能附带以下参考文件(已就位于会话工作区,需要时用 read 打开):\n");
        for (AiSkillFile f : files)
        {
            sb.append("- ").append(base).append(f.getRelPath());
            if (StringUtils.hasText(f.getSummary()))
            {
                sb.append(" — ").append(f.getSummary());
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** 技能附件的落盘目录:{skill-root}/{skillId}/ */
    private Path skillDir(Long skillId)
    {
        String root = aiToolProperties.getSkillRoot();
        Path base = StringUtils.hasText(root)
                ? Paths.get(root) : Paths.get(System.getProperty("user.dir"), "agent-java", "ai", "skills");
        return base.toAbsolutePath().normalize().resolve(String.valueOf(skillId));
    }

    /**
     * 相对路径消毒:只允许技能目录内的相对路径。
     *
     * <p>这条路径最终会拼进文件系统,并且拷贝时会拼进会话沙箱 —— 放过一个 {@code ..}
     * 就等于把两个目录同时开了口子,所以在入口一次性卡死,而不是指望下游校验。
     */
    static String sanitizeRelPath(String raw)
    {
        if (!StringUtils.hasText(raw))
        {
            throw new ServiceException("附件路径不能为空");
        }
        String p = raw.trim().replace('\\', '/');
        while (p.startsWith("/")) p = p.substring(1);
        if (p.isEmpty() || p.endsWith("/"))
        {
            throw new ServiceException("附件路径非法: " + raw);
        }
        for (String seg : p.split("/"))
        {
            if (seg.isEmpty() || ".".equals(seg) || "..".equals(seg))
            {
                throw new ServiceException("附件路径非法: " + raw);
            }
        }
        if (p.length() > 200)
        {
            throw new ServiceException("附件路径过长");
        }
        return p;
    }

    private static void requireAllowedExtension(String relPath)
    {
        int dot = relPath.lastIndexOf('.');
        String ext = dot >= 0 ? relPath.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        if (!ALLOWED_EXTENSIONS.contains(ext))
        {
            throw new ServiceException("技能附件暂时只支持文本参考文件("
                    + String.join("/", ALLOWED_EXTENSIONS.stream().sorted().toList()) + ")");
        }
    }

    /** 技能目录名:优先用 skillCode(可读),异常字符一律退回 skillId。 */
    static String safeDirName(String skillCode, Long skillId)
    {
        if (StringUtils.hasText(skillCode) && skillCode.matches("[A-Za-z0-9_-]{1,64}"))
        {
            return skillCode;
        }
        return String.valueOf(skillId);
    }
}
