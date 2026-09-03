package com.ruoyi.system.ai.userfile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import com.ruoyi.ai.contract.storage.ObjectReadHandle;
import com.ruoyi.ai.contract.storage.ObjectStorage;
import com.ruoyi.ai.contract.storage.StoredObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.AiUserFile;
import com.ruoyi.system.mapper.AiUserFileMapper;
import com.ruoyi.system.tool.AiToolProperties;
import com.ruoyi.system.tool.WorkspaceSandbox;
import com.ruoyi.system.tool.WorkspaceScopeService;

/**
 * 用户个人文件服务实现。
 *
 * @author ruoyi
 */
@Service
public class AiUserFileServiceImpl implements IAiUserFileService
{
    private static final Logger log = LoggerFactory.getLogger(AiUserFileServiceImpl.class);

    /** 会话工作区里存放用户上传的子目录,与 AiChatWorkspaceController.UPLOAD_DIR 保持一致。 */
    private static final String UPLOAD_DIR = "uploads";

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");

    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private final AiUserFileMapper userFileMapper;
    private final AiUserFileProperties properties;
    private final AiToolProperties toolProperties;
    private final WorkspaceScopeService workspaceScopeService;
    /**
     * 用 ObjectProvider 而不是直接注入:没配对象存储的开发机与 CI 里这个 bean 不存在,
     * 硬注入会让整个应用起不来,而个人文件只是众多模块之一。
     */
    private final ObjectProvider<ObjectStorage> storageProvider;

    public AiUserFileServiceImpl(AiUserFileMapper userFileMapper,
                                 AiUserFileProperties properties,
                                 AiToolProperties toolProperties,
                                 WorkspaceScopeService workspaceScopeService,
                                 ObjectProvider<ObjectStorage> storageProvider)
    {
        this.userFileMapper = userFileMapper;
        this.properties = properties;
        this.toolProperties = toolProperties;
        this.workspaceScopeService = workspaceScopeService;
        this.storageProvider = storageProvider;
    }

    @Override
    public boolean storageAvailable()
    {
        return storageProvider.getIfAvailable() != null;
    }

    @Override
    public List<AiUserFile> list(Long userId, String keyword, String orderBy)
    {
        AiUserFile query = new AiUserFile();
        query.setUserId(requireUser(userId));
        query.setKeyword(StringUtils.hasText(keyword) ? keyword.trim() : null);
        query.setSortMode(normalizeOrderBy(orderBy));
        return userFileMapper.selectByUser(query);
    }

    @Override
    public UserFileQuota quota(Long userId)
    {
        Long used = userFileMapper.sumSizeByUser(requireUser(userId));
        return new UserFileQuota(used == null ? 0 : used, properties.getUserQuotaBytes(),
                userFileMapper.countByUser(userId), properties.getMaxFilesPerUser());
    }

    @Override
    public AiUserFile get(Long userId, Long fileId)
    {
        if (fileId == null) return null;
        return userFileMapper.selectByIdAndUser(fileId, requireUser(userId));
    }

    @Override
    @Transactional
    public AiUserFile upload(Long userId, String createBy, MultipartFile file)
    {
        if (file == null || file.isEmpty())
        {
            throw new ServiceException("请选择要上传的文件", HttpStatus.BAD_REQUEST.value());
        }
        return persist(userId, createBy, sanitizeFileName(file.getOriginalFilename()),
                StringUtils.hasText(file.getContentType()) ? file.getContentType() : DEFAULT_CONTENT_TYPE,
                file.getSize(), file::getInputStream);
    }

    @Override
    @Transactional
    public AiUserFile saveFromWorkspace(Long userId, String createBy, String sessionId,
                                        String path, Long projectId)
    {
        requireUser(userId);
        if (!StringUtils.hasText(sessionId)) throw new ServiceException("缺少会话ID");
        if (!StringUtils.hasText(path)) throw new ServiceException("缺少文件路径");
        try
        {
            // createIfMissing=false:只读取，不能因为「保存一下」就给空会话建出工作区
            Path root = projectId != null
                    ? workspaceScopeService.resolveProjectRoot(toolProperties, projectId, false)
                    : workspaceScopeService.resolveRoot(toolProperties, sessionId, false);
            // 防穿越：path 由前端传入，必须落在沙箱根内
            Path target = WorkspaceSandbox.resolveSafe(root, path);
            if (!Files.isRegularFile(target))
            {
                throw new ServiceException("文件不存在: " + path);
            }
            String fileName = sanitizeFileName(target.getFileName().toString());
            String contentType = Files.probeContentType(target);
            return persist(userId, createBy, fileName,
                    StringUtils.hasText(contentType) ? contentType : DEFAULT_CONTENT_TYPE,
                    Files.size(target), () -> Files.newInputStream(target));
        }
        catch (IOException e)
        {
            throw new ServiceException("读取工作区文件失败: " + e.getMessage());
        }
    }

    /**
     * 上传与「从工作区保存」共用的落地逻辑:限额校验 → 秒传判定 → 写对象 → 落库。
     *
     * <p>{@code content} 会被读两遍(一遍算哈希、一遍写存储),所以必须能重复打开 ——
     * MultipartFile 与磁盘文件都满足,不要传一个只能消费一次的流进来。
     */
    private AiUserFile persist(Long userId, String createBy, String fileName, String contentType,
                               long size, StreamSource content)
    {
        requireUser(userId);
        ObjectStorage storage = requireStorage();
        if (size > properties.getMaxFileBytes())
        {
            throw new ServiceException("文件超过 " + readable(properties.getMaxFileBytes()) + " 上限");
        }

        UserFileQuota quota = quota(userId);
        if (quota.fileCount() >= quota.maxFiles())
        {
            throw new ServiceException("文件数已达上限 " + quota.maxFiles() + " 个,请先清理");
        }
        if (quota.usedBytes() + size > quota.quotaBytes())
        {
            throw new ServiceException("存储空间不足,剩余 " + readable(quota.remainingBytes())
                    + ",本次需要 " + readable(size));
        }

        String hash = sha256(content);

        // 秒传:同一用户传过同样内容就复用对象,只新增一条元数据。
        // 跨用户不复用 —— 对象键里带 userId,共用会让「A 删了文件 B 还看得见」变成
        // 一个需要全局引用计数才能解释清楚的问题,省下的那点空间不值。
        AiUserFile duplicate = StringUtils.hasText(hash)
                ? userFileMapper.selectByUserAndHash(userId, hash) : null;
        String objectKey;
        if (duplicate != null)
        {
            objectKey = duplicate.getObjectKey();
            log.debug("个人文件秒传命中: userId={} hash={} 复用 objectKey={}", userId, hash, objectKey);
        }
        else
        {
            objectKey = buildObjectKey(userId, fileName);
            try (InputStream in = content.open())
            {
                storage.put(objectKey, in, size, contentType);
            }
            catch (IOException e)
            {
                throw new ServiceException("文件写入存储失败: " + e.getMessage());
            }
        }

        Date now = new Date();
        AiUserFile row = new AiUserFile();
        row.setUserId(userId);
        row.setFileName(fileName);
        row.setObjectKey(objectKey);
        row.setFileSize(size);
        row.setContentType(contentType);
        row.setContentHash(hash);
        row.setCreateBy(createBy);
        row.setCreateTime(now);
        row.setUpdateTime(now);
        userFileMapper.insertAiUserFile(row);
        return row;
    }

    /** 可重复打开的字节源。 */
    @FunctionalInterface
    private interface StreamSource
    {
        InputStream open() throws IOException;
    }

    @Override
    public ObjectReadHandle open(Long userId, Long fileId)
    {
        AiUserFile row = requireOwned(userId, fileId);
        ObjectReadHandle handle = requireStorage().open(row.getObjectKey());
        if (handle == null)
        {
            // 元数据在但对象没了:多半是存储侧被手工清理过。报清楚,别让前端看到空文件
            throw new ServiceException("文件内容已丢失,请重新上传");
        }
        return handle;
    }

    @Override
    public String presignedUrl(Long userId, Long fileId, boolean asAttachment)
    {
        AiUserFile row = requireOwned(userId, fileId);
        return requireStorage().presignedUrl(row.getObjectKey(),
                Duration.ofSeconds(properties.getPresignTtlSeconds()),
                asAttachment ? row.getFileName() : null);
    }

    @Override
    @Transactional
    public AiUserFile rename(Long userId, Long fileId, String newName)
    {
        AiUserFile row = requireOwned(userId, fileId);
        String sanitized = sanitizeFileName(newName);
        if (!StringUtils.hasText(sanitized))
        {
            throw new ServiceException("文件名不能为空");
        }
        AiUserFile update = new AiUserFile();
        update.setFileId(row.getFileId());
        update.setUserId(userId);
        update.setFileName(sanitized);
        update.setUpdateTime(new Date());
        userFileMapper.updateAiUserFile(update);
        row.setFileName(sanitized);
        return row;
    }

    @Override
    @Transactional
    public void delete(Long userId, Long fileId)
    {
        AiUserFile row = requireOwned(userId, fileId);
        userFileMapper.softDeleteByIdAndUser(fileId, userId);
        // 秒传让多条记录可能共用一个对象,只有最后一条被删时才动存储本体
        if (userFileMapper.countLiveByObjectKey(row.getObjectKey()) > 0)
        {
            return;
        }
        try
        {
            requireStorage().delete(row.getObjectKey());
        }
        catch (RuntimeException e)
        {
            // 元数据已软删,存储侧失败不该回滚用户可见的删除动作。
            // 留下的孤儿对象是可容忍的:它不可达,后续用对账任务清理即可
            log.warn("删除对象失败,已留下孤儿对象: objectKey={} err={}", row.getObjectKey(), e.toString());
        }
    }

    @Override
    public String attachToSession(Long userId, Long fileId, String sessionId, Long projectId)
    {
        AiUserFile row = requireOwned(userId, fileId);
        if (!StringUtils.hasText(sessionId))
        {
            throw new ServiceException("缺少会话ID");
        }
        try
        {
            Path root = projectId != null
                    ? workspaceScopeService.resolveProjectRoot(toolProperties, projectId, true)
                    : workspaceScopeService.resolveRoot(toolProperties, sessionId, true);
            Path uploadDir = root.resolve(UPLOAD_DIR);
            Files.createDirectories(uploadDir);
            Path target = uniqueTarget(uploadDir, row.getFileName());
            // 二次校验:文件名已 sanitize,这里再确认落点确实在 uploads/ 内
            if (!target.normalize().startsWith(uploadDir.normalize()))
            {
                throw new ServiceException("非法的文件名");
            }
            try (ObjectReadHandle handle = requireStorage().open(row.getObjectKey()))
            {
                if (handle == null)
                {
                    throw new ServiceException("文件内容已丢失,请重新上传");
                }
                Files.copy(handle.stream(), target, StandardCopyOption.REPLACE_EXISTING);
            }
            return UPLOAD_DIR + "/" + target.getFileName();
        }
        catch (IOException e)
        {
            throw new ServiceException("投递到会话工作区失败: " + e.getMessage());
        }
    }

    /* ---------------- 内部工具 ---------------- */

    private ObjectStorage requireStorage()
    {
        ObjectStorage storage = storageProvider.getIfAvailable();
        if (storage == null)
        {
            throw new ServiceException("对象存储未配置,请先在 ruoyi.ai.storage 下配置并开启");
        }
        return storage;
    }

    private static Long requireUser(Long userId)
    {
        if (userId == null)
        {
            throw new ServiceException("未登录");
        }
        return userId;
    }

    private AiUserFile requireOwned(Long userId, Long fileId)
    {
        AiUserFile row = get(userId, fileId);
        if (row == null)
        {
            throw new ServiceException("文件不存在或无权访问");
        }
        return row;
    }

    /** 排序字段白名单。来自查询参数,不能透传给 SQL。 */
    private static String normalizeOrderBy(String orderBy)
    {
        if (orderBy == null) return "date";
        return switch (orderBy.trim().toLowerCase(Locale.ROOT))
        {
            case "name", "size" -> orderBy.trim().toLowerCase(Locale.ROOT);
            default -> "date";
        };
    }

    /**
     * 对象键:{@code user/{userId}/{yyyyMM}/{uuid}.{ext}}。
     *
     * <p>刻意不拿原始文件名做键 —— 中文、空格、{@code ../}、超长名在不同 S3 实现下的行为
     * 各不相同,把它们挡在存储层之外,展示名归展示名、键归键。
     * 按月分片是为了让存储控制台还能按目录浏览,单前缀几十万对象翻不动。
     */
    private static String buildObjectKey(Long userId, String fileName)
    {
        String ext = extensionOf(fileName);
        String name = UUID.randomUUID().toString().replace("-", "");
        return "user/" + userId + "/" + LocalDate.now().format(MONTH) + "/"
                + name + (ext.isEmpty() ? "" : "." + ext);
    }

    /** 只保留字母数字的扩展名,长度截到 16 —— 它只影响存储控制台的可读性,不参与任何判断。 */
    private static String extensionOf(String fileName)
    {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return "";
        String ext = fileName.substring(dot + 1).replaceAll("[^A-Za-z0-9]", "");
        return ext.length() > 16 ? ext.substring(0, 16) : ext.toLowerCase(Locale.ROOT);
    }

    /**
     * 文件名 sanitize:剥掉目录成分,过滤控制字符与路径分隔符。
     *
     * <p>{@code getOriginalFilename()} 来自客户端,可能是 {@code ../../etc/passwd} 或带 NUL 的
     * 构造串。这里的产物既做展示名,也是 attachToSession 落地到磁盘时的文件名,必须干净。
     */
    private static String sanitizeFileName(String original)
    {
        if (!StringUtils.hasText(original)) return "unnamed";
        String name = original.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        name = name.replaceAll("[\\p{Cntrl}]", "").trim();
        // 纯点号的名字(. / ..)在文件系统里有特殊含义,直接换掉
        if (name.isEmpty() || name.chars().allMatch(c -> c == '.')) return "unnamed";
        return name.length() > 200 ? name.substring(0, 200) : name;
    }

    /** 同名不覆盖,追加 (1)(2)…… 与工作区上传的行为保持一致。 */
    private static Path uniqueTarget(Path dir, String fileName)
    {
        Path target = dir.resolve(fileName);
        if (!Files.exists(target)) return target;
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        String ext = dot > 0 ? fileName.substring(dot) : "";
        for (int i = 1; i < 1000; i++)
        {
            Path candidate = dir.resolve(base + " (" + i + ")" + ext);
            if (!Files.exists(candidate)) return candidate;
        }
        throw new ServiceException("同名文件过多,请先重命名");
    }

    /** 正文 SHA-256。失败只影响秒传,不该阻断上传,因此吞掉异常返回 null。 */
    private static String sha256(StreamSource source)
    {
        try (InputStream in = source.open())
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0)
            {
                digest.update(buffer, 0, read);
            }
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        }
        catch (IOException | NoSuchAlgorithmException e)
        {
            log.warn("计算文件哈希失败,本次不做秒传: {}", e.toString());
            return null;
        }
    }

    private static String readable(long bytes)
    {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / 1024.0 / 1024);
        return String.format("%.2f GB", bytes / 1024.0 / 1024 / 1024);
    }
}
