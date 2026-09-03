package com.ruoyi.system.ai;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Iterator;
import java.util.Locale;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.tool.AiToolProperties;
import com.ruoyi.system.tool.WorkspaceSandbox;

/**
 * 把 drawVideo 的参考图参数收成上游认的 URL / data URL。
 * <p>工作区相对路径读文件后压成 JPEG data URL。grok2api 对 JSON 体有长度上限,
 * 原样 PNG base64 会 400 {@code length limit exceeded}。
 */
public final class VideoImageRef
{
    static final long MAX_BYTES = 8L * 1024 * 1024;
    /** 实测 900KB JPEG data URL 可通过;按二进制约 700KB 留余量 */
    static final int TARGET_JPEG_BYTES = 700 * 1024;
    static final int MAX_EDGE = 1280;
    static final int MIN_EDGE = 256;

    private VideoImageRef()
    {
    }

    public static String resolve(AiToolProperties props, String sessionId, String imageUrl)
    {
        if (imageUrl == null || imageUrl.isBlank())
        {
            return null;
        }
        String raw = imageUrl.trim();
        if (isRemoteOrData(raw))
        {
            return compactIfDataUrl(raw);
        }
        try
        {
            Path root = WorkspaceSandbox.resolveRoot(props, sessionId, false);
            Path file = resolveInSandbox(root, raw);
            if (!Files.isRegularFile(file))
            {
                throw new ServiceException("参考图不存在: " + raw);
            }
            long size = Files.size(file);
            if (size <= 0)
            {
                throw new ServiceException("参考图是空文件: " + raw);
            }
            if (size > MAX_BYTES)
            {
                throw new ServiceException("参考图超过 8MB,请换一张更小的图: " + raw);
            }
            byte[] jpeg = compactJpeg(Files.readAllBytes(file));
            return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg);
        }
        catch (ServiceException e)
        {
            throw e;
        }
        catch (SecurityException e)
        {
            throw new ServiceException("参考图必须位于会话工作区内: " + raw);
        }
        catch (IOException e)
        {
            throw new ServiceException("读取参考图失败: " + raw + " (" + OpenAiCompatibleVideoClient.describeIo(e) + ")");
        }
    }

    /**
     * 参考图的二进制形态,给 multipart 的 {@code /v1/images/edits} 用。
     *
     * <p>{@link #resolve} 给的是 data URL(视频接口吃 JSON),而图片编辑走 multipart,
     * 要的是原始字节 + 文件名 + mime。校验规则两者共用:必须在会话工作区内、非空、≤8MB。
     *
     * @param filename 上传时用的文件名,仅作标识
     */
    public record ImageBytes(byte[] bytes, String filename, String mimeType) {}

    /**
     * 把工作区相对路径(或绝对路径,但必须落在沙箱内)读成字节。
     *
     * <p>远程 URL 与 data URL 不在此处理 —— 编辑接口需要真实字节,调用方若拿到
     * 这类输入应先自行下载或解码。
     */
    public static ImageBytes readBytes(AiToolProperties props, String sessionId, String imageRef)
    {
        if (imageRef == null || imageRef.isBlank())
        {
            throw new ServiceException("参考图路径为空");
        }
        String raw = imageRef.trim();
        if (isRemoteOrData(raw))
        {
            throw new ServiceException("参考图请传会话工作区内的相对路径(如 outputs/xxx.png): " + raw);
        }
        try
        {
            Path root = WorkspaceSandbox.resolveRoot(props, sessionId, false);
            Path file = resolveInSandbox(root, raw);
            if (!Files.isRegularFile(file))
            {
                throw new ServiceException("参考图不存在: " + raw);
            }
            long size = Files.size(file);
            if (size <= 0)
            {
                throw new ServiceException("参考图是空文件: " + raw);
            }
            if (size > MAX_BYTES)
            {
                throw new ServiceException("参考图超过 8MB,请换一张更小的图: " + raw);
            }
            String name = file.getFileName().toString();
            return new ImageBytes(Files.readAllBytes(file), name, mimeOf(name));
        }
        catch (ServiceException e)
        {
            throw e;
        }
        catch (SecurityException e)
        {
            throw new ServiceException("参考图必须位于会话工作区内: " + raw);
        }
        catch (IOException e)
        {
            throw new ServiceException("读取参考图失败: " + raw + " ("
                    + OpenAiCompatibleVideoClient.describeIo(e) + ")");
        }
    }

    static boolean isRemoteOrData(String raw)
    {
        String lower = raw.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("data:");
    }

    /**
     * 已是小 JPEG 的 data URL 原样返回;过大或 PNG 再压成 JPEG,避免首帧再踩长度上限。
     */
    static String compactIfDataUrl(String raw)
    {
        String lower = raw.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("data:"))
        {
            return raw;
        }
        int comma = raw.indexOf(',');
        if (comma < 0)
        {
            return raw;
        }
        String header = raw.substring(5, comma).toLowerCase(Locale.ROOT);
        if (!header.contains("base64"))
        {
            return raw;
        }
        byte[] decoded;
        try
        {
            decoded = Base64.getDecoder().decode(raw.substring(comma + 1));
        }
        catch (IllegalArgumentException e)
        {
            return raw;
        }
        if (decoded.length == 0)
        {
            return raw;
        }
        if (looksLikeJpeg(decoded) && decoded.length <= TARGET_JPEG_BYTES)
        {
            return raw;
        }
        try
        {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(decoded));
            if (src == null)
            {
                return raw;
            }
            byte[] jpeg = compactJpeg(decoded);
            return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg);
        }
        catch (ServiceException e)
        {
            throw e;
        }
        catch (IOException e)
        {
            if (decoded.length <= TARGET_JPEG_BYTES)
            {
                return raw;
            }
            throw new ServiceException("参考图压缩失败: " + OpenAiCompatibleVideoClient.describeIo(e));
        }
    }

    static byte[] compactJpeg(byte[] raw) throws IOException
    {
        if (looksLikeJpeg(raw) && raw.length <= TARGET_JPEG_BYTES)
        {
            return raw;
        }
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(raw));
        if (src == null)
        {
            if (raw.length <= TARGET_JPEG_BYTES)
            {
                return raw;
            }
            throw new ServiceException("参考图无法解码且超过上游体积限制");
        }
        BufferedImage rgb = fitMaxEdge(toRgb(src), MAX_EDGE);
        float[] qualities = {0.86f, 0.76f, 0.66f, 0.55f, 0.45f};
        for (int attempt = 0; attempt < 8; attempt++)
        {
            for (float quality : qualities)
            {
                byte[] jpeg = writeJpeg(rgb, quality);
                if (jpeg.length <= TARGET_JPEG_BYTES)
                {
                    return jpeg;
                }
            }
            int w = rgb.getWidth();
            int h = rgb.getHeight();
            if (Math.min(w, h) <= MIN_EDGE)
            {
                return writeJpeg(rgb, 0.4f);
            }
            rgb = scale(rgb, Math.max(MIN_EDGE, (int) (w * 0.75)), Math.max(MIN_EDGE, (int) (h * 0.75)));
        }
        return writeJpeg(rgb, 0.4f);
    }

    static boolean looksLikeJpeg(byte[] raw)
    {
        return raw != null && raw.length >= 3
                && (raw[0] & 0xFF) == 0xFF
                && (raw[1] & 0xFF) == 0xD8
                && (raw[2] & 0xFF) == 0xFF;
    }

    static BufferedImage toRgb(BufferedImage src)
    {
        if (src.getType() == BufferedImage.TYPE_INT_RGB)
        {
            return src;
        }
        BufferedImage rgb = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return rgb;
    }

    static BufferedImage fitMaxEdge(BufferedImage src, int maxEdge)
    {
        int w = src.getWidth();
        int h = src.getHeight();
        int edge = Math.max(w, h);
        if (edge <= maxEdge)
        {
            return src;
        }
        double scale = (double) maxEdge / edge;
        return scale(src, Math.max(1, (int) Math.round(w * scale)), Math.max(1, (int) Math.round(h * scale)));
    }

    static BufferedImage scale(BufferedImage src, int w, int h)
    {
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return dst;
    }

    static byte[] writeJpeg(BufferedImage rgb, float quality) throws IOException
    {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext())
        {
            throw new IOException("JDK 无 JPEG writer");
        }
        ImageWriter writer = writers.next();
        try
        {
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed())
            {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(quality);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(out))
            {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(rgb, null, null), param);
            }
            return out.toByteArray();
        }
        finally
        {
            writer.dispose();
        }
    }

    static Path resolveInSandbox(Path root, String raw) throws IOException
    {
        Path candidate = Path.of(raw);
        if (candidate.isAbsolute())
        {
            Path abs = candidate.normalize();
            Path base = root.toAbsolutePath().normalize();
            if (!abs.startsWith(base))
            {
                throw new SecurityException("参考图必须位于会话工作区内: " + raw);
            }
            return abs;
        }
        return WorkspaceSandbox.resolveSafe(root, raw);
    }

    static String mimeOf(String fileName)
    {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".jpg") || name.endsWith(".jpeg"))
        {
            return "image/jpeg";
        }
        if (name.endsWith(".webp"))
        {
            return "image/webp";
        }
        if (name.endsWith(".gif"))
        {
            return "image/gif";
        }
        return "image/png";
    }
}
