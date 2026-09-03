package com.ruoyi.system.tool;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 工作区路径。
 * <p>绑了会话时,相对路径落到会话沙箱({@code workspace-root/sessionId}),
 * 这样 write/bash 的产出会出现在前端工作区面板。未绑定时回退到配置 cwd / 进程目录(单测)。
 */
public final class ProjectPaths
{
    private ProjectPaths()
    {
    }

    public static Path cwd(AiToolProperties props)
    {
        String workspaceKey = WorkspaceContext.getWorkspaceKeyOrSessionId();
        if (workspaceKey != null && !workspaceKey.isBlank())
        {
            try
            {
                return WorkspaceSandbox.resolveRoot(props, workspaceKey, true);
            }
            catch (Exception ignored)
            {
            }
        }
        String configured = props == null ? null : props.getCwd();
        if (configured != null && !configured.isBlank())
        {
            return Paths.get(configured).toAbsolutePath().normalize();
        }
        return processCwd();
    }

    public static Path resolve(AiToolProperties props, String path)
    {
        Path base = cwd(props);
        if (path == null || path.isBlank() || ".".equals(path.trim()))
        {
            return base;
        }
        Path raw = Paths.get(path.trim());
        if (raw.isAbsolute())
        {
            Path abs = raw.normalize();
            if (abs.startsWith(base))
            {
                return guardSessionPath(base, abs, path);
            }
            Path remapped = remapProcessCwdLeak(base, abs);
            if (remapped != null)
            {
                return guardSessionPath(base, remapped, path);
            }
            if (WorkspaceContext.getOrNull() != null)
            {
                throw new SecurityException("路径必须位于会话工作区内: " + path);
            }
            return abs;
        }
        Path resolved = base.resolve(raw).normalize();
        if (WorkspaceContext.getOrNull() != null && !resolved.startsWith(base))
        {
            throw new SecurityException("路径越出会话工作区: " + path);
        }
        return guardSessionPath(base, resolved, path);
    }

    /**
     * 旧提示词教模型写 {@code /app/xxx.docx},把它折回会话工作区。
     */
    private static Path remapProcessCwdLeak(Path base, Path abs)
    {
        Path process = processCwd();
        if (!abs.startsWith(process) || abs.startsWith(base))
        {
            return null;
        }
        Path rel = process.relativize(abs);
        if (rel.startsWith(".."))
        {
            return null;
        }
        Path mapped = base.resolve(rel).normalize();
        return mapped.startsWith(base) ? mapped : null;
    }

    private static Path guardSessionPath(Path base, Path target, String label)
    {
        if (WorkspaceContext.getOrNull() == null)
        {
            return target;
        }
        try
        {
            return WorkspaceSandbox.rejectSymlinkOrEscape(base, target, label);
        }
        catch (IOException e)
        {
            throw new SecurityException("路径校验失败: " + label);
        }
    }

    private static Path processCwd()
    {
        return Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
    }
}
