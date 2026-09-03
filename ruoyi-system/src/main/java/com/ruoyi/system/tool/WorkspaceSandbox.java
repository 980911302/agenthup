package com.ruoyi.system.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.ai.session.SessionIds;

/**
 * AI 工作区沙箱路径解析与防路径穿越
 *
 * @author ruoyi
 */
public final class WorkspaceSandbox
{
    /** 未绑定会话时的兜底子目录(单测/未来新增调用方) */
    private static final String SHARED_DIR = "_shared";

    /**
     * MCP 调用 args envelope 里携带会话工作区键的字段名。
     * <p>主应用 {@code RecordingToolCallback} 对远端执行工具(MCP)注入该字段,
     * server 侧 {@code McpToolBootstrap} 弹出后 bind,让 OPI 上的读写按会话隔离落盘。
     * 模型可见的 inputSchema 不含此字段,永远不会被模型生成。
     */
    public static final String WORKSPACE_KEY_FIELD = "_workspaceKey";

    private WorkspaceSandbox()
    {
    }

    /**
     * 校验 sessionId 是否可安全用作目录名。
     *
     * <p>给「按前端传来的 sessionId 建会话」这类场景用:落库前先过这道校验,
     * 否则等于让客户端往库里写任意主键,后续再拿它拼路径就晚了。
     * {@link #resolveRoot} 内部同样会校验,这里只是把判断暴露出去,规则是同一条。
     */
    public static boolean isValidSessionId(String sessionId)
    {
        return SessionIds.isWellFormed(sessionId);
    }

    /**
     * 解析当前工具调用应使用的沙箱根。
     *
     * <p><b>语义</b>:自动读取 {@link WorkspaceContext} 里绑定的 sessionId ——
     * 因此 FileTools/ShellTool 的调用点无需感知会话。绑定由
     * {@link RecordingToolCallback} 在工具调用外层完成。
     */
    public static Path resolveRoot(AiToolProperties props) throws IOException
    {
        return resolveRoot(props, WorkspaceContext.getWorkspaceKeyOrSessionId());
    }

    /**
     * 解析指定会话的沙箱根(不存在则创建)。
     *
     * @param sessionId 会话ID;为空时落到 {base}/_shared;
     *                  {@code workspace-per-session=false} 时忽略此参数,直接返回 base
     */
    public static Path resolveRoot(AiToolProperties props, String sessionId) throws IOException
    {
        return resolveRoot(props, sessionId, true);
    }

    /**
     * 解析指定会话的沙箱根。
     *
     * @param createIfMissing 目录不存在时是否创建。<b>只读查询必须传 false</b> ——
     *                        否则一次 GET(如工作区目录树接口)就会在磁盘上建出空目录,
     *                        让从没产生过文件的会话凭空多出工作区。
     */
    public static Path resolveRoot(AiToolProperties props, String sessionId, boolean createIfMissing)
        throws IOException
    {
        Path base = resolveBaseRoot(props, createIfMissing);
        if (props != null && !props.isWorkspacePerSession())
        {
            return base;
        }
        String sub = StringUtils.isEmpty(sessionId) ? SHARED_DIR : sessionId.trim();
        if (!SHARED_DIR.equals(sub) && !SessionIds.isWellFormed(sub))
        {
            throw new SecurityException("非法的会话ID: " + sessionId);
        }
        Path dir = base.resolve(sub).normalize();
        if (!dir.startsWith(base))
        {
            throw new SecurityException("会话工作区越界: " + sessionId);
        }
        if (createIfMissing && !Files.exists(dir))
        {
            Files.createDirectories(dir);
        }
        return dir;
    }

    /**
     * 解析沙箱基础根(不存在则创建)。
     * 配置为空时回落 {ruoyi.profile}/ai-workspace。
     */
    public static Path resolveBaseRoot(AiToolProperties props) throws IOException
    {
        return resolveBaseRoot(props, true);
    }

    /**
     * 解析沙箱基础根。
     *
     * @param createIfMissing 目录不存在时是否创建(只读查询传 false)
     */
    public static Path resolveBaseRoot(AiToolProperties props, boolean createIfMissing) throws IOException
    {
        Path base;
        String configured = props != null ? props.getWorkspaceRoot() : null;
        if (StringUtils.isNotEmpty(configured))
        {
            base = Paths.get(configured.trim()).toAbsolutePath().normalize();
        }
        else
        {
            String profile = RuoYiConfig.getProfile();
            if (StringUtils.isEmpty(profile))
            {
                profile = System.getProperty("java.io.tmpdir") + "/ruoyi-ai";
            }
            base = Paths.get(profile, "ai-workspace").toAbsolutePath().normalize();
        }
        if (createIfMissing && !Files.exists(base))
        {
            Files.createDirectories(base);
        }
        return base;
    }

    /**
     * 将相对路径解析为沙箱内绝对路径，禁止穿越
     *
     * @param root     沙箱根
     * @param relative 相对路径；空或 "." 表示根
     * @return 规范化后的绝对路径
     * @throws SecurityException 路径越界
     */
    public static Path resolveSafe(Path root, String relative) throws IOException
    {
        Path base = root.toAbsolutePath().normalize();
        String rel = relative == null || relative.isBlank() ? "." : relative.trim();
        while (rel.startsWith("/") || rel.startsWith("\\"))
        {
            rel = rel.substring(1);
        }
        if (rel.matches("^[A-Za-z]:.*"))
        {
            throw new SecurityException("禁止访问沙箱外绝对路径: " + relative);
        }
        Path target = base.resolve(rel).normalize();
        if (!target.startsWith(base))
        {
            throw new SecurityException("路径穿越被拒绝: " + relative);
        }
        return rejectSymlinkOrEscape(base, target, relative);
    }

    /**
     * 拒绝符号链接以及 toRealPath 后逃出沙箱的路径。
     * 目标尚不存在时只做字面 normalize 检查(写新文件)。
     */
    public static Path rejectSymlinkOrEscape(Path base, Path target, String label) throws IOException
    {
        Path absBase = base.toAbsolutePath().normalize();
        Path absTarget = target.toAbsolutePath().normalize();
        if (!absTarget.startsWith(absBase))
        {
            throw new SecurityException("路径穿越被拒绝: " + label);
        }
        Path realBase = Files.exists(absBase) ? absBase.toRealPath() : absBase;
        Path cursor = absBase;
        Path relative = absBase.relativize(absTarget);
        for (Path part : relative)
        {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor))
            {
                throw new SecurityException("禁止访问符号链接: " + label);
            }
            if (!Files.exists(cursor))
            {
                break;
            }
            Path real = cursor.toRealPath();
            if (!real.startsWith(realBase))
            {
                throw new SecurityException("路径穿越被拒绝: " + label);
            }
        }
        return target;
    }
}
