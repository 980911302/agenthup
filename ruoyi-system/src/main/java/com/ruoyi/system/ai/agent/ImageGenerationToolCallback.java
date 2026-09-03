package com.ruoyi.system.ai.agent;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.ai.ImageModelFactory;
import com.ruoyi.system.domain.AiModel;
import com.ruoyi.system.tool.AttachmentAware;
import com.ruoyi.system.tool.AiToolProperties;
import com.ruoyi.system.tool.ToolAttachment;
import com.ruoyi.system.tool.WorkspaceContext;
import com.ruoyi.system.tool.WorkspaceSandbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImageOptions;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.ai.openai.OpenAiImageOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.chat.model.ToolContext;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * 把智能体绑定的生图模型包装成 {@link ToolCallback}。
 *
 * <p>与 {@link SubAgentToolCallback}(子智能体包成工具)同模式:不是 Spring bean,
 * 每次 {@link AgentContextFactory#doBuild} 发现 agent.imageModelCode 非空时 new 一个,
 * 被 {@link com.ruoyi.system.tool.RecordingToolCallback} 包装后自动获得记账/事件流/会话绑定。
 *
 * <p>用户视角:智能体配了生图模型就会画图,没配就没有 drawImage 工具。
 *
 * <p>出图后立即下载到会话沙箱 {@code outputs/} 下(生图 API 返回的多是临时 URL,有时效,
 * 不能只存 URL),通过 {@link AttachmentAware} 把图片元数据声明给外层,写进事件流与消息表,
 * 前端据此内联渲染。
 *
 * @author ruoyi
 */
public class ImageGenerationToolCallback implements ToolCallback, AttachmentAware {

    private static final Logger log = LoggerFactory.getLogger(ImageGenerationToolCallback.class);

    /** 工具名固定,LLM 看到后据此发起 tool call */
    private static final String TOOL_NAME = "drawImage";

    private static final String INPUT_SCHEMA =
            "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"type\":\"object\","
          + "\"properties\":{"
          + "\"prompt\":{\"type\":\"string\",\"description\":\"画面描述。参考图与 @image 写法见技能「图片生成」\"},"
          + "\"imageUrls\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},"
          + "\"description\":\"可选参考图路径,详见技能「图片生成」\"},"
          + "\"size\":{\"type\":\"string\",\"description\":\"可选尺寸,如 1024x1024\"}"
          + "},\"required\":[\"prompt\"]}";

    private final String imageModelCode;
    private final ImageModelFactory imageModelFactory;
    /** 模型行解析器:装配方传 AgentAssemblyCache::modelByCode,drawImage 也吃到行缓存 */
    private final Function<String, AiModel> modelResolver;
    private final AiToolProperties aiToolProperties;

    /** 最近一次 call() 产出的附件,供外层 RecordingToolCallback 取用 */
    private List<ToolAttachment> lastAttachments;

    public ImageGenerationToolCallback(String imageModelCode,
                                       ImageModelFactory imageModelFactory,
                                       Function<String, AiModel> modelResolver,
                                       AiToolProperties aiToolProperties) {
        this.imageModelCode = imageModelCode;
        this.imageModelFactory = imageModelFactory;
        this.modelResolver = modelResolver;
        this.aiToolProperties = aiToolProperties;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return DefaultToolDefinition.builder()
                .name(TOOL_NAME)
                .description(MediaGenSkills.imageToolDescription())
                .inputSchema(INPUT_SCHEMA)
                .build();
    }

    @Override
    public String call(String toolInput) {
        return doCall(toolInput);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return doCall(toolInput);
    }

    @Override
    public List<ToolAttachment> lastAttachments() {
        return lastAttachments;
    }

    /** 上游对单次编辑的参考图数量有上限,超了直接报错比让上游截断更清楚 */
    static final int MAX_REFERENCE_IMAGES = 3;

    /** 兼容单张 imageUrl 与多张 imageUrls,统一收成有序列表(顺序即 @image1/@image2 的编号) */
    private static List<String> parseImageRefs(JSONObject input) {
        List<String> refs = new ArrayList<>();
        com.alibaba.fastjson2.JSONArray arr = input.getJSONArray("imageUrls");
        if (arr != null) {
            for (int i = 0; i < arr.size(); i++) {
                String s = arr.getString(i);
                if (s != null && !s.isBlank()) {
                    refs.add(s.trim());
                }
            }
        }
        String single = input.getString("imageUrl");
        if (single != null && !single.isBlank() && !refs.contains(single.trim())) {
            refs.add(single.trim());
        }
        if (refs.size() > MAX_REFERENCE_IMAGES) {
            throw new ServiceException("参考图最多 " + MAX_REFERENCE_IMAGES
                    + " 张,当前 " + refs.size() + " 张");
        }
        return refs;
    }

    private List<com.ruoyi.system.ai.ImageEditClient.ImagePart> toParts(List<String> refs, String sessionId) {
        List<com.ruoyi.system.ai.ImageEditClient.ImagePart> parts = new ArrayList<>(refs.size());
        for (String ref : refs) {
            com.ruoyi.system.ai.VideoImageRef.ImageBytes b =
                    com.ruoyi.system.ai.VideoImageRef.readBytes(aiToolProperties, sessionId, ref);
            parts.add(new com.ruoyi.system.ai.ImageEditClient.ImagePart(
                    b.bytes(), b.filename(), b.mimeType()));
        }
        return parts;
    }

    private String doCall(String toolInput) {
        JSONObject input = parseInput(toolInput);
        String prompt = input.getString("prompt");
        if (prompt == null || prompt.isEmpty()) {
            throw new ServiceException("drawImage 缺少 prompt 参数");
        }
        String size = input.getString("size");
        List<String> refs = parseImageRefs(input);

        AiModel model = modelResolver.apply(imageModelCode);
        if (model == null) {
            throw new ServiceException("生图模型不存在: " + imageModelCode);
        }

        String sessionId = WorkspaceContext.getWorkspaceKeyOrSessionId();
        // 参考图为空走文生图(Spring AI 的 ImageModel);非空走图生图(自建 multipart 客户端)。
        // 分两条路是因为 OpenAiImageRequest 是字段固定的 record,塞不进参考图。
        byte[] editedBytes = refs.isEmpty() ? null
                : imageModelFactory.getEditClient(model.getModelId())
                        .edit(prompt, toParts(refs, sessionId), size);

        org.springframework.ai.image.Image img = null;
        if (editedBytes == null) {
            ImageModel imageModel = imageModelFactory.get(model.getModelId());
            ImageOptions options = buildOptions(size);
            ImagePrompt imagePrompt = options != null
                    ? new ImagePrompt(prompt, options)
                    : new ImagePrompt(prompt);
            ImageResponse response = imageModel.call(imagePrompt);
            if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
                throw new ServiceException("生图模型未返回结果");
            }
            img = response.getResult().getOutput();
        }

        // 下载到会话沙箱(生图 URL 多有时效,必须立即落盘)
        // sessionId 由 RecordingToolCallback 外层绑定的 WorkspaceContext 提供
        String relativePath;
        try {
            Path sandboxRoot = WorkspaceSandbox.resolveRoot(aiToolProperties, sessionId);
            Path outputsDir = sandboxRoot.resolve("outputs").normalize();
            if (!outputsDir.startsWith(sandboxRoot)) {
                throw new SecurityException("outputs 目录越界");
            }
            Files.createDirectories(outputsDir);
            String fileName = "img-" + UUID.randomUUID() + ".png";
            Path target = outputsDir.resolve(fileName).normalize();
            if (!target.startsWith(sandboxRoot)) {
                throw new SecurityException("图片路径越界");
            }
            if (editedBytes != null) {
                Files.write(target, editedBytes);
            } else if (img.getUrl() != null && !img.getUrl().isEmpty()) {
                downloadTo(img.getUrl(), target);
            } else if (img.getB64Json() != null && !img.getB64Json().isEmpty()) {
                Files.write(target, Base64.getDecoder().decode(img.getB64Json()));
            } else {
                throw new ServiceException("生图模型既未返回 URL 也未返回 base64");
            }
            relativePath = "outputs/" + fileName;
        } catch (IOException e) {
            throw new ServiceException("保存生成图片失败: " + e.getMessage());
        }

        long fileSize = fileSizeQuiet(relativePath, sessionId);

        // 声明附件,外层 RecordingToolCallback 会写进事件流与消息表
        List<ToolAttachment> atts = new ArrayList<>();
        atts.add(new ToolAttachment("image", relativePath,
                relativePath.substring(relativePath.lastIndexOf('/') + 1),
                fileSize, "image/png"));
        this.lastAttachments = atts;

        // 返回给 LLM 的文本(模型会基于此向用户复述)
        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("status", "ok");
        ret.put("path", relativePath);
        ret.put("prompt", prompt);
        ret.put("message", "图片已生成并保存到 " + relativePath);
        return JSON.toJSONString(ret);
    }

    /**
     * 构造请求级 ImageOptions。
     *
     * <p>OpenAI 生图请求体只有 {@code size} 字符串字段(如 {@code "1024x1024"}),
     * 没有 width/height -- 所以把 LLM 传来的尺寸归一化成 "宽x高" 字符串;
     * 解析失败返回 null,不传 options,用服务端默认尺寸。
     */
    private ImageOptions buildOptions(String size) {
        if (size == null || size.isEmpty()) {
            return null;
        }
        int[] wh = parseSize(size);
        if (wh == null) {
            return null;
        }
        // builder 没有 size 方法,构造后 set
        OpenAiImageOptions options = OpenAiImageOptions.builder().build();
        options.setSize(wh[0] + "x" + wh[1]);
        return options;
    }

    /** 解析 "1024x1024" -> [1024,1024];失败返回 null(× 与 * 归一化成 x) */
    private int[] parseSize(String size) {
        String s = size.trim().toLowerCase().replace("×", "x").replace("*", "x");
        String[] parts = s.split("x");
        if (parts.length == 2) {
            try {
                int w = Integer.parseInt(parts[0].trim());
                int h = Integer.parseInt(parts[1].trim());
                return new int[]{w, h};
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    /** 兼容模型传 JSON 或裸字符串 */
    private JSONObject parseInput(String toolInput) {
        if (toolInput == null || toolInput.isEmpty()) {
            throw new ServiceException("drawImage 缺少入参");
        }
        try {
            return JSON.parseObject(toolInput);
        } catch (Exception e) {
            // 非 JSON,当裸字符串当 prompt
            JSONObject o = new JSONObject();
            o.put("prompt", toolInput);
            return o;
        }
    }

    /** 下载远程图片到本地 */
    private void downloadTo(String url, Path target) throws IOException {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .GET()
                    .build();
            HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() / 100 != 2) {
                throw new IOException("下载图片 HTTP " + resp.statusCode());
            }
            Files.write(target, resp.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("下载图片被中断", e);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("下载图片失败: " + e.getMessage(), e);
        }
    }

    /** 读取已保存图片的字节数;失败返回 null */
    private Long fileSizeQuiet(String relativePath, String sessionId) {
        try {
            Path root = WorkspaceSandbox.resolveRoot(aiToolProperties, sessionId, false);
            Path p = WorkspaceSandbox.resolveSafe(root, relativePath);
            return Files.size(p);
        } catch (Exception e) {
            return null;
        }
    }
}
