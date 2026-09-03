package com.ruoyi.system.kb;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.constant.Constants;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.domain.KbDocument;

/**
 * 知识库文档本地文件路径解析与安全校验（纯函数，可单测）。
 * <p>只允许位于 {@code {uploadPath}/kb/{kbId}/} 下的普通可读文件。
 */
public final class KbDocumentFileAccess
{
    private KbDocumentFileAccess() {}

    /**
     * 当前库上传根目录（规范化绝对路径）。
     */
    public static Path kbRoot(Long kbId)
    {
        if (kbId == null)
        {
            return null;
        }
        return Paths.get(RuoYiConfig.getUploadPath(), "kb", String.valueOf(kbId))
            .toAbsolutePath().normalize();
    }

    /**
     * 将文档 filePath 解析为本地绝对路径；越界、空路径、不在库目录时返回 null。
     */
    public static Path resolveLocalFile(Long kbId, String filePath)
    {
        if (kbId == null || StringUtils.isEmpty(filePath))
        {
            return null;
        }
        Path root = kbRoot(kbId);
        if (root == null)
        {
            return null;
        }
        String relative = filePath;
        if (relative.startsWith(Constants.RESOURCE_PREFIX))
        {
            relative = StringUtils.substringAfter(relative, Constants.RESOURCE_PREFIX);
        }
        if (StringUtils.isEmpty(relative))
        {
            return null;
        }
        // 去掉前导分隔符，避免 Paths.get(profile, "/upload/...") 丢弃 profile
        while (relative.startsWith("/") || relative.startsWith("\\"))
        {
            relative = relative.substring(1);
        }
        Path absolute = Paths.get(RuoYiConfig.getProfile(), relative).toAbsolutePath().normalize();
        if (!absolute.startsWith(root))
        {
            return null;
        }
        return absolute;
    }

    /**
     * 路径合法且为普通可读文件时返回 true。
     */
    public static boolean isDownloadable(Long kbId, String filePath)
    {
        Path path = resolveLocalFile(kbId, filePath);
        if (path == null)
        {
            return false;
        }
        try
        {
            return Files.isRegularFile(path) && Files.isReadable(path);
        }
        catch (Exception e)
        {
            return false;
        }
    }

    public static boolean isDownloadable(KbDocument doc)
    {
        if (doc == null)
        {
            return false;
        }
        return isDownloadable(doc.getKbId(), doc.getFilePath());
    }

    /**
     * 面向用户响应：计算 downloadable，并清除内部路径/hash。
     */
    public static void sanitizeForUser(KbDocument doc)
    {
        if (doc == null)
        {
            return;
        }
        doc.setDownloadable(isDownloadable(doc));
        doc.setFilePath(null);
        doc.setContentHash(null);
        doc.setIrPath(null);
    }

    /** 兼容旧 File API */
    public static File toFile(Path path)
    {
        return path == null ? null : path.toFile();
    }
}
