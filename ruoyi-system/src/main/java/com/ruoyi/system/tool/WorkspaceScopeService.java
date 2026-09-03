package com.ruoyi.system.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.AiProject;
import com.ruoyi.system.mapper.AiChatSessionMapper;
import com.ruoyi.system.mapper.AiProjectMapper;

/**
 * 将聊天会话映射到真正的文件工作空间。
 *
 * <p>项目会话共享 {@code project-{projectId}}，普通会话继续使用 {@code sessionId}。
 * 对话消息、记忆和运行记录仍按 session 隔离；这里只改变磁盘文件的作用域。
 */
@Component
public class WorkspaceScopeService
{
    private static final Logger log = LoggerFactory.getLogger(WorkspaceScopeService.class);
    private static final String PROJECT_PREFIX = "project-";
    private static final String LEGACY_CONFLICT_DIR = "_legacy";

    private AiChatSessionMapper sessionMapper;
    private AiProjectMapper projectMapper;

    /** 只有项目会话才缓存；普通会话不缓存，兼容“上传后再绑定项目”的旧客户端。 */
    private final Map<String, String> projectScopeCache = new ConcurrentHashMap<>();
    /** 每个进程每个项目只扫描一次旧目录；旧目录保留不删，迁移是无损复制。 */
    private final Set<Long> migratedProjects = ConcurrentHashMap.newKeySet();

    /** 兼容不启动 Spring 的旧测试；生产使用下面的注入构造。 */
    public WorkspaceScopeService()
    {
    }

    @Autowired
    public WorkspaceScopeService(AiChatSessionMapper sessionMapper, AiProjectMapper projectMapper)
    {
        this.sessionMapper = sessionMapper;
        this.projectMapper = projectMapper;
    }

    public String resolveWorkspaceKey(String sessionId)
    {
        if (sessionId == null || sessionId.isBlank())
        {
            return sessionId;
        }
        String cached = projectScopeCache.get(sessionId);
        if (cached != null)
        {
            return cached;
        }
        Long projectId = sessionMapper.selectOwnedProjectIdBySessionId(sessionId);
        if (projectId == null || projectId <= 0)
        {
            return sessionId;
        }
        String key = projectWorkspaceKey(projectId);
        projectScopeCache.put(sessionId, key);
        return key;
    }

    public Path resolveRoot(AiToolProperties props, String sessionId, boolean createIfMissing)
            throws IOException
    {
        String workspaceKey = resolveWorkspaceKey(sessionId);
        Path root = WorkspaceSandbox.resolveRoot(props, workspaceKey, createIfMissing);
        Long projectId = parseProjectId(workspaceKey);
        if (projectId != null)
        {
            migrateLegacySessionRoots(props, projectId, root);
        }
        return root;
    }

    /** 已完成项目归属校验的 HTTP 调用使用，同时兼容归并旧的会话目录。 */
    public Path resolveProjectRoot(AiToolProperties props, Long projectId, boolean createIfMissing)
            throws IOException
    {
        Path root = WorkspaceSandbox.resolveRoot(props, projectWorkspaceKey(projectId), createIfMissing);
        migrateLegacySessionRoots(props, projectId, root);
        return root;
    }

    /** 新建项目会话上传附件前使用：同时完成项目归属校验。 */
    public String requireProjectWorkspaceKey(Long projectId, Long userId)
    {
        if (projectId == null)
        {
            throw new ServiceException("项目ID不能为空");
        }
        AiProject project = projectMapper.selectAiProjectById(projectId);
        if (project == null || userId == null || !userId.equals(project.getUserId()))
        {
            throw new ServiceException("项目不存在");
        }
        return projectWorkspaceKey(projectId);
    }

    public static String projectWorkspaceKey(Long projectId)
    {
        if (projectId == null || projectId <= 0)
        {
            throw new ServiceException("项目ID不正确");
        }
        return PROJECT_PREFIX + projectId;
    }

    public void evictSession(String sessionId)
    {
        if (sessionId != null)
        {
            projectScopeCache.remove(sessionId);
        }
    }

    /**
     * 将改造前的 {@code workspace/{sessionId}} 文件复制进项目工作区。
     *
     * <p>源目录始终保留；同路径同内容跳过，不同内容保存在
     * {@code _legacy/{sessionId}/}，避免静默覆盖。
     */
    private void migrateLegacySessionRoots(AiToolProperties props, Long projectId, Path projectRoot)
            throws IOException
    {
        if (props == null || !props.isWorkspacePerSession() || migratedProjects.contains(projectId))
        {
            return;
        }
        synchronized (migratedProjects)
        {
            if (migratedProjects.contains(projectId))
            {
                return;
            }
            List<String> sessionIds = sessionMapper.selectOwnedSessionIdsByProjectId(projectId);
            if (sessionIds != null)
            {
                for (String sessionId : sessionIds)
                {
                    Path legacyRoot = WorkspaceSandbox.resolveRoot(props, sessionId, false);
                    if (!Files.exists(legacyRoot) || Files.isSymbolicLink(legacyRoot)
                            || legacyRoot.equals(projectRoot))
                    {
                        continue;
                    }
                    copyLegacyRoot(legacyRoot, projectRoot, sessionId);
                }
            }
            migratedProjects.add(projectId);
        }
    }

    private void copyLegacyRoot(Path legacyRoot, Path projectRoot, String sessionId) throws IOException
    {
        try (Stream<Path> walk = Files.walk(legacyRoot))
        {
            for (Path source : walk.toList())
            {
                if (source.equals(legacyRoot) || Files.isSymbolicLink(source))
                {
                    continue;
                }
                Path relative = legacyRoot.relativize(source);
                Path target = WorkspaceSandbox.resolveSafe(projectRoot, relative.toString());
                if (Files.isDirectory(source))
                {
                    Files.createDirectories(target);
                    continue;
                }
                if (!Files.isRegularFile(source))
                {
                    continue;
                }
                if (!Files.exists(target))
                {
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target);
                    continue;
                }
                if (Files.isRegularFile(target) && Files.mismatch(source, target) == -1L)
                {
                    continue;
                }
                Path conflict = WorkspaceSandbox.resolveSafe(projectRoot,
                        LEGACY_CONFLICT_DIR + "/" + sessionId + "/" + relative);
                Files.createDirectories(conflict.getParent());
                if (!Files.exists(conflict))
                {
                    Files.copy(source, conflict);
                }
                else if (!Files.isRegularFile(conflict) || Files.mismatch(source, conflict) != -1L)
                {
                    log.warn("旧项目工作区存在重复冲突文件，保留原目录未覆盖: projectId={}, sessionId={}, path={}",
                            parseProjectId(projectRoot.getFileName().toString()), sessionId, relative);
                }
            }
        }
    }

    private Long parseProjectId(String workspaceKey)
    {
        if (workspaceKey == null || !workspaceKey.startsWith(PROJECT_PREFIX))
        {
            return null;
        }
        try
        {
            return Long.valueOf(workspaceKey.substring(PROJECT_PREFIX.length()));
        }
        catch (NumberFormatException ignored)
        {
            return null;
        }
    }

    /** 删除项目时才删除共享工作区；删除其中一个会话不能调用本方法。 */
    public void deleteProjectWorkspace(AiToolProperties props, Long projectId)
    {
        if (props == null || projectId == null || !props.isWorkspacePerSession())
        {
            return;
        }
        try
        {
            Path root = WorkspaceSandbox.resolveRoot(props, projectWorkspaceKey(projectId), false);
            Path base = WorkspaceSandbox.resolveBaseRoot(props, false);
            if (!root.startsWith(base) || root.equals(base) || !Files.exists(root))
            {
                return;
            }
            try (Stream<Path> walk = Files.walk(root))
            {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try
                    {
                        Files.deleteIfExists(path);
                    }
                    catch (IOException e)
                    {
                        throw new ServiceException("删除项目工作区失败: " + e.getMessage());
                    }
                });
            }
        }
        catch (IOException e)
        {
            throw new ServiceException("删除项目工作区失败: " + e.getMessage());
        }
    }
}
