package com.ruoyi.system.ai;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.tool.AiToolProperties;
import com.ruoyi.system.tool.WorkspaceSandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoImageRefTest
{
    @Test
    void workspaceRelativePngBecomesDataUrl(@TempDir Path root) throws Exception
    {
        String sessionId = "sess-video-ref";
        Path img = WorkspaceSandbox.resolveRoot(props(root), sessionId, true)
                .resolve("outputs/img-acfe.png");
        Files.createDirectories(img.getParent());
        ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", img.toFile());

        String resolved = VideoImageRef.resolve(props(root), sessionId, "outputs/img-acfe.png");

        assertTrue(resolved.startsWith("data:image/jpeg;base64,"), resolved);
        byte[] decoded = Base64.getDecoder().decode(resolved.substring("data:image/jpeg;base64,".length()));
        assertTrue(VideoImageRef.looksLikeJpeg(decoded));
        assertTrue(decoded.length > 0);
    }

    @Test
    void largeWorkspacePngIsJpegUnderLimit(@TempDir Path root) throws Exception
    {
        String sessionId = "sess-video-ref";
        Path img = WorkspaceSandbox.resolveRoot(props(root), sessionId, true)
                .resolve("outputs/test_firstframe.png");
        Files.createDirectories(img.getParent());
        BufferedImage big = new BufferedImage(1600, 1200, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < big.getHeight(); y++)
        {
            for (int x = 0; x < big.getWidth(); x++)
            {
                big.setRGB(x, y, (0xFF << 24) | ((x * 17) << 16) | ((y * 13) << 8) | ((x + y) & 0xFF));
            }
        }
        ImageIO.write(big, "png", img.toFile());
        assertTrue(Files.size(img) > 100_000, "fixture should be a real PNG");

        String resolved = VideoImageRef.resolve(props(root), sessionId, "outputs/test_firstframe.png");

        assertTrue(resolved.startsWith("data:image/jpeg;base64,"), resolved);
        byte[] decoded = Base64.getDecoder().decode(resolved.substring("data:image/jpeg;base64,".length()));
        assertTrue(VideoImageRef.looksLikeJpeg(decoded));
        assertTrue(decoded.length <= VideoImageRef.TARGET_JPEG_BYTES, "jpeg bytes=" + decoded.length);
        assertTrue(resolved.length() < 1_200_000, "data url length=" + resolved.length());
    }

    @Test
    void httpAndDataUrlsPassThrough(@TempDir Path root)
    {
        assertEquals("https://cdn.example/a.png",
                VideoImageRef.resolve(props(root), "sess-video-ref", "https://cdn.example/a.png"));
        assertEquals("data:image/jpeg;base64,abc",
                VideoImageRef.resolve(props(root), "sess-video-ref", "data:image/jpeg;base64,abc"));
    }

    @Test
    void blankIsNull(@TempDir Path root)
    {
        assertNull(VideoImageRef.resolve(props(root), "sess-video-ref", null));
        assertNull(VideoImageRef.resolve(props(root), "sess-video-ref", "  "));
    }

    @Test
    void missingWorkspaceFileFails(@TempDir Path root)
    {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> VideoImageRef.resolve(props(root), "sess-video-ref", "outputs/missing.png"));
        assertTrue(ex.getMessage().contains("参考图"), ex.getMessage());
    }

    private static AiToolProperties props(Path root)
    {
        AiToolProperties p = new AiToolProperties();
        p.setWorkspaceRoot(root.toString());
        p.setWorkspacePerSession(true);
        return p;
    }
}
