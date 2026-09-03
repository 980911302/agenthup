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
import com.ruoyi.system.ai.OpenAiCompatibleTtsClient;
import com.ruoyi.system.ai.OpenAiCompatibleTtsClient.TtsGenerateRequest;
import com.ruoyi.system.ai.OpenAiCompatibleVideoClient;
import com.ruoyi.system.ai.TtsModelFactory;
import com.ruoyi.system.ai.TtsVoiceCatalog;
import com.ruoyi.system.domain.AiModel;
import com.ruoyi.system.tool.AiToolProperties;
import com.ruoyi.system.tool.AttachmentAware;
import com.ruoyi.system.tool.ToolAttachment;
import com.ruoyi.system.tool.WorkspaceContext;
import com.ruoyi.system.tool.WorkspaceSandbox;

/**
 * 文本转语音工具,与 {@link VideoGenerationToolCallback} 平行,不进 ai_tool 表。
 */
public class SpeechGenerationToolCallback implements ToolCallback, AttachmentAware
{
    public static final String TOOL_NAME = "speak";

    private static final String INPUT_SCHEMA =
            "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"type\":\"object\","
          + "\"properties\":{"
          + "\"text\":{\"type\":\"string\",\"description\":\"要朗读的文本。情绪标签写法见技能「语音合成」\"},"
          + "\"voice\":{\"type\":\"string\",\"description\":\""
          + TtsVoiceCatalog.voiceParamDescription() + "\"},"
          + "\"instructions\":{\"type\":\"string\",\"description\":\"可选,自然语言控制语速/情感/风格\"}"
          + "},\"required\":[\"text\"]}";

    private final String ttsModelCode;
    private final TtsModelFactory ttsModelFactory;
    /** 模型行解析器:装配方传 AgentAssemblyCache::modelByCode,speak 也吃到行缓存 */
    private final Function<String, AiModel> modelResolver;
    private final AiToolProperties aiToolProperties;
    private List<ToolAttachment> lastAttachments;

    public SpeechGenerationToolCallback(String ttsModelCode, TtsModelFactory ttsModelFactory,
                                        Function<String, AiModel> modelResolver, AiToolProperties aiToolProperties)
    {
        this.ttsModelCode = ttsModelCode;
        this.ttsModelFactory = ttsModelFactory;
        this.modelResolver = modelResolver;
        this.aiToolProperties = aiToolProperties;
    }

    @Override
    public ToolDefinition getToolDefinition()
    {
        return DefaultToolDefinition.builder()
                .name(TOOL_NAME)
                .description(TtsVoiceCatalog.toolDescription())
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

    private String doCall(String toolInput)
    {
        JSONObject input = parseInput(toolInput);
        String text = firstText(input, "text", "prompt", "input");
        if (text == null || text.isBlank())
        {
            throw new ServiceException("speak 需要 text");
        }
        AiModel model = modelResolver.apply(ttsModelCode);
        if (model == null)
        {
            throw new ServiceException("语音模型不存在: " + ttsModelCode);
        }
        OpenAiCompatibleTtsClient client = ttsModelFactory.get(model.getModelId());
        String sessionId = WorkspaceContext.getWorkspaceKeyOrSessionId();
        String voice = TtsVoiceCatalog.resolve(firstText(input, "voice"));
        String relativePath;
        String mime = "audio/mpeg";
        try
        {
            Path sandboxRoot = WorkspaceSandbox.resolveRoot(aiToolProperties, sessionId);
            Path outputsDir = sandboxRoot.resolve("outputs").normalize();
            if (!outputsDir.startsWith(sandboxRoot))
            {
                throw new SecurityException("outputs 目录越界");
            }
            Files.createDirectories(outputsDir);
            String stem = "aud-" + UUID.randomUUID();
            Path tmp = outputsDir.resolve(stem + ".bin").normalize();
            if (!tmp.startsWith(sandboxRoot))
            {
                throw new SecurityException("语音路径越界");
            }
            mime = client.generateToFile(new TtsGenerateRequest(text, voice,
                    firstText(input, "instructions", "instruction")), tmp);
            String ext = (mime != null && mime.toLowerCase().contains("wav")) ? ".wav" : ".mp3";
            Path target = outputsDir.resolve(stem + ext).normalize();
            Files.move(tmp, target);
            relativePath = "outputs/" + target.getFileName();
        }
        catch (IOException e)
        {
            throw new ServiceException("保存合成语音失败: " + OpenAiCompatibleVideoClient.describeIo(e));
        }

        Long size = fileSizeQuiet(relativePath, sessionId);
        lastAttachments = new ArrayList<>();
        lastAttachments.add(new ToolAttachment("audio", relativePath,
                relativePath.substring(relativePath.lastIndexOf('/') + 1),
                size, mime == null ? "audio/mpeg" : mime));

        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("status", "ok");
        ret.put("path", relativePath);
        ret.put("text", text);
        ret.put("voice", voice);
        ret.put("voiceName", TtsVoiceCatalog.displayName(voice));
        ret.put("message", "语音已生成并保存到 " + relativePath + "，音色 " + TtsVoiceCatalog.displayName(voice));
        return JSON.toJSONString(ret);
    }

    private static JSONObject parseInput(String toolInput)
    {
        if (toolInput == null || toolInput.isEmpty())
        {
            throw new ServiceException("speak 缺少入参");
        }
        try
        {
            return JSON.parseObject(toolInput);
        }
        catch (Exception e)
        {
            JSONObject o = new JSONObject();
            o.put("text", toolInput);
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
