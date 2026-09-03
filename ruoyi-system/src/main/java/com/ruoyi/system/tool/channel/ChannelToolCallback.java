package com.ruoyi.system.tool.channel;

import com.ruoyi.ai.contract.storage.ObjectReadHandle;
import com.ruoyi.system.ai.event.ChatEventSink;
import com.ruoyi.system.ai.userfile.IAiUserFileService;
import com.ruoyi.system.tool.PromptImages;
import com.ruoyi.system.tool.PromptMediaAware;
import com.ruoyi.system.tool.ToolOutcomeAware;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.content.Media;

import java.util.List;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * 渠道工具的 Spring AI ToolCallback。执行体在客户端，这里只转发并挂起。
 *
 * <p>{@code lastCallOk} 必须 ThreadLocal：callback 实例在并行调用间共享。
 */
public class ChannelToolCallback implements ToolCallback, ToolOutcomeAware, PromptMediaAware
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
    private final ThreadLocal<Boolean> lastCallOk = new ThreadLocal<>();
    // 与 lastCallOk 同理:实例在并行调用间共享,必须 ThreadLocal
    private final ThreadLocal<List<Media>> lastMedia = new ThreadLocal<>();

    public ChannelToolCallback(ChannelToolDef def, ChannelToolBroker broker,
                               String sessionId, String runId, ChatEventSink eventSink, String owner,
                               Long userId, IAiUserFileService userFileService)
    {
        this.def = def;
        this.broker = broker;
        this.sessionId = sessionId;
        this.runId = runId;
        this.eventSink = eventSink;
        this.owner = owner;
        this.userId = userId;
        this.userFileService = userFileService;
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
        ChannelToolBroker.ChannelToolResult result =
                broker.invoke(sessionId, runId, def.name(), toolInput, eventSink, owner, null);
        lastCallOk.set(result.ok());
        if (result.ok() && result.mediaFileId() != null)
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
