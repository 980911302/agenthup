package com.ruoyi.system.ai.agent;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.content.Media;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.core.io.FileSystemResource;
import org.springframework.util.MimeTypeUtils;
import org.springframework.util.StringUtils;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.tool.AiToolProperties;
import com.ruoyi.system.tool.AttachmentAware;
import com.ruoyi.system.tool.PromptMediaAware;
import com.ruoyi.system.tool.ToolAttachment;
import com.ruoyi.system.tool.WorkspaceContext;
import com.ruoyi.system.tool.WorkspaceSandbox;

/**
 * 无头浏览器截图。图片同时走 {@link AttachmentAware}(前端)和 {@link PromptMediaAware}(下一轮模型上下文)。
 *
 * <p><b>两条出口给的不是同一张图</b>:前端拿原始 PNG(用户要看高清),模型拿
 * {@link #shrinkForModel} 压出来的 JPEG。原图直发上下文会因请求体过大被上游网关 400 拒掉,
 * 且超出模型有效分辨率的像素纯属浪费 token。
 */
public class ScreenshotToolCallback implements ToolCallback, AttachmentAware, PromptMediaAware
{
    private static final Logger log = LoggerFactory.getLogger(ScreenshotToolCallback.class);

    static final String TOOL_NAME = "captureScreenshot";

    private static final String INPUT_SCHEMA =
            "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"type\":\"object\","
          + "\"properties\":{"
          + "\"url\":{\"type\":\"string\",\"description\":\"要截图的页面 URL,如 http://127.0.0.1:4173/\"},"
          + "\"width\":{\"type\":\"integer\",\"description\":\"视口宽,默认 1440\"},"
          + "\"height\":{\"type\":\"integer\",\"description\":\"视口高,默认 900\"},"
          + "\"fullPage\":{\"type\":\"boolean\",\"description\":\"是否截整页,默认 false。"
          + "整页图缩放后细节会糊,查配色/文案/间距请用默认视口分屏截\"}"
          + "},\"required\":[\"url\"]}";

    private static final String[] BROWSER_CANDIDATES = {
            "chromium", "chromium-browser", "google-chrome", "google-chrome-stable", "chrome"
    };

    /**
     * fullPage 未显式指定高度时的默认视口高。
     *
     * <p>原值 4000:1440x4000 的 PNG 实测 3.6MB,base64 后约 5MB,上游网关直接 400
     * (run eada2c77 子智能体两次都栽在这)。而且 4000px 高的图按长边缩到 1568 后宽只剩
     * 565px,配色和布局细节全糊,自检等于没做。2400 是「一屏半到两屏」的折中。
     */
    private static final int FULL_PAGE_HEIGHT = 2400;

    /**
     * 进模型上下文的图片长边上限。主流 vision 模型的有效分辨率就在这个量级,
     * 再大只是把 token 烧在模型看不到的像素上。
     */
    private static final int CONTEXT_MAX_EDGE = 1568;

    /** 进模型上下文的图片体积上限(字节)。超过就继续降质量重编码。 */
    private static final long CONTEXT_MAX_BYTES = 1_000_000L;

    /** 递降的 JPEG 质量档位:第一个能压进 {@link #CONTEXT_MAX_BYTES} 的即采用。 */
    private static final float[] JPEG_QUALITIES = {0.82f, 0.65f, 0.5f};

    private final AiToolProperties aiToolProperties;
    private List<ToolAttachment> lastAttachments;
    private List<Media> lastPromptMedia;

    public ScreenshotToolCallback(AiToolProperties aiToolProperties)
    {
        this.aiToolProperties = aiToolProperties;
    }

    @Override
    public ToolDefinition getToolDefinition()
    {
        return DefaultToolDefinition.builder()
                .name(TOOL_NAME)
                .description("打开页面并截图。网页/前端任务在宣称完成前必须调用,检查布局、配色、图片是否加载。"
                        + "返回截图路径;截图会压缩后进入你的下一轮视觉上下文。"
                        + "默认视口(1440x900)截首屏最清晰;要看下方版块请调 height 或分屏多截几张,"
                        + "不要用 fullPage 截超长图 —— 缩放后文字和配色都看不清。")
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

    @Override
    public List<Media> lastPromptMedia()
    {
        return lastPromptMedia;
    }

    private String doCall(String toolInput)
    {
        lastAttachments = null;
        lastPromptMedia = null;
        JSONObject input = parse(toolInput);
        String url = input.getString("url");
        if (!StringUtils.hasText(url))
        {
            throw new ServiceException("captureScreenshot 缺少 url");
        }
        if (!url.startsWith("http://") && !url.startsWith("https://"))
        {
            throw new ServiceException("url 必须以 http:// 或 https:// 开头");
        }
        int width = input.getIntValue("width", 1440);
        int height = input.getIntValue("height", 900);
        boolean fullPage = Boolean.TRUE.equals(input.getBoolean("fullPage"));
        if (width < 320 || width > 3840 || height < 240 || height > 8000)
        {
            throw new ServiceException("width/height 超出范围");
        }
        // fullPage 只决定「没显式给高度时」的默认值。原先无条件 Math.max(height, 4000),
        // 模型就算主动传了 height=1200 也会被强拉到 4000,反而拿不到清晰的自检图。
        if (fullPage && !input.containsKey("height"))
        {
            height = FULL_PAGE_HEIGHT;
        }

        String sessionId = WorkspaceContext.getWorkspaceKeyOrSessionId();
        try
        {
            Path sandboxRoot = WorkspaceSandbox.resolveRoot(aiToolProperties, sessionId);
            Path outputsDir = sandboxRoot.resolve("outputs").normalize();
            if (!outputsDir.startsWith(sandboxRoot))
            {
                throw new SecurityException("outputs 目录越界");
            }
            Files.createDirectories(outputsDir);
            String fileName = "shot-" + UUID.randomUUID() + ".png";
            Path target = outputsDir.resolve(fileName).normalize();
            if (!target.startsWith(sandboxRoot))
            {
                throw new SecurityException("截图路径越界");
            }
            capture(url, width, height, target);
            String relativePath = "outputs/" + fileName;
            long size = Files.size(target);
            // 前端附件始终是原图:用户点开要看高清的
            lastAttachments = List.of(new ToolAttachment("image", relativePath, fileName, size, "image/png"));

            // 进模型上下文的是压缩副本。原图直发会被上游网关按请求体大小拒掉(400),
            // 而且超出模型有效分辨率的像素只是白烧 token。
            Map<String, Object> ret = new LinkedHashMap<>();
            Path forModel = shrinkForModel(target, outputsDir);
            if (forModel != null)
            {
                lastPromptMedia = List.of(new Media(MimeTypeUtils.IMAGE_JPEG, new FileSystemResource(forModel)));
                ret.put("contextImage", readableSize(Files.size(forModel)));
            }
            else if (size <= CONTEXT_MAX_BYTES)
            {
                // 压缩链路不可用(ImageIO 缺编解码器等),原图够小就照旧直发
                lastPromptMedia = List.of(new Media(MimeTypeUtils.IMAGE_PNG, new FileSystemResource(target)));
                ret.put("contextImage", readableSize(size));
            }
            else
            {
                // 压不了又太大:宁可不给模型看,也不能让整轮请求被上游打回
                lastPromptMedia = null;
                log.warn("截图 {} 体积 {} 超上限且压缩失败,本次不进模型上下文", relativePath, size);
            }

            ret.put("status", "ok");
            ret.put("path", relativePath);
            ret.put("width", width);
            ret.put("height", height);
            ret.put("message", lastPromptMedia == null
                    ? "截图已生成 " + relativePath + " (" + width + "x" + height + "),但体积过大无法进入视觉上下文,"
                            + "请缩小 width/height 或关掉 fullPage 重截。"
                    : "截图已生成 " + relativePath + " (" + width + "x" + height + "),请查看图片检查页面。");
            return JSON.toJSONString(ret);
        }
        catch (ServiceException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            log.warn("截图失败 url={}: {}", url, e.getMessage());
            throw new ServiceException("截图失败: " + e.getMessage());
        }
    }

    private static JSONObject parse(String toolInput)
    {
        if (!StringUtils.hasText(toolInput))
        {
            return new JSONObject();
        }
        try
        {
            return JSON.parseObject(toolInput);
        }
        catch (Exception e)
        {
            throw new ServiceException("captureScreenshot 参数不是 JSON");
        }
    }

    /**
     * 生成进模型上下文用的压缩副本:长边缩到 {@link #CONTEXT_MAX_EDGE} 以内,转 JPEG 并按
     * {@link #JPEG_QUALITIES} 递降质量直到压进 {@link #CONTEXT_MAX_BYTES}。
     *
     * <p>原 PNG 保持不动 —— 前端附件要的是高清图,模型要的是能塞进请求体的图,两者分开。
     *
     * @return 压缩副本路径;ImageIO 读不出或写不出时返回 {@code null},由调用方决定兜底策略
     */
    static Path shrinkForModel(Path png, Path outputsDir)
    {
        try
        {
            BufferedImage src = ImageIO.read(png.toFile());
            if (src == null)
            {
                return null;
            }
            int w = src.getWidth();
            int h = src.getHeight();
            double scale = Math.min(1.0d, (double) CONTEXT_MAX_EDGE / Math.max(w, h));
            int tw = Math.max(1, (int) Math.round(w * scale));
            int th = Math.max(1, (int) Math.round(h * scale));

            // JPEG 不支持透明:先铺白底再画,否则 PNG 的透明区会变成黑块
            BufferedImage dst = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = dst.createGraphics();
            try
            {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, tw, th);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g.drawImage(src, 0, 0, tw, th, null);
            }
            finally
            {
                g.dispose();
            }

            String name = png.getFileName().toString().replaceFirst("\\.png$", "") + "-ctx.jpg";
            Path out = outputsDir.resolve(name).normalize();
            if (!out.startsWith(outputsDir))
            {
                return null;
            }
            for (float quality : JPEG_QUALITIES)
            {
                writeJpeg(dst, out, quality);
                if (Files.size(out) <= CONTEXT_MAX_BYTES)
                {
                    return out;
                }
            }
            // 最低档仍超标:尺寸已经受控,交回去总比不给模型看强
            return out;
        }
        catch (Exception e)
        {
            log.warn("截图压缩失败 {}: {}", png.getFileName(), e.getMessage());
            return null;
        }
    }

    private static void writeJpeg(BufferedImage image, Path target, float quality) throws IOException
    {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext())
        {
            throw new IOException("JVM 无 JPEG 编码器");
        }
        ImageWriter writer = writers.next();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(target.toFile()))
        {
            writer.setOutput(stream);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed())
            {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(quality);
            }
            writer.write(null, new IIOImage(image, null, null), param);
        }
        finally
        {
            writer.dispose();
        }
    }

    private static String readableSize(long bytes)
    {
        return bytes >= 1024 * 1024
                ? String.format("%.1fMB", bytes / 1024d / 1024d)
                : Math.max(1L, bytes / 1024L) + "KB";
    }

    static void capture(String url, int width, int height, Path target) throws IOException, InterruptedException
    {
        String bin = resolveBrowser();
        if (bin == null)
        {
            throw new ServiceException("沙箱未安装无头浏览器(chromium/chrome),无法截图");
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(bin);
        cmd.add("--headless=new");
        cmd.add("--disable-gpu");
        cmd.add("--no-sandbox");
        cmd.add("--disable-dev-shm-usage");
        cmd.add("--hide-scrollbars");
        cmd.add("--window-size=" + width + "," + height);
        cmd.add("--screenshot=" + target.toAbsolutePath());
        cmd.add(url);
        Process process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        // 必须把输出抽干:chromium 往 stderr 打的日志不少,合并进管道后没人读,
        // 写满 64KB 缓冲区它就阻塞在那儿,再也走不到退出 —— 表现为白等 45 秒超时。
        Thread drain = new Thread(() -> {
            try (java.io.InputStream in = process.getInputStream())
            {
                byte[] buf = new byte[4096];
                while (in.read(buf) >= 0)
                {
                    // 截图工具不需要浏览器日志,读掉即可
                }
            }
            catch (Exception ignored)
            {
            }
        }, "shot-out-drain");
        drain.setDaemon(true);
        drain.start();

        boolean finished = process.waitFor(45, TimeUnit.SECONDS);
        if (!finished)
        {
            process.descendants().toList().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            throw new ServiceException("浏览器截图超时");
        }
        if (process.exitValue() != 0 || !Files.isRegularFile(target))
        {
            throw new ServiceException("浏览器退出码=" + process.exitValue() + ",未生成截图");
        }
    }

    static String resolveBrowser()
    {
        String env = System.getenv("CHROMIUM_PATH");
        if (StringUtils.hasText(env) && Files.isExecutable(Path.of(env)))
        {
            return env;
        }
        for (String name : BROWSER_CANDIDATES)
        {
            try
            {
                // 丢弃 which 的输出而不是留在管道里:输出虽小填不满缓冲区,
                // 但留着管道不读始终是隐患,交给 JVM 直接接空设备最省事
                Process p = new ProcessBuilder("which", name)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
                if (p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0)
                {
                    return name;
                }
                p.destroyForcibly();
            }
            catch (Exception ignored)
            {
            }
        }
        return null;
    }
}
