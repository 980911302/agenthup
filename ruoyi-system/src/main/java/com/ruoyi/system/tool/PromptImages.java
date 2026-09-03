package com.ruoyi.system.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * 把图片字节流变成可直接进模型上下文的 {@link Media}。
 *
 * <p>与 {@code ScreenshotToolCallback.shrinkForModel} 同策略,区别是那边从磁盘文件读、
 * 这边从对象存储的流读(渠道工具的截图先落个人文件,服务端再取回)。长边缩到
 * {@link #MAX_EDGE} 以内并转 JPEG:上游按图块计费,原图尺寸越大块数越多,而截图
 * 缩到这个尺寸对模型识图基本无损。
 *
 * @author ruoyi
 */
public final class PromptImages
{
    private static final Logger log = LoggerFactory.getLogger(PromptImages.class);

    /** 与 ScreenshotToolCallback.CONTEXT_MAX_EDGE 保持一致。 */
    public static final int MAX_EDGE = 1568;

    private static final float JPEG_QUALITY_HINT = 0.82f;

    private PromptImages() { }

    /**
     * 读流 → 按长边缩放 → 铺白底转 JPEG → 包成 Media。
     *
     * @return 失败返回 null(不是图片、解码失败等),调用方按「没有图」处理,不要抛异常打断工具
     */
    public static Media fromStream(InputStream in)
    {
        if (in == null)
        {
            return null;
        }
        try
        {
            BufferedImage src = ImageIO.read(in);
            if (src == null)
            {
                return null;
            }
            int w = src.getWidth();
            int h = src.getHeight();
            double scale = Math.min(1.0d, (double) MAX_EDGE / Math.max(w, h));
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

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(dst, "jpg", out))
            {
                return null;
            }
            return new Media(MimeTypeUtils.IMAGE_JPEG, new ByteArrayResource(out.toByteArray()));
        }
        catch (Exception e)
        {
            log.warn("图片转 Media 失败: {}", e.getMessage());
            return null;
        }
    }
}
