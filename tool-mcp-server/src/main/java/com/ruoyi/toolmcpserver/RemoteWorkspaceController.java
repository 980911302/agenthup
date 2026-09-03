package com.ruoyi.toolmcpserver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.utils.file.FileUtils;
import com.ruoyi.system.tool.AiToolProperties;
import com.ruoyi.system.tool.WorkspaceSandbox;
import com.ruoyi.system.tool.WorkspaceTreeWalker;

import jakarta.servlet.http.HttpServletResponse;

/**
 * 会话工作区文件的远端暴露(/ws,与 /mcp 并存)。
 *
 * <p>主应用执行工具切到本 server(mode=mcp)后,AI 产出的文件落在 OPI 本地沙箱;
 * 主应用 AiChatWorkspaceController 的远程分支经这里代理抽屉读/写。[明文]
 *
 * <p>形状与护栏与主应用本地实现完全同源:{@link WorkspaceSandbox} 校验 workspaceKey 格式
 * (SessionIds 正则)与路径穿越/符号链接,{@link WorkspaceTreeWalker} 出目录树/文本预览/上传命名。
 *
 * <p>v1 不做独立鉴权:与 /mcp 一样靠 ZeroTier 隔离 + workspaceKey(会话 UUID / project-{id},不可猜)。
 */
@RestController
@RequestMapping("/ws")
public class RemoteWorkspaceController
{
    private final AiToolProperties props;

    public RemoteWorkspaceController(AiToolProperties props)
    {
        this.props = props;
    }

    /** 目录树;会话还没产生过文件返回空树,不落盘 */
    @GetMapping("/tree")
    public AjaxResult tree(@RequestParam String workspaceKey) throws IOException
    {
        Path root = WorkspaceSandbox.resolveRoot(props, workspaceKey, false);
        WorkspaceTreeWalker.TreeResult result = WorkspaceTreeWalker.walk(root);
        return AjaxResult.success().put("truncated", result.truncated).put("nodes", result.nodes);
    }

    /** 文本预览(≤200KB,拒绝二进制) */
    @GetMapping("/file")
    public AjaxResult file(@RequestParam String workspaceKey,
                           @RequestParam String path) throws IOException
    {
        Path root = WorkspaceSandbox.resolveRoot(props, workspaceKey, false);
        WorkspaceTreeWalker.FileView view = WorkspaceTreeWalker.readFile(root, path);
        if (!view.found)
        {
            return AjaxResult.error("文件不存在: " + path);
        }
        return AjaxResult.success()
                .put("path", view.path)
                .put("size", view.size)
                .put("tooLarge", view.tooLarge)
                .put("binary", view.binary)
                .put("content", view.content);
    }

    /** 下载单个文件(任意类型) */
    @GetMapping("/download")
    public void download(@RequestParam String workspaceKey,
                         @RequestParam String path,
                         HttpServletResponse response) throws IOException
    {
        if (StringUtils.isEmpty(path))
        {
            throw new ServiceException("参数 path 不能为空");
        }
        Path root = WorkspaceSandbox.resolveRoot(props, workspaceKey, false);
        Path target = WorkspaceSandbox.resolveSafe(root, path);
        if (!Files.isRegularFile(target))
        {
            throw new ServiceException("文件不存在: " + path);
        }
        response.reset();
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        response.setContentLengthLong(Files.size(target));
        FileUtils.setAttachmentResponseHeader(response, target.getFileName().toString());
        try (var out = response.getOutputStream())
        {
            Files.copy(target, out);
            out.flush();
        }
    }

    /** 打包下载:path 为目录则打包该目录,为空则整个工作区;软链接一律跳过 */
    @GetMapping("/download-zip")
    public void downloadZip(@RequestParam String workspaceKey,
                            @RequestParam(required = false) String path,
                            HttpServletResponse response) throws IOException
    {
        Path root = WorkspaceSandbox.resolveRoot(props, workspaceKey, false);
        Path base = StringUtils.isEmpty(path) ? root : WorkspaceSandbox.resolveSafe(root, path);
        if (!Files.isDirectory(base))
        {
            throw new ServiceException("目录不存在: " + (path == null ? "" : path));
        }
        String zipName = (base.equals(root) ? "workspace" : base.getFileName().toString()) + ".zip";
        response.reset();
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        FileUtils.setAttachmentResponseHeader(response, zipName);

        long[] written = {0L};
        int[] count = {0};
        try (ZipOutputStream zip = new ZipOutputStream(response.getOutputStream(), StandardCharsets.UTF_8);
             Stream<Path> walk = Files.walk(base))
        {
            List<Path> files = walk.filter(Files::isRegularFile)
                    .filter(p -> !Files.isSymbolicLink(p))
                    .collect(Collectors.toList());
            for (Path f : files)
            {
                if (count[0] >= WorkspaceTreeWalker.MAX_ZIP_ENTRIES)
                {
                    throw new ServiceException("文件数超过 " + WorkspaceTreeWalker.MAX_ZIP_ENTRIES + " 个,请分目录下载");
                }
                long size = Files.size(f);
                if (written[0] + size > WorkspaceTreeWalker.MAX_ZIP_BYTES)
                {
                    throw new ServiceException("内容超过 " + (WorkspaceTreeWalker.MAX_ZIP_BYTES / 1024 / 1024) + "MB,请分目录下载");
                }
                String entryName = base.relativize(f).toString().replace('\\', '/');
                zip.putNextEntry(new ZipEntry(entryName));
                Files.copy(f, zip);
                zip.closeEntry();
                written[0] += size;
                count[0]++;
            }
            zip.finish();
        }
    }

    /** 删除单个文件或目录 */
    @DeleteMapping("/file")
    public AjaxResult deleteFile(@RequestParam String workspaceKey,
                                 @RequestParam String path) throws IOException
    {
        if (StringUtils.isEmpty(path))
        {
            throw new ServiceException("参数 path 不能为空");
        }
        Path root = WorkspaceSandbox.resolveRoot(props, workspaceKey, false);
        Path target = WorkspaceSandbox.resolveSafe(root, path);
        if (!Files.exists(target))
        {
            return AjaxResult.success("文件已被删除");
        }
        if (Files.isDirectory(target))
        {
            try (Stream<Path> walk = Files.walk(target))
            {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try
                    {
                        Files.deleteIfExists(p);
                    }
                    catch (IOException e)
                    {
                        throw new ServiceException("删除失败: " + p.getFileName() + " - " + e.getMessage());
                    }
                });
            }
        }
        else
        {
            Files.deleteIfExists(target);
        }
        return AjaxResult.success("删除成功");
    }

    /** 清空整个工作区(二次校验只能删 workspaceKey 对应的子目录) */
    @DeleteMapping("/clear")
    public AjaxResult clear(@RequestParam String workspaceKey) throws IOException
    {
        Path root = WorkspaceSandbox.resolveRoot(props, workspaceKey, false);
        Path base = WorkspaceSandbox.resolveBaseRoot(props, false);
        if (!root.startsWith(base) || root.equals(base))
        {
            throw new ServiceException("非法的工作区路径,拒绝删除");
        }
        if (Files.exists(root))
        {
            try (Stream<Path> walk = Files.walk(root))
            {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try
                    {
                        Files.deleteIfExists(p);
                    }
                    catch (IOException e)
                    {
                        throw new ServiceException("删除失败: " + p.getFileName() + " - " + e.getMessage());
                    }
                });
            }
        }
        Files.createDirectories(root);
        return AjaxResult.success("工作区已清空");
    }

    /** 上传到 uploads/(同名自动加序号,只新增不覆盖) */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AjaxResult upload(@RequestParam String workspaceKey,
                             @RequestParam("file") MultipartFile file) throws IOException
    {
        if (file == null || file.isEmpty())
        {
            throw new ServiceException("请选择要上传的文件");
        }
        if (file.getSize() > WorkspaceTreeWalker.MAX_UPLOAD_BYTES)
        {
            throw new ServiceException("文件超过 " + (WorkspaceTreeWalker.MAX_UPLOAD_BYTES / 1024 / 1024) + "MB 上限");
        }
        Path root = WorkspaceSandbox.resolveRoot(props, workspaceKey, true);
        Path uploadDir = root.resolve(WorkspaceTreeWalker.UPLOAD_DIR);
        Files.createDirectories(uploadDir);

        String safeName = WorkspaceTreeWalker.sanitizeFileName(file.getOriginalFilename());
        Path target = WorkspaceTreeWalker.uniqueTarget(uploadDir, safeName);
        if (!target.normalize().startsWith(uploadDir.normalize()))
        {
            throw new ServiceException("非法的文件名");
        }
        file.transferTo(target);

        String relPath = WorkspaceTreeWalker.UPLOAD_DIR + "/" + target.getFileName();
        Map<String, Object> data = new HashMap<>();
        data.put("name", target.getFileName().toString());
        data.put("path", relPath);
        data.put("mime", WorkspaceTreeWalker.resolveMime(target, file.getContentType()));
        data.put("size", Files.size(target));
        return AjaxResult.success(data);
    }
}