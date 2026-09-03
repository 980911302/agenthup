package com.ruoyi.system.ai.agent;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 截图进模型上下文前的压缩:体积上限、长边上限、透明区处理。
 *
 * <p>背景:1440x4000 的整页 PNG 实测 3.6MB,原图直发被上游网关 400 拒掉,
 * 子智能体两次调用全挂(run eada2c77)。这里锁住「压缩后必须能进请求体」这条线。
 */
class ScreenshotToolCallbackTest
{
    /** 与被测类保持一致的上限,变了这里要一起改 */
    private static final long CONTEXT_MAX_BYTES = 1_000_000L;
    private static final int CONTEXT_MAX_EDGE = 1568;

    /**
     * 造一张压不动的 PNG:随机噪声让 PNG 无损压缩失效,体积逼近事故现场的 3.6MB。
     * 纯色图会被 PNG 压到几十 KB,测不出问题。
     */
    private Path writeNoisePng(Path dir, int width, int height) throws Exception
    {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(42);
        for (int y = 0; y < height; y++)
        {
            for (int x = 0; x < width; x++)
            {
                img.setRGB(x, y, random.nextInt(0xFFFFFF));
            }
        }
        Path png = dir.resolve("shot-" + width + "x" + height + ".png");
        ImageIO.write(img, "png", png.toFile());
        return png;
    }

    @Test
    void 整页大图压缩后能进请求体(@TempDir Path dir) throws Exception
    {
        Path png = writeNoisePng(dir, 1440, 4000);
        long original = Files.size(png);
        assertTrue(original > CONTEXT_MAX_BYTES,
                "样本得先超标才有意义,实际 " + original);

        Path out = ScreenshotToolCallback.shrinkForModel(png, dir);

        assertNotNull(out, "压缩不该失败");
        assertTrue(Files.size(out) <= CONTEXT_MAX_BYTES,
                "压缩后仍超上限: " + Files.size(out));
        BufferedImage shrunk = ImageIO.read(out.toFile());
        assertTrue(Math.max(shrunk.getWidth(), shrunk.getHeight()) <= CONTEXT_MAX_EDGE,
                "长边未收敛: " + shrunk.getWidth() + "x" + shrunk.getHeight());
        assertTrue(Files.exists(png), "原图必须保留给前端附件");
    }

    @Test
    void 小图不被放大(@TempDir Path dir) throws Exception
    {
        Path png = writeNoisePng(dir, 800, 600);

        Path out = ScreenshotToolCallback.shrinkForModel(png, dir);

        assertNotNull(out);
        BufferedImage shrunk = ImageIO.read(out.toFile());
        assertEquals(800, shrunk.getWidth());
        assertEquals(600, shrunk.getHeight());
    }

    @Test
    void 透明区铺白底而非变黑(@TempDir Path dir) throws Exception
    {
        BufferedImage img = new BufferedImage(400, 300, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0, 0, 0, 0));
        g.fillRect(0, 0, 400, 300);
        g.dispose();
        Path png = dir.resolve("transparent.png");
        ImageIO.write(img, "png", png.toFile());

        Path out = ScreenshotToolCallback.shrinkForModel(png, dir);

        assertNotNull(out);
        BufferedImage shrunk = ImageIO.read(out.toFile());
        int rgb = shrunk.getRGB(200, 150) & 0xFFFFFF;
        // JPEG 有色差,不能断言精确白
        assertTrue(rgb > 0xF0F0F0, "透明区应接近白色,实际 " + Integer.toHexString(rgb));
    }

    @Test
    void 读不出的文件返回null而不是抛异常(@TempDir Path dir) throws Exception
    {
        Path broken = dir.resolve("broken.png");
        Files.writeString(broken, "not an image");

        assertEquals(null, ScreenshotToolCallback.shrinkForModel(broken, dir));
    }
}
