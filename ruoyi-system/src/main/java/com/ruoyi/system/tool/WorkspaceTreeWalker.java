package com.ruoyi.system.tool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;

/**
 * 会话工作区文件系统的共享视图工具:目录树遍历 / 文本预览 / 上传命名规则。
 *
 * <p>从 {@code ruoyi-admin.AiChatWorkspaceController} 抽出,主应用工作区抽屉与独立
 * tool-mcp-server 的 /ws REST 共用同一份 —— 两边返回的 JSON 形状(node/file 视图)、
 * 截断预算、上传命名规则天然一致,零逻辑复制。
 */
public final class WorkspaceTreeWalker
{
    /** 目录树最大递归深度 */
    public static final int MAX_DEPTH = 5;
    /** 目录树最大节点数(防超大目录打爆响应) */
    public static final int MAX_NODES = 500;
    /** 单文件预览最大字节数 */
    public static final long MAX_FILE_BYTES = 200 * 1024L;
    /** 用户上传目录(相对会话沙箱根),与 AI 自己产出的文件分开放 */
    public static final String UPLOAD_DIR = "uploads";
    /** AI 生成文件目录(相对会话沙箱根) */
    public static final String OUTPUT_DIR = "outputs";
    /** 工作区上传来源:用户主动上传 */
    public static final String SOURCE_USER = "user";
    /** 工作区上传来源:AI/渠道工具生成 */
    public static final String SOURCE_AI = "ai";
    /** 单个上传文件大小上限(与 ai/chat/workspace 上传、Spring multipart 上限对齐) */
    public static final long MAX_UPLOAD_BYTES = 10 * 1024 * 1024L;
    /** 文件名最大长度(不含去重后缀) */
    public static final int MAX_NAME_LEN = 80;
    /** 打包下载的条目数上限,防止一次拖走超大目录把服务器 IO 打满 */
    public static final int MAX_ZIP_ENTRIES = 2000;
    /** 打包下载的总字节上限 */
    public static final long MAX_ZIP_BYTES = 200 * 1024 * 1024L;

    private WorkspaceTreeWalker()
    {
    }

    /** 目录树结果 */
    public static final class TreeResult
    {
        public boolean truncated;
        public List<Node> nodes = new ArrayList<>();
    }

    /** 单个文本文件预览视图 */
    public static final class FileView
    {
        public boolean found;
        public String path;
        public long size;
        public boolean tooLarge;
        public boolean binary;
        public String content = "";
    }

    /** 目录树节点(字段名即 /ws 与主应用抽屉的 JSON 契约) */
    public static class Node
    {
        public String name;
        /** 面向用户的名称；路径与真实文件名仍使用 name/path，避免中文展示名破坏文件操作 */
        public String displayName;
        /** 相对沙箱根的路径 */
        public String path;
        /** dir / file */
        public String type;
        public long size;
        /** 最后修改时间(ms) */
        public long mtime;
        public List<Node> children;
    }

    /**
     * 目录树。目录不存在(会话还没产生过文件)时返回空结果,不落盘。
     */
    public static TreeResult walk(Path root) throws IOException
    {
        TreeResult result = new TreeResult();
        if (!Files.isDirectory(root))
        {
            return result;
        }
        int[] budget = {MAX_NODES};
        result.nodes = listChildren(root, root, 1, budget);
        result.truncated = budget[0] <= 0;
        return result;
    }

    /**
     * 递归列目录。目录在前、同类按名称排序;超出深度或节点预算即停止。
     * <p>从 {@code AiChatWorkspaceController.listChildren} 原样迁入。
     */
    private static List<Node> listChildren(Path root, Path dir, int depth, int[] budget) throws IOException
    {
        List<Node> list = new ArrayList<>();
        if (depth > MAX_DEPTH || budget[0] <= 0 || !Files.isDirectory(dir))
        {
            return list;
        }
        List<Path> entries;
        try (Stream<Path> stream = Files.list(dir))
        {
            entries = stream
                .sorted(Comparator
                    .comparing((Path p) -> Files.isDirectory(p) ? 0 : 1)
                    .thenComparing(p -> p.getFileName().toString()))
                .toList();
        }
        for (Path p : entries)
        {
            if (budget[0] <= 0)
            {
                break;
            }
            if (Files.isSymbolicLink(p))
            {
                continue;
            }
            budget[0]--;
            boolean isDir = Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS);
            Node n = new Node();
            n.name = p.getFileName().toString();
            n.displayName = displayName(n.name, isDir, depth);
            n.path = root.relativize(p).toString().replace('\\', '/');
            n.type = isDir ? "dir" : "file";
            n.size = isDir ? 0L : Files.size(p);
            n.mtime = Files.getLastModifiedTime(p).toMillis();
            if (isDir)
            {
                n.children = listChildren(root, p, depth + 1, budget);
            }
            list.add(n);
        }
        return list;
    }

    /**
     * 上传来源对应的固定分区。缺省保持用户上传语义；未知值拒绝，避免调用方拼错后
     * 把 AI 产物静默放进个人上传区。
     */
    public static String uploadDirectory(String source)
    {
        if (StringUtils.isEmpty(source) || SOURCE_USER.equalsIgnoreCase(source))
        {
            return UPLOAD_DIR;
        }
        if (SOURCE_AI.equalsIgnoreCase(source))
        {
            return OUTPUT_DIR;
        }
        throw new ServiceException("未知的工作区文件来源: " + source);
    }

    private static String displayName(String name, boolean directory, int depth)
    {
        if (!directory || depth != 1)
        {
            return null;
        }
        if (UPLOAD_DIR.equals(name))
        {
            return "个人文件上传";
        }
        if (OUTPUT_DIR.equals(name))
        {
            return "AI 生成文件";
        }
        return null;
    }

    /**
     * 文本预览视图。经 {@link WorkspaceSandbox#resolveSafe} 防穿越/符号链接;
     * 非普通文件返回 found=false;超 <code>MAX_FILE_BYTES</code> 或二进制时
     * content 为空、分别置 tooLarge/binary,由调用方决定怎样展示。
     */
    public static FileView readFile(Path root, String relative) throws IOException
    {
        FileView view = new FileView();
        if (StringUtils.isEmpty(relative))
        {
            return view;
        }
        view.path = relative;
        Path target = WorkspaceSandbox.resolveSafe(root, relative);
        if (!Files.isRegularFile(target))
        {
            return view;
        }
        view.found = true;
        view.size = Files.size(target);
        if (view.size > MAX_FILE_BYTES)
        {
            view.tooLarge = true;
            return view;
        }
        byte[] bytes = Files.readAllBytes(target);
        if (isBinary(bytes))
        {
            view.binary = true;
            return view;
        }
        view.content = new String(bytes, StandardCharsets.UTF_8);
        return view;
    }

    /** 前 8KB 出现 NUL 字节即判为二进制 */
    public static boolean isBinary(byte[] bytes)
    {
        int limit = Math.min(bytes.length, 8192);
        for (int i = 0; i < limit; i++)
        {
            if (bytes[i] == 0)
            {
                return true;
            }
        }
        return false;
    }

    /**
     * 文件名 sanitize:剥掉任何目录成分,只保留基本名;过滤控制字符与路径分隔符。
     * <p>不能信任客户端名字 —— 可能带目录成分或 NUL。
     */
    public static String sanitizeFileName(String original)
    {
        String name = StringUtils.isEmpty(original) ? "file" : original;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0)
        {
            name = name.substring(slash + 1);
        }
        name = name.replaceAll("[\\p{Cntrl}<>:\"|?*]", "").trim();
        if (name.isEmpty() || name.chars().allMatch(c -> c == '.'))
        {
            name = "file";
        }
        if (name.length() > MAX_NAME_LEN)
        {
            String ext = "";
            int dot = name.lastIndexOf('.');
            if (dot > 0 && name.length() - dot <= 12)
            {
                ext = name.substring(dot);
            }
            name = name.substring(0, MAX_NAME_LEN - ext.length()) + ext;
        }
        return name;
    }

    /** 同名去重:report.csv -> report(1).csv -> report(2).csv */
    public static Path uniqueTarget(Path dir, String name)
    {
        Path candidate = dir.resolve(name);
        if (!Files.exists(candidate))
        {
            return candidate;
        }
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0)
        {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        for (int i = 1; i < 1000; i++)
        {
            Path next = dir.resolve(base + "(" + i + ")" + ext);
            if (!Files.exists(next))
            {
                return next;
            }
        }
        throw new ServiceException("同名文件过多,请重命名后再上传");
    }

    /** MIME:优先按扩展名探测,拿不到再退回客户端声明的 Content-Type */
    public static String resolveMime(Path path, String declared)
    {
        try
        {
            String probed = Files.probeContentType(path);
            if (StringUtils.isNotEmpty(probed))
            {
                return probed;
            }
        }
        catch (IOException ignored)
        {
        }
        return StringUtils.isNotEmpty(declared) ? declared : "application/octet-stream";
    }
}
