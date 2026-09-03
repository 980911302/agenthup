package com.ruoyi.web.controller.ai;

import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.ruoyi.ai.contract.storage.ObjectReadHandle;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.file.FileUtils;
import com.ruoyi.system.ai.userfile.AiUserFileProperties;
import com.ruoyi.system.ai.userfile.IAiUserFileService;
import com.ruoyi.system.ai.userfile.UserFileQuota;
import com.ruoyi.system.domain.AiUserFile;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 用户个人文件 —— desktop「文件」菜单的后端。
 *
 * <p>与 {@link AiChatWorkspaceController} 的边界:那边是<b>会话沙箱</b>,文件随会话生灭、
 * AI 用 bash/grep 直接在上面跑;这边是<b>个人空间</b>,文件跟人走、存在 S3 兼容存储里,
 * 只有用户主动 {@code /attach} 时才拷一份进某个会话沙箱给模型用。
 *
 * <p>没有 {@code @PreAuthorize}:这是 C 端用户操作自己的文件,不是后台管理的权限点。
 * 归属校验在 Service 与 SQL 的 where 条件里,不靠注解。
 *
 * @author ruoyi
 */
@Tag(name = "个人文件", description = "用户个人文件空间")
@RestController
@RequestMapping("/ai/files")
public class AiUserFileController extends BaseController
{
    @Autowired
    private IAiUserFileService userFileService;

    @Autowired
    private AiUserFileProperties properties;

    /**
     * 文件列表。
     *
     * @param keyword 文件名模糊关键字
     * @param orderBy date(默认) / name / size
     */
    @GetMapping
    public AjaxResult list(@RequestParam(required = false) String keyword,
                           @RequestParam(required = false) String orderBy)
    {
        List<AiUserFile> files = userFileService.list(getUserId(), keyword, orderBy);
        AjaxResult res = AjaxResult.success();
        res.put("files", files.stream().map(AiUserFileController::toView).toList());
        res.put("storageEnabled", userFileService.storageAvailable());
        return res;
    }

    /**
     * 存储配额。前端配额条读这里,不再写死 10MB。
     */
    @GetMapping("/quota")
    public AjaxResult quota()
    {
        UserFileQuota quota = userFileService.quota(getUserId());
        AjaxResult res = AjaxResult.success();
        res.put("usedBytes", quota.usedBytes());
        res.put("quotaBytes", quota.quotaBytes());
        res.put("remainingBytes", quota.remainingBytes());
        res.put("fileCount", quota.fileCount());
        res.put("maxFiles", quota.maxFiles());
        res.put("maxFileBytes", properties.getMaxFileBytes());
        res.put("storageEnabled", userFileService.storageAvailable());
        return res;
    }

    /**
     * 上传。同一用户重复上传相同内容会秒传:不重复占用存储,但仍新增一条记录。
     */
    @PostMapping("/upload")
    public AjaxResult upload(@RequestParam("file") MultipartFile file)
    {
        AiUserFile saved = userFileService.upload(getUserId(), getUsername(), file);
        return AjaxResult.success(toView(saved));
    }

    /**
     * 下载。走后端转发以保证鉴权 —— 需要免转发的高频只读场景用 {@code /preview-url}。
     */
    @GetMapping("/{fileId}/download")
    public void download(@PathVariable Long fileId, HttpServletResponse response) throws IOException
    {
        AiUserFile row = userFileService.get(getUserId(), fileId);
        try (ObjectReadHandle handle = userFileService.open(getUserId(), fileId))
        {
            response.reset();
            response.setContentType(row.getContentType() == null
                    ? "application/octet-stream" : row.getContentType());
            response.setContentLengthLong(handle.object().size());
            FileUtils.setAttachmentResponseHeader(response, row.getFileName());
            try (OutputStream out = response.getOutputStream())
            {
                handle.stream().transferTo(out);
                out.flush();
            }
        }
    }

    /**
     * 预签名直链,供图片/PDF 内联预览用。
     *
     * <p>让浏览器直连对象存储,而不是每张缩略图都占一个 Servlet 线程做转发。
     *
     * @param download true 则链接带另存为响应头
     */
    @GetMapping("/{fileId}/preview-url")
    public AjaxResult previewUrl(@PathVariable Long fileId,
                                 @RequestParam(required = false, defaultValue = "false") boolean download)
    {
        AjaxResult res = AjaxResult.success();
        res.put("url", userFileService.presignedUrl(getUserId(), fileId, download));
        res.put("expiresInSeconds", properties.getPresignTtlSeconds());
        return res;
    }

    /** 重命名。只改展示名。 */
    @PutMapping("/{fileId}/name")
    public AjaxResult rename(@PathVariable Long fileId, @RequestBody Map<String, String> body)
    {
        return AjaxResult.success(toView(
                userFileService.rename(getUserId(), fileId, body == null ? null : body.get("name"))));
    }

    /** 删除。 */
    @DeleteMapping("/{fileId}")
    public AjaxResult remove(@PathVariable Long fileId)
    {
        userFileService.delete(getUserId(), fileId);
        return AjaxResult.success();
    }

    /**
     * 投递到会话工作区,让模型能读到这个文件。
     *
     * <p>返回的 {@code path} 形如 {@code uploads/report.csv},可直接作为消息附件的 path
     * 走既有的 {@code ChatAttachment} 链路。
     *
     * @param sessionId 目标会话
     * @param projectId 项目会话传项目ID,普通会话不传
     */
    @PostMapping("/{fileId}/attach")
    public AjaxResult attach(@PathVariable Long fileId,
                             @RequestParam String sessionId,
                             @RequestParam(required = false) Long projectId)
    {
        AiUserFile row = userFileService.get(getUserId(), fileId);
        String path = userFileService.attachToSession(getUserId(), fileId, sessionId, projectId);
        Map<String, Object> data = new HashMap<>();
        data.put("name", row.getFileName());
        data.put("path", path);
        data.put("mime", row.getContentType());
        data.put("size", row.getFileSize());
        return AjaxResult.success(data);
    }

    /**
     * 把会话工作区里的文件存进个人文件空间。
     *
     * <p>与 {@code /attach} 方向相反 —— 工作区随会话删除而清空,AI 产出的东西要留得住
     * 就得收进个人空间。
     *
     * @param sessionId 来源会话
     * @param path      相对工作区根的路径
     * @param projectId 项目会话传项目ID,普通会话不传
     */
    @PostMapping("/save-from-workspace")
    public AjaxResult saveFromWorkspace(@RequestParam String sessionId,
                                        @RequestParam String path,
                                        @RequestParam(required = false) Long projectId)
    {
        AiUserFile saved = userFileService.saveFromWorkspace(
                getUserId(), getUsername(), sessionId, path, projectId);
        return AjaxResult.success(toView(saved));
    }

    /**
     * 对外视图:{@code objectKey} 不出网。
     *
     * <p>它是存储内部布局,泄露出去等于把桶结构公开;前端拿 {@code fileId} 就够办所有事。
     */
    private static Map<String, Object> toView(AiUserFile row)
    {
        Map<String, Object> view = new HashMap<>();
        view.put("fileId", row.getFileId());
        view.put("name", row.getFileName());
        view.put("size", row.getFileSize());
        view.put("mime", row.getContentType());
        view.put("image", row.isImage());
        view.put("createTime", row.getCreateTime());
        view.put("updateTime", row.getUpdateTime());
        return view;
    }
}
