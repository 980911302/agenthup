package com.ruoyi.system.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 工作区变更 UI 产物的统一载荷构造器。
 *
 * <p>文件本体仍以会话工作区为事实源；这里仅记录一轮 Agent 对用户可见的
 * 创建、修改和删除事实，不把文件内容或绝对路径塞进事件。
 */
public final class WorkspaceChanges
{
    /** 单个事件最多携带的文件条目，避免一次依赖安装把 WebSocket/数据库撑大。 */
    static final int MAX_EVENT_FILES = 100;
    /** bash 前后快照上限；超过时宁可不猜，也不产生错误的删除记录。 */
    static final int MAX_MANIFEST_FILES = 10_000;

    public enum Operation
    {
        CREATE,
        MODIFY,
        DELETE
    }

    public record Change(Operation operation, String path, String name,
                         Long size, String mime, boolean previewable)
    {
    }

    public record Fingerprint(long size, long modifiedAt, String mime)
    {
    }

    public record Manifest(Path root, Map<String, Fingerprint> files, boolean truncated)
    {
    }

    private WorkspaceChanges()
    {
    }

    public static UiArtifact artifact(Collection<Change> source)
    {
        if (source == null || source.isEmpty())
        {
            return null;
        }
        List<Change> all = source.stream()
                .filter(c -> c != null && c.path() != null && !c.path().isBlank())
                .sorted(Comparator.comparing(Change::path))
                .toList();
        if (all.isEmpty())
        {
            return null;
        }

        int created = 0;
        int modified = 0;
        int deleted = 0;
        for (Change change : all)
        {
            if (change.operation() == Operation.CREATE) created++;
            else if (change.operation() == Operation.DELETE) deleted++;
            else modified++;
        }

        List<Map<String, Object>> files = new ArrayList<>();
        for (Change change : all.stream().limit(MAX_EVENT_FILES).toList())
        {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("operation", change.operation().name());
            one.put("path", change.path());
            one.put("name", change.name());
            if (change.size() != null) one.put("size", change.size());
            if (change.mime() != null && !change.mime().isBlank()) one.put("mime", change.mime());
            one.put("previewable", change.previewable());
            files.add(one);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("files", files);
        payload.put("fileCount", all.size());
        payload.put("createdCount", created);
        payload.put("modifiedCount", modified);
        payload.put("deletedCount", deleted);
        payload.put("truncated", all.size() > files.size());
        UiArtifactNames.Spec spec = UiArtifactNames.spec(UiArtifactNames.WORKSPACE_CHANGES);
        return new UiArtifact(spec.name(), spec.schemaVersion(), payload);
    }

    public static UiArtifact attachmentArtifact(List<ToolAttachment> attachments)
    {
        if (attachments == null || attachments.isEmpty())
        {
            return null;
        }
        List<Change> changes = new ArrayList<>();
        for (ToolAttachment attachment : attachments)
        {
            if (attachment == null || attachment.path() == null || attachment.path().isBlank())
            {
                continue;
            }
            String path = normalize(attachment.path());
            changes.add(new Change(Operation.CREATE, path,
                    attachment.name() == null || attachment.name().isBlank()
                            ? fileName(path) : attachment.name(),
                    attachment.size(), attachment.mime(), isPreviewable(path, attachment.mime())));
        }
        return artifact(changes);
    }

    public static Change current(Path root, Path file, Operation operation)
    {
        if (root == null || file == null || operation == null)
        {
            return null;
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedFile = file.toAbsolutePath().normalize();
        if (!normalizedFile.startsWith(normalizedRoot))
        {
            return null;
        }
        String relative = normalize(normalizedRoot.relativize(normalizedFile).toString());
        Long size = null;
        String mime = null;
        if (operation != Operation.DELETE && Files.isRegularFile(normalizedFile))
        {
            try
            {
                size = Files.size(normalizedFile);
                mime = probeMime(normalizedFile);
            }
            catch (IOException ignored)
            {
            }
        }
        return new Change(operation, relative, fileName(relative), size, mime,
                operation != Operation.DELETE && isPreviewable(relative, mime));
    }

    /** bash 等任意命令执行前后的轻量 manifest。 */
    public static Manifest snapshot(Path root)
    {
        Path normalizedRoot = root == null ? null : root.toAbsolutePath().normalize();
        if (normalizedRoot == null || !Files.isDirectory(normalizedRoot))
        {
            return new Manifest(normalizedRoot, Map.of(), false);
        }
        Map<String, Fingerprint> files = new LinkedHashMap<>();
        boolean truncated = false;
        try (Stream<Path> stream = Files.walk(normalizedRoot))
        {
            var iterator = stream.filter(Files::isRegularFile).iterator();
            while (iterator.hasNext())
            {
                Path file = iterator.next();
                if (files.size() >= MAX_MANIFEST_FILES)
                {
                    truncated = true;
                    break;
                }
                String relative = normalize(normalizedRoot.relativize(file).toString());
                try
                {
                    files.put(relative, new Fingerprint(Files.size(file),
                            Files.getLastModifiedTime(file).toMillis(), probeMime(file)));
                }
                catch (IOException ignored)
                {
                }
            }
        }
        catch (IOException ignored)
        {
        }
        return new Manifest(normalizedRoot, files, truncated);
    }

    public static UiArtifact diffArtifact(Manifest before, Manifest after)
    {
        if (before == null || after == null || before.truncated() || after.truncated()
                || before.root() == null || after.root() == null
                || !before.root().equals(after.root()))
        {
            return null;
        }
        List<Change> changes = new ArrayList<>();
        for (Map.Entry<String, Fingerprint> entry : after.files().entrySet())
        {
            Fingerprint old = before.files().get(entry.getKey());
            Fingerprint now = entry.getValue();
            if (old == null)
            {
                changes.add(change(entry.getKey(), Operation.CREATE, now));
            }
            else if (old.size() != now.size() || old.modifiedAt() != now.modifiedAt())
            {
                changes.add(change(entry.getKey(), Operation.MODIFY, now));
            }
        }
        for (Map.Entry<String, Fingerprint> entry : before.files().entrySet())
        {
            if (!after.files().containsKey(entry.getKey()))
            {
                changes.add(change(entry.getKey(), Operation.DELETE, entry.getValue()));
            }
        }
        return artifact(changes);
    }

    private static Change change(String path, Operation operation, Fingerprint fp)
    {
        Long size = operation == Operation.DELETE ? null : fp.size();
        String mime = operation == Operation.DELETE ? null : fp.mime();
        return new Change(operation, path, fileName(path), size, mime,
                operation != Operation.DELETE && isPreviewable(path, mime));
    }

    private static String probeMime(Path file)
    {
        try
        {
            return Files.probeContentType(file);
        }
        catch (IOException ignored)
        {
            return null;
        }
    }

    private static boolean isPreviewable(String path, String mime)
    {
        String lower = path == null ? "" : path.toLowerCase(Locale.ROOT);
        if (mime != null && (mime.startsWith("image/") || mime.startsWith("video/")
                || mime.startsWith("audio/") || mime.startsWith("text/")))
        {
            return true;
        }
        return lower.matches(".*\\.(md|txt|json|ya?ml|xml|properties|toml|ini|conf|csv|tsv|"
                + "js|ts|jsx|tsx|java|py|go|rs|php|c|cpp|h|cs|kt|swift|html?|css|scss|"
                + "png|jpe?g|gif|webp|svg|bmp|ico|mp4|webm|mov|m4v|mp3|wav|ogg|m4a|aac)$");
    }

    private static String normalize(String path)
    {
        return path == null ? "" : path.replace('\\', '/');
    }

    private static String fileName(String path)
    {
        String normalized = normalize(path);
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }
}
