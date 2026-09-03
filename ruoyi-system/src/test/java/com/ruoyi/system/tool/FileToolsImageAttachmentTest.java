package com.ruoyi.system.tool;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.content.Media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * read 读图片时**不产出前端附件**。
 *
 * <p>附件的语义是「我产出了这个东西给你看」——那是 drawImage / captureScreenshot 的事。
 * read 是模型自己回头查看已有文件，对用户没有新信息：那张图早在生成它的消息里展示过了。
 * 登记附件会让同一张图在聊天流里出现两次（实测 drawImage 生成 8 张、模型 read 其中一张
 * 检查，界面渲染出 9 张）。
 *
 * <p>这个重复此前被另一个 bug 掩盖：read 登记的是绝对路径，而下载接口只认沙箱相对路径，
 * 那份附件一律 404 显示成碎图，看起来像「一张好图 + 一张坏图」。修掉碎图后重复才暴露。
 */
class FileToolsImageAttachmentTest
{
    @AfterEach
    void clearContext()
    {
        WorkspaceContext.restore(null);
    }

    private FileTools newTools(Path workspaceRoot)
    {
        AiToolProperties props = new AiToolProperties();
        props.setWorkspaceRoot(workspaceRoot.toString());
        props.setWorkspacePerSession(true);
        return new FileTools(props);
    }

    private String read(FileTools tools, Path file)
    {
        FileTools.ReadRequest req = new FileTools.ReadRequest();
        req.path = file.toAbsolutePath().toString();
        return (String) org.springframework.test.util.ReflectionTestUtils
                .invokeMethod(tools, "read", req);
    }

    private static void writePng(Path file) throws Exception
    {
        Files.createDirectories(file.getParent());
        ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", file.toFile());
    }

    @Test
    void 沙箱内图片也不登记附件(@TempDir Path root) throws Exception
    {
        String sessionId = "sess-1";
        WorkspaceContext.bind(sessionId);
        Path sandbox = WorkspaceSandbox.resolveRoot(newProps(root), sessionId, true);
        Path img = sandbox.resolve("outputs/pic.png");
        writePng(img);

        FileTools tools = newTools(root);
        String out = read(tools, img);

        List<ToolAttachment> attachments = tools.lastAttachments();
        assertTrue(attachments == null || attachments.isEmpty(),
                "read 不该登记附件，否则同一张图会在聊天流里出现两次");
        // 工具层不该谈论宿主 UI 的可见性:read 也跑在子 agent、定时任务里,那里没有「用户」。
        // 旧文案「not shown to the user again」被模型读成了能力声明 —— 它据此拒绝了用户
        // 「再给我看看那张图」的请求,还建议对方去本地打开原文件,并且不再调用任何工具。
        // 防重复渲染由上面的 lastAttachments() 为空保证,与提示词无关。
        assertFalse(out.toLowerCase().contains("shown to the user"),
                "返回文本不得涉及用户可见性,那不是本工具的职责: " + out);
        assertTrue(out.contains("reading the same path again here returns nothing new"),
                "返回文本要讲明本轮内重复读取没有意义: " + out);
    }

    @Test
    void 图片仍然进模型视觉上下文(@TempDir Path root) throws Exception
    {
        String sessionId = "sess-vision";
        WorkspaceContext.bind(sessionId);
        Path sandbox = WorkspaceSandbox.resolveRoot(newProps(root), sessionId, true);
        Path img = sandbox.resolve("outputs/pic.png");
        writePng(img);

        FileTools tools = newTools(root);
        read(tools, img);

        // 不给前端不等于不给模型 —— read 图片的意义就在于让模型能看
        List<Media> media = tools.lastPromptMedia();
        assertNotNull(media, "图片必须进模型视觉上下文,否则 read 一张图毫无意义");
        assertEquals(1, media.size());
    }

    @Test
    void 沙箱外图片同样不登记附件(@TempDir Path root, @TempDir Path outside) throws Exception
    {
        WorkspaceContext.bind("sess-2");
        Path img = outside.resolve("project/assets/hero.png");
        writePng(img);

        FileTools tools = newTools(root);
        read(tools, img);

        List<ToolAttachment> attachments = tools.lastAttachments();
        assertTrue(attachments == null || attachments.isEmpty(),
                "沙箱外的图片下载接口本来就取不到,更不该登记");
    }

    @Test
    void 拿不到会话时不登记附件(@TempDir Path root) throws Exception
    {
        Path img = root.resolve("stray.png");
        writePng(img);

        FileTools tools = newTools(root);
        String out = read(tools, img);

        assertFalse(out.startsWith("Image attached:"), out);
        List<ToolAttachment> attachments = tools.lastAttachments();
        assertTrue(attachments == null || attachments.isEmpty());
    }

    private AiToolProperties newProps(Path root)
    {
        AiToolProperties props = new AiToolProperties();
        props.setWorkspaceRoot(root.toString());
        props.setWorkspacePerSession(true);
        return props;
    }
}
