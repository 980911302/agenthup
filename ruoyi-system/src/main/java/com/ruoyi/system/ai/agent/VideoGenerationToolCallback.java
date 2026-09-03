package com.ruoyi.system.ai.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.ai.OpenAiCompatibleVideoClient;
import com.ruoyi.system.ai.OpenAiCompatibleVideoClient.VideoGenerateRequest;
import com.ruoyi.system.ai.VideoImageRef;
import com.ruoyi.system.ai.VideoModelFactory;
import com.ruoyi.system.domain.AiModel;
import com.ruoyi.system.tool.AiToolProperties;
import com.ruoyi.system.tool.AttachmentAware;
import com.ruoyi.system.tool.ToolAttachment;
import com.ruoyi.system.tool.WorkspaceContext;
import com.ruoyi.system.tool.WorkspaceSandbox;

/**
 * 文生/图生视频工具,与 {@link ImageGenerationToolCallback} 平行,不进 ai_tool 表。
 */
public class VideoGenerationToolCallback implements ToolCallback, AttachmentAware
{
    public static final String TOOL_NAME = "drawVideo";

    private static final String INPUT_SCHEMA =
            "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"type\":\"object\","
          + "\"properties\":{"
          + "\"prompt\":{\"type\":\"string\",\"description\":\"视频内容。起始帧写法见技能「视频生成」\"},"
          + "\"duration\":{\"type\":\"integer\",\"description\":\"可选时长秒\"},"
          + "\"aspectRatio\":{\"type\":\"string\",\"description\":\"可选画幅,如 16:9\"},"
          + "\"resolution\":{\"type\":\"string\",\"description\":\"可选分辨率,如 720p\"},"
          + "\"referenceImageUrls\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},"
          + "\"description\":\"可选。当前渠道只取第一张当作起始帧;优先用 firstFrameUrl\"},"
          + "\"firstFrameUrl\":{\"type\":\"string\",\"description\":\"可选起始帧路径,详见技能「视频生成」\"}"
          + "}}";

    private final String videoModelCode;
    private final VideoModelFactory videoModelFactory;
    /** 模型行解析器:装配方传 AgentAssemblyCache::modelByCode,drawVideo 也吃到行缓存 */
    private final Function<String, AiModel> modelResolver;
    private final AiToolProperties aiToolProperties;
    private List<ToolAttachment> lastAttachments;

    public VideoGenerationToolCallback(String videoModelCode, VideoModelFactory videoModelFactory,
                                       Function<String, AiModel> modelResolver, AiToolProperties aiToolProperties)
    {
        this.videoModelCode = videoModelCode;
        this.videoModelFactory = videoModelFactory;
        this.modelResolver = modelResolver;
        this.aiToolProperties = aiToolProperties;
    }

    @Override
    public ToolDefinition getToolDefinition()
    {
        return DefaultToolDefinition.builder()
                .name(TOOL_NAME)
                .description(MediaGenSkills.videoToolDescription())
                .inputSchema(INPUT_SCHEMA)
                .build();
    }

    @Override
    public String call(String toolInput)
    {
        return doCall(toolInput);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext)
    {
        return doCall(toolInput);
    }

    @Override
    public List<ToolAttachment> lastAttachments()
    {
        return lastAttachments;
    }

    /** 兼容旧技能里的多张参考图;当前渠道只取第一张当起始帧 */
    static final int MAX_REFERENCE_IMAGES = 7;

    /** 旧字段 referenceImageUrls / imageUrls;仅作 firstFrameUrl 的兜底 */
    private static List<String> parseReferenceRefs(JSONObject input)
    {
        List<String> refs = new ArrayList<>();
        com.alibaba.fastjson2.JSONArray arr = input.getJSONArray("referenceImageUrls");
        if (arr == null)
        {
            arr = input.getJSONArray("imageUrls");
        }
        if (arr != null)
        {
            for (int i = 0; i < arr.size(); i++)
            {
                String s = arr.getString(i);
                if (s != null && !s.isBlank())
                {
                    refs.add(s.trim());
                }
            }
        }
        if (refs.size() > MAX_REFERENCE_IMAGES)
        {
            throw new ServiceException("参考图最多 " + MAX_REFERENCE_IMAGES
                    + " 张,当前 " + refs.size() + " 张");
        }
        return refs;
    }

    private String doCall(String toolInput)
    {
        JSONObject input = parseInput(toolInput);
        String prompt = input.getString("prompt");
        String firstFrame = firstText(input, "firstFrameUrl", "imageUrl", "image_url");
        List<String> references = parseReferenceRefs(input);
        if ((prompt == null || prompt.isBlank()) && firstFrame == null && references.isEmpty())
        {
            throw new ServiceException("drawVideo 需要 prompt、firstFrameUrl 或 referenceImageUrls");
        }
        AiModel model = modelResolver.apply(videoModelCode);
        if (model == null)
        {
            throw new ServiceException("视频模型不存在: " + videoModelCode);
        }
        OpenAiCompatibleVideoClient client = videoModelFactory.get(model.getModelId());
        String sessionId = WorkspaceContext.getWorkspaceKeyOrSessionId();
        String relativePath;
        try
        {
            Path sandboxRoot = WorkspaceSandbox.resolveRoot(aiToolProperties, sessionId);
            Path outputsDir = sandboxRoot.resolve("outputs").normalize();
            if (!outputsDir.startsWith(sandboxRoot))
            {
                throw new SecurityException("outputs 目录越界");
            }
            Files.createDirectories(outputsDir);
            String fileName = "vid-" + UUID.randomUUID() + ".mp4";
            Path target = outputsDir.resolve(fileName).normalize();
            if (!target.startsWith(sandboxRoot))
            {
                throw new SecurityException("视频路径越界");
            }
            Integer duration = input.getInteger("duration");
            String startRef = firstFrame;
            if (startRef == null && !references.isEmpty())
            {
                startRef = references.get(0);
            }
            String resolvedStart = startRef == null || startRef.isBlank() ? null
                    : VideoImageRef.resolve(aiToolProperties, sessionId, startRef);
            client.generateToFile(new VideoGenerateRequest(prompt, duration,
                    firstText(input, "aspectRatio", "aspect_ratio"),
                    firstText(input, "resolution", "size"),
                    resolvedStart, List.of()), target);
            relativePath = "outputs/" + fileName;
        }
        catch (IOException e)
        {
            throw new ServiceException("保存生成视频失败: " + OpenAiCompatibleVideoClient.describeIo(e));
        }

        Long size = fileSizeQuiet(relativePath, sessionId);
        lastAttachments = new ArrayList<>();
        lastAttachments.add(new ToolAttachment("video", relativePath,
                relativePath.substring(relativePath.lastIndexOf('/') + 1),
                size, "video/mp4"));

        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("status", "ok");
        ret.put("path", relativePath);
        ret.put("prompt", prompt);
        ret.put("message", "视频已生成并保存到 " + relativePath);
        return JSON.toJSONString(ret);
    }

    private static JSONObject parseInput(String toolInput)
    {
        if (toolInput == null || toolInput.isEmpty())
        {
            throw new ServiceException("drawVideo 缺少入参");
        }
        try
        {
            return JSON.parseObject(toolInput);
        }
        catch (Exception e)
        {
            JSONObject o = new JSONObject();
            o.put("prompt", toolInput);
            return o;
        }
    }

    private static String firstText(JSONObject input, String... keys)
    {
        for (String key : keys)
        {
            String v = input.getString(key);
            if (v != null && !v.isBlank())
            {
                return v.trim();
            }
        }
        return null;
    }

    private Long fileSizeQuiet(String relativePath, String sessionId)
    {
        try
        {
            Path root = WorkspaceSandbox.resolveRoot(aiToolProperties, sessionId, false);
            Path p = WorkspaceSandbox.resolveSafe(root, relativePath);
            return Files.size(p);
        }
        catch (Exception e)
        {
            return null;
        }
    }
}
