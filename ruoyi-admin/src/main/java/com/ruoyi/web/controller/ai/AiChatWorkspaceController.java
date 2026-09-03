package com.ruoyi.web.controller.ai;

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
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.utils.file.FileUtils;
import com.ruoyi.system.ai.session.SessionAccessGuard;
import com.ruoyi.system.domain.AiChatRun;
import com.ruoyi.system.domain.AiChatSession;
import com.ruoyi.system.mapper.AiChatRunMapper;
import com.ruoyi.system.mapper.AiProjectMapper;
import com.ruoyi.system.tool.AiToolProperties;
import com.ruoyi.system.tool.RemoteWorkspaceService;
import com.ruoyi.system.tool.WorkspaceSandbox;
import com.ruoyi.system.tool.WorkspaceScopeService;
import com.ruoyi.system.tool.WorkspaceTreeWalker;

/**
 * AI 会话工作区。
 *
 * <p>展示某次会话的沙箱目录 {workspace-root}/{sessionId} 下的文件 —— 既有 AI 产出的,
 * 也有用户上传的(统一落在 {@code uploads/} 子目录)。
 *
 * <p><b>本地模式(local)</b>:直接操作主应用本地目录 {workspace-root}/{sessionId}。
 * <b>远端模式(mcp)</b>:执行工具在远端 tool-mcp-server 跑,产物落在 OPI 本地沙箱,
 * 本控制器经 {@link RemoteWorkspaceService} 把抽屉读写原样透传到远端的 /ws REST
 * (同一套 {@link WorkspaceSandbox} 护栏 + {@link WorkspaceTreeWalker} 形状)。
 * 归属校验(非管理员只能看自己的会话)两种模式都在主应用先做。
 *
 * <p><b>为什么允许上传,却仍不提供任意写/改</b>:上传是用户显式提供的输入,
 * 且必定伴随一条把文件清单告知模型的消息,磁盘状态与对话上下文是同步的;
 * 而人工去改 AI 已经写好的文件,模型并不知情,上下文就和磁盘对不上了。
 * 所以这里只开「新增到 uploads/」这一个写口子,不开覆盖与编辑。
 *
 * <p>安全:归属校验 + 复用 {@link WorkspaceSandbox#resolveSafe} 防路径穿越 + 文件名 sanitize。
 *
 * @author ruoyi
 */
@RestController
@RequestMapping("/ai/chat/workspace")
public class AiChatWorkspaceController extends BaseController
{
    @Autowired
    private AiToolProperties aiToolProperties;

    @Autowired
    private AiChatRunMapper aiChatRunMapper;

    @Autowired
    private SessionAccessGuard sessionGuard;

    @Autowired
    private WorkspaceScopeService workspaceScopeService;

    @Autowired
    private AiProjectMapper aiProjectMapper;

    @Autowired
    private RemoteWorkspaceService remoteWorkspaceService;

    /**
     * 会话工作区目录树。
     *
     * @param sessionId 会话ID
     * @return { truncated: 是否被截断, nodes: [...] }
     */
    @GetMapping("/{sessionId}/tree")
    public AjaxResult tree(@PathVariable String sessionId,
                           @RequestParam(required = false) Long projectId) throws IOException
    {
        if (remoteWorkspaceService.enabled())
        {
            Map<String, Object> r = remoteWorkspaceService.tree(resolveRemoteWorkspaceKey(sessionId, projectId));
            return AjaxResult.success()
                    .put("truncated", r.getOrDefault("truncated", false))
                    .put("nodes", r.getOrDefault("nodes", List.of()));
        }
        // createIfMissing=false:只读查询不能有建目录的副作用,否则打开一次面板
        // 就会给从没产生过文件的会话建出空工作区
        Path root = resolveReadableWorkspaceRoot(sessionId, projectId);
        WorkspaceTreeWalker.TreeResult tree = WorkspaceTreeWalker.walk(root);
        return AjaxResult.success().put("truncated", tree.truncated).put("nodes", tree.nodes);
    }

    /**
     * 读取工作区内单个文本文件。
     *
     * @param sessionId 会话ID
     * @param path      相对沙箱根的文件路径
     */
    @GetMapping("/{sessionId}/file")
    public AjaxResult file(@PathVariable String sessionId,
                           @RequestParam String path,
                           @RequestParam(required = false) Long projectId) throws IOException
    {
        if (StringUtils.isEmpty(path))
        {
            return AjaxResult.error("参数 path 不能为空");
        }
        if (remoteWorkspaceService.enabled())
        {
            Map<String, Object> r = remoteWorkspaceService.file(resolveRemoteWorkspaceKey(sessionId, projectId), path);
            return AjaxResult.success()
                    .put("path", path)
                    .put("size", r.getOrDefault("size", 0L))
                    .put("tooLarge", r.getOrDefault("tooLarge", false))
                    .put("binary", r.getOrDefault("binary", false))
                    .put("content", r.getOrDefault("content", ""));
        }
        Path root = resolveReadableWorkspaceRoot(sessionId, projectId);
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

    /**
     * 手动清空会话工作区(只删本会话沙箱目录,不接受任意 path)。
     * <p>删除会话时服务层会级联删整棵沙箱；本接口仅用于会话仍存在时用户主动清空。
     */
    @DeleteMapping("/{sessionId}")
    @Transactional
    public AjaxResult clear(@PathVariable String sessionId,
                            @RequestParam(required = false) Long projectId) throws IOException
    {
        if (remoteWorkspaceService.enabled())
        {
            remoteWorkspaceService.clear(resolveRemoteWorkspaceKey(sessionId, projectId));
            return AjaxResult.success("工作区已清空");
        }
        Path root = resolveWritableWorkspaceRoot(sessionId, projectId);
        Path base = WorkspaceSandbox.resolveBaseRoot(aiToolProperties, false);
        // 二次校验:只能删 base 下的会话子目录,禁止误伤 base 本身
        if (!root.startsWith(base) || root.equals(base))
        {
            throw new ServiceException("非法的工作区路径,拒绝删除");
        }
        if (Files.exists(root))
        {
            deleteRecursively(root);
        }
        // 幂等:清空后重建空目录,后续工具调用可直接写
        Files.createDirectories(root);
        return AjaxResult.success("工作区已清空");
    }

    /**
     * 上传文件到会话工作区的 {@code uploads/} 目录。
     *
     * <p>只新增、不覆盖:同名文件自动加序号(report.csv -> report(1).csv),
     * 避免用户二次上传把模型已经读过的文件悄悄换掉 —— 那会让上下文与磁盘不一致。
     *
     * @param sessionId 会话ID
     * @param file      上传的文件
     * @return {name, path, mime, size},path 是相对沙箱根的路径,可直接喂给 readFile 工具
     */
    @PostMapping("/{sessionId}/upload")
    public AjaxResult upload(@PathVariable String sessionId,
                             @RequestParam(value = "projectId", required = false) Long projectId,
                             @RequestParam("file") MultipartFile file) throws IOException
    {
        // 与只读接口不同,这里用 requireOrCreateSession:
        // 「新开对话 -> 先传个文件 -> 再提问」是很自然的用法,不该逼用户先随便发一句话
        // 才能上传。会话行由这里先建出来,标题留空,等首条消息发出时再补(见 ensureSession)。
        requireOrCreateSession(sessionId, projectId);
        if (file == null || file.isEmpty())
        {
            throw new ServiceException("请选择要上传的文件");
        }
        if (file.getSize() > WorkspaceTreeWalker.MAX_UPLOAD_BYTES)
        {
            throw new ServiceException("文件超过 " + (WorkspaceTreeWalker.MAX_UPLOAD_BYTES / 1024 / 1024) + "MB 上限");
        }
        if (remoteWorkspaceService.enabled())
        {
            Map<String, Object> data = remoteWorkspaceService.upload(resolveRemoteWorkspaceKey(sessionId, projectId),
                    file.getOriginalFilename(), file.getBytes());
            return AjaxResult.success(data);
        }

        Path root = workspaceScopeService.resolveRoot(aiToolProperties, sessionId, true);
        Path uploadDir = root.resolve(WorkspaceTreeWalker.UPLOAD_DIR);
        Files.createDirectories(uploadDir);

        String safeName = WorkspaceTreeWalker.sanitizeFileName(file.getOriginalFilename());
        Path target = WorkspaceTreeWalker.uniqueTarget(uploadDir, safeName);
        // 二次校验:sanitize 后仍必须落在 uploads/ 内,防御任何遗漏的穿越形态
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

    /**
     * 下载工作区内单个文件(任意类型,含二进制)。
     *
     * <p>与 {@code /file} 接口的区别:那个只做文本预览、有 200KB 上限且拒绝二进制;
     * 这里是原样下载,不限类型。
     */
    @GetMapping("/{sessionId}/download")
    public void download(@PathVariable String sessionId,
                         @RequestParam String path,
                         @RequestParam(required = false) Long projectId,
                         HttpServletResponse response) throws IOException
    {
        if (StringUtils.isEmpty(path))
        {
            throw new ServiceException("参数 path 不能为空");
        }
        if (remoteWorkspaceService.enabled())
        {
            byte[] bytes = remoteWorkspaceService.download(resolveRemoteWorkspaceKey(sessionId, projectId), path);
            String name = fileName(path);
            response.reset();
            response.setContentType("application/octet-stream");
            response.setContentLengthLong(bytes.length);
            FileUtils.setAttachmentResponseHeader(response, name);
            try (var out = response.getOutputStream())
            {
                out.write(bytes);
                out.flush();
            }
            return;
        }
        Path root = resolveReadableWorkspaceRoot(sessionId, projectId);
        Path target = WorkspaceSandbox.resolveSafe(root, path);
        if (!Files.isRegularFile(target))
        {
            throw new ServiceException("文件不存在: " + path);
        }
        response.reset();
        response.setContentType("application/octet-stream");
        response.setContentLengthLong(Files.size(target));
        FileUtils.setAttachmentResponseHeader(response, target.getFileName().toString());
        try (var out = response.getOutputStream())
        {
            Files.copy(target, out);
            out.flush();
        }
    }

    /**
     * 打包下载:{@code path} 为目录则打包该目录,为空则打包整个会话工作区。
     *
     * <p>流式写进响应,不在内存里攒整个 zip —— 工作区可能有几百 MB,
     * 先攒后写会直接把堆打爆。
     *
     * <p>软链接一律跳过:沙箱内的软链接可能指向 root 之外,跟着走就绕过了
     * {@link WorkspaceSandbox} 的边界防护,等于把服务器任意文件打包给了下载方。
     */
    @GetMapping("/{sessionId}/download-zip")
    public void downloadZip(@PathVariable String sessionId,
                            @RequestParam(required = false) String path,
                            @RequestParam(required = false) Long projectId,
                            HttpServletResponse response) throws IOException
    {
        if (remoteWorkspaceService.enabled())
        {
            // 远端已做逐项预算与越界防护(与本地同源),这里只负责把 zip 原样转回
            byte[] bytes = remoteWorkspaceService.downloadZip(resolveRemoteWorkspaceKey(sessionId, projectId), path);
            String zipName = (StringUtils.isEmpty(path) ? "workspace" : fileName(path)) + ".zip";
            response.reset();
            response.setContentType("application/octet-stream");
            response.setContentLengthLong(bytes.length);
            FileUtils.setAttachmentResponseHeader(response, zipName);
            try (var out = response.getOutputStream())
            {
                out.write(bytes);
                out.flush();
            }
            return;
        }
        Path root = resolveReadableWorkspaceRoot(sessionId, projectId);
        Path base = StringUtils.isEmpty(path) ? root : WorkspaceSandbox.resolveSafe(root, path);
        if (!Files.isDirectory(base))
        {
            throw new ServiceException("目录不存在: " + (path == null ? "" : path));
        }

        String zipName = (base.equals(root) ? "workspace" : base.getFileName().toString()) + ".zip";
        response.reset();
        response.setContentType("application/zip");
        FileUtils.setAttachmentResponseHeader(response, zipName);

        long[] written = {0L};
        int[] count = {0};
        try (ZipOutputStream zip = new ZipOutputStream(response.getOutputStream(), StandardCharsets.UTF_8);
             Stream<Path> walk = Files.walk(base))
        {
            List<Path> files = walk.filter(Files::isRegularFile)
                    // 不跟软链接:可能指向沙箱之外
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
                // zip 内用相对路径,保留目录结构;统一成 / 分隔,Windows 下也能正常解压
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

    /**
     * 删除工作区内单个文件或目录。
     *
     * @param sessionId 会话ID
     * @param path      相对沙箱根的文件/目录路径
     */
    @DeleteMapping("/{sessionId}/file")
    @Transactional
    public AjaxResult deleteFile(@PathVariable String sessionId,
                                 @RequestParam String path,
                                 @RequestParam(required = false) Long projectId) throws IOException
    {
        if (remoteWorkspaceService.enabled())
        {
            remoteWorkspaceService.deleteFile(resolveRemoteWorkspaceKey(sessionId, projectId), path);
            return AjaxResult.success("删除成功");
        }
        Path root = resolveWritableWorkspaceRoot(sessionId, projectId);
        if (StringUtils.isEmpty(path))
        {
            throw new ServiceException("参数 path 不能为空");
        }
        Path target = WorkspaceSandbox.resolveSafe(root, path);
        if (!Files.exists(target))
        {
            return AjaxResult.success("文件已被删除");
        }
        if (Files.isDirectory(target))
        {
            deleteRecursively(target);
        }
        else
        {
            Files.deleteIfExists(target);
        }
        return AjaxResult.success("删除成功");
    }

    /** 递归删除目录树(逆序删,先叶子后根)。 */
    private static void deleteRecursively(Path target) throws IOException
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

    private static String fileName(String path)
    {
        String name = path.substring(path.lastIndexOf('/') + 1);
        return StringUtils.isEmpty(name) ? "file" : name;
    }

    // ==================== 会话准入与目录解析 ====================

    // 会话准入统一走 SessionAccessGuard(规则与越权审计集中在那里)，这里只适配身份取值。

    /**
     * 取会话;不存在则先建一个空会话(仅上传用)。
     *
     * <p>标题留空,等首条消息发出时由 {@code ChatRunService.ensureSession} 用消息内容补上 ——
     * 用文件名当标题会很怪:用户真正要问的问题跟文件名往往没什么关系。
     *
     * <p>新建分支的 id 强度校验、并发下的 insert-ignore-再重读,都在 Guard 里统一处理。
     */
    private AiChatSession requireOrCreateSession(String sessionId, Long projectId)
    {
        return sessionGuard.requireOrCreate(sessionId, getUserId(), getUsername(),
                SecurityUtils.isAdmin(getUserId()), "", null, projectId);
    }

    private AiChatSession requireOwnedSession(String sessionId)
    {
        return sessionGuard.requireOwned(sessionId, getUserId(), SecurityUtils.isAdmin(getUserId()));
    }

    /** 与运行创建锁同一条会话主行，防止检查通过后并发启动 Agent。 */
    private AiChatSession requireOwnedSessionForUpdate(String sessionId)
    {
        return sessionGuard.requireOwnedForUpdate(sessionId, getUserId(), SecurityUtils.isAdmin(getUserId()));
    }

    /**
     * 远端模式的工作区键:与工具调用侧 {@code resolveWorkspaceKey} 是同一算法
     * (项目会话 project-{id}、普通 sessionId),保证抽屉看到的就是 OPI 上 AI 写出的目录。
     */
    private String resolveRemoteWorkspaceKey(String sessionId, Long projectId)
    {
        if (projectId != null)
        {
            workspaceScopeService.requireProjectWorkspaceKey(projectId, getUserId());
            return WorkspaceScopeService.projectWorkspaceKey(projectId);
        }
        requireOwnedSession(sessionId);
        return workspaceScopeService.resolveWorkspaceKey(sessionId);
    }

    /**
     * 项目会话尚未发送首条消息时会话行还不存在，但项目工作区已经存在。
     * 此时用 projectId 做归属校验；会话已存在时还要校验它确实属于该项目。
     */
    private Path resolveReadableWorkspaceRoot(String sessionId, Long projectId) throws IOException
    {
        if (projectId == null)
        {
            requireOwnedSession(sessionId);
            return workspaceScopeService.resolveRoot(aiToolProperties, sessionId, false);
        }
        workspaceScopeService.requireProjectWorkspaceKey(projectId, getUserId());
        AiChatSession session = sessionGuard.requireOwnedIfExists(
                sessionId, getUserId(), SecurityUtils.isAdmin(getUserId()));
        if (session != null && !java.util.Objects.equals(session.getProjectId(), projectId))
        {
            throw new ServiceException("会话所属项目不一致");
        }
        return workspaceScopeService.resolveProjectRoot(aiToolProperties, projectId, false);
    }

    private Path resolveWritableWorkspaceRoot(String sessionId, Long projectId) throws IOException
    {
        if (projectId == null)
        {
            AiChatSession session = requireOwnedSessionForUpdate(sessionId);
            requireWorkspaceIdle(session);
            return workspaceScopeService.resolveRoot(aiToolProperties, sessionId, false);
        }
        workspaceScopeService.requireProjectWorkspaceKey(projectId, getUserId());
        AiChatSession session = sessionGuard.requireOwnedIfExists(
                sessionId, getUserId(), SecurityUtils.isAdmin(getUserId()));
        if (session != null && !java.util.Objects.equals(session.getProjectId(), projectId))
        {
            throw new ServiceException("会话所属项目不一致");
        }
        requireProjectWorkspaceIdle(projectId);
        return workspaceScopeService.resolveProjectRoot(aiToolProperties, projectId, false);
    }

    /** 共享项目工作区执行破坏性操作前，项目下任一会话都不能正在运行。 */
    private void requireWorkspaceIdle(AiChatSession session)
    {
        if (session == null)
        {
            throw new ServiceException("会话不存在或无权访问");
        }
        if (session.getProjectId() == null)
        {
            if (aiChatRunMapper.selectActiveBySession(session.getSessionId()) != null)
            {
                throw new ServiceException("会话正在执行，请先停止运行再操作工作区");
            }
            return;
        }
        for (String projectSessionId : aiProjectMapper.selectSessionIdsByProjectId(session.getProjectId()))
        {
            if (aiChatRunMapper.selectActiveBySession(projectSessionId) != null)
            {
                throw new ServiceException("项目中有会话正在执行，请先停止运行再操作工作区");
            }
        }
    }

    private void requireProjectWorkspaceIdle(Long projectId)
    {
        for (String projectSessionId : aiProjectMapper.selectSessionIdsByProjectId(projectId))
        {
            if (aiChatRunMapper.selectActiveBySession(projectSessionId) != null)
            {
                throw new ServiceException("项目中有会话正在执行，请先停止运行再操作工作区");
            }
        }
    }
}