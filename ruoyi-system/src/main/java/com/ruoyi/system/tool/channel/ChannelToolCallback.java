package com.ruoyi.system.tool.channel;

import com.ruoyi.ai.contract.storage.ObjectReadHandle;
import com.ruoyi.system.ai.event.ChatEventSink;
import com.ruoyi.system.ai.userfile.IAiUserFileService;
import com.ruoyi.system.tool.AiToolProperties;
import com.ruoyi.system.tool.AttachmentAware;
import com.ruoyi.system.tool.PromptImages;
import com.ruoyi.system.tool.PromptMediaAware;
import com.ruoyi.system.tool.RemoteWorkspaceService;
import com.ruoyi.system.tool.ToolAttachment;
import com.ruoyi.system.tool.ToolOutcomeAware;
import com.ruoyi.system.tool.WorkspaceSandbox;
import com.ruoyi.system.tool.WorkspaceTreeWalker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.content.Media;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.net.URLConnection;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * 渠道工具的 Spring AI ToolCallback。执行体在客户端，这里只转发并挂起。
 *
 * <p>{@code lastCallOk} 必须 ThreadLocal：callback 实例在并行调用间共享。
 */
public class ChannelToolCallback implements ToolCallback, ToolOutcomeAware, PromptMediaAware, AttachmentAware
{
    private static final Logger log = LoggerFactory.getLogger(ChannelToolCallback.class);

    public static final String SOURCE = "channel";

    private final ChannelToolDef def;
    private final ChannelToolBroker broker;
    private final String sessionId;
    private final String runId;
    private final ChatEventSink eventSink;
    private final String owner;
    private final Long userId;
    private final IAiUserFileService userFileService;
    private final String workspaceKey;
    private final AiToolProperties toolProperties;
    private final RemoteWorkspaceService remoteWorkspaceService;
    private final ThreadLocal<Boolean> lastCallOk = new ThreadLocal<>();
    // 与 lastCallOk 同理:实例在并行调用间共享,必须 ThreadLocal
    private final ThreadLocal<List<Media>> lastMedia = new ThreadLocal<>();
    private final ThreadLocal<List<ToolAttachment>> lastAttachments = new ThreadLocal<>();

    public ChannelToolCallback(ChannelToolDef def, ChannelToolBroker broker,
                               String sessionId, String runId, ChatEventSink eventSink, String owner,
                               Long userId, IAiUserFileService userFileService)
    {
        this(def, broker, sessionId, runId, eventSink, owner, userId, userFileService,
                sessionId, null, null);
    }

    public ChannelToolCallback(ChannelToolDef def, ChannelToolBroker broker,
                               String sessionId, String runId, ChatEventSink eventSink, String owner,
                               Long userId, IAiUserFileService userFileService,
                               String workspaceKey, AiToolProperties toolProperties,
                               RemoteWorkspaceService remoteWorkspaceService)
    {
        this.def = def;
        this.broker = broker;
        this.sessionId = sessionId;
        this.runId = runId;
        this.eventSink = eventSink;
        this.owner = owner;
        this.userId = userId;
        this.userFileService = userFileService;
        this.workspaceKey = workspaceKey;
        this.toolProperties = toolProperties;
        this.remoteWorkspaceService = remoteWorkspaceService;
    }

    @Override
    public ToolDefinition getToolDefinition()
    {
        return DefaultToolDefinition.builder()
                .name(def.name())
                .description(def.description())
                .inputSchema(def.inputSchemaJson())
                .build();
    }

    @Override
    public String call(String toolInput)
    {
        lastCallOk.remove();
        lastMedia.remove();
        lastAttachments.remove();
        ChannelToolBroker.ChannelToolResult result =
                broker.invoke(sessionId, runId, def.name(), toolInput, eventSink, owner, null);
        lastCallOk.set(result.ok());
        if (result.ok() && result.workspacePath() != null && !result.workspacePath().isBlank())
        {
            List<Media> media = loadWorkspaceMedia(result.workspacePath());
            if (media != null)
            {
                lastMedia.set(media);
            }
        }
        else if (result.ok() && result.mediaFileId() != null)
        {
            List<Media> media = loadMedia(result.mediaFileId());
            if (media != null)
            {
                lastMedia.set(media);
            }
        }
        return result.text() != null ? result.text() : "";
    }

    @Override
    public String call(String toolInput, ToolContext toolContext)
    {
        return call(toolInput);
    }

    @Override
    public Boolean lastCallOk()
    {
        Boolean ok = lastCallOk.get();
        lastCallOk.remove();
        return ok;
    }

    @Override
    public List<Media> lastPromptMedia()
    {
        List<Media> media = lastMedia.get();
        lastMedia.remove();
        return media;
    }

    @Override
    public List<ToolAttachment> lastAttachments()
    {
        List<ToolAttachment> attachments = lastAttachments.get();
        lastAttachments.remove();
        return attachments;
    }

    /**
     * 新协议从当前工作区读取截图。MCP 模式经 RemoteWorkspaceService 访问 OPI；local
     * 模式直接读会话沙箱。读取成功后，同一路径既进入模型视觉上下文，也作为前端附件。
     */
    private List<Media> loadWorkspaceMedia(String relativePath)
    {
        if (toolProperties == null || workspaceKey == null || workspaceKey.isBlank())
        {
            return null;
        }
        if (relativePath.length() > 500)
        {
            log.warn("渠道工具工作区图片路径过长，已拒绝: tool={}", def.name());
            return null;
        }
        try
        {
            byte[] bytes;
            if (remoteWorkspaceService != null && remoteWorkspaceService.enabled())
            {
                Object remoteSize = remoteWorkspaceService.file(workspaceKey, relativePath).get("size");
                if (remoteSize instanceof Number size
                        && size.longValue() > WorkspaceTreeWalker.MAX_UPLOAD_BYTES)
                {
                    throw new IllegalStateException("图片超过工作区媒体读取上限");
                }
                bytes = remoteWorkspaceService.download(workspaceKey, relativePath);
            }
            else
            {
                Path root = WorkspaceSandbox.resolveRoot(toolProperties, workspaceKey, false);
                Path target = WorkspaceSandbox.resolveSafe(root, relativePath);
                if (!Files.isRegularFile(target))
                {
                    throw new IllegalStateException("文件不存在");
                }
                if (Files.size(target) > WorkspaceTreeWalker.MAX_UPLOAD_BYTES)
                {
                    throw new IllegalStateException("图片超过工作区媒体读取上限");
                }
                bytes = Files.readAllBytes(target);
            }
            Media media = PromptImages.fromStream(new ByteArrayInputStream(bytes));
            if (media == null)
            {
                return null;
            }
            String name = fileName(relativePath);
            String mime = URLConnection.guessContentTypeFromName(name);
            lastAttachments.set(List.of(new ToolAttachment(
                    "image", relativePath, name, (long) bytes.length,
                    mime != null ? mime : "image/png")));
            return List.of(media);
        }
        catch (Exception e)
        {
            // 文本结果与工作区文件已经成功，视觉加载失败不应把整次工具调用改判失败。
            log.warn("渠道工具工作区图片取回失败: tool={} path={}: {}",
                    def.name(), relativePath, e.getMessage());
            return null;
        }
    }

    private static String fileName(String path)
    {
        String normalized = path == null ? "screenshot.png" : path.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    /**
     * 客户端只回图片在个人文件里的 id,图片本体不过 WebSocket —— base64 会撑爆
     * ai_chat_message 审计流与工具字符预算。这里按 id 取回、缩到模型尺寸。
     *
     * <p>归属由 {@code userFileService.open(userId, fileId)} 保证:它按 userId 过滤,
     * 模型指别人的 fileId 取不到。
     */
    private List<Media> loadMedia(Long fileId)
    {
        if (userId == null || userFileService == null)
        {
            return null;
        }
        try (ObjectReadHandle handle = userFileService.open(userId, fileId))
        {
            Media media = PromptImages.fromStream(handle.stream());
            return media != null ? List.of(media) : null;
        }
        catch (RuntimeException e)
        {
            // 取不到图不该打断工具:文本结果已经回给模型了
            log.warn("渠道工具图片取回失败: tool={} fileId={}: {}", def.name(), fileId, e.getMessage());
            return null;
        }
    }
}
