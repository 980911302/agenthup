package com.ruoyi.adapter.workspace;

import com.ruoyi.ai.contract.core.InvocationContext;
import com.ruoyi.ai.contract.workspace.ByteRange;
import com.ruoyi.ai.contract.workspace.ReadHandle;
import com.ruoyi.ai.contract.workspace.WorkspaceNode;
import com.ruoyi.ai.contract.workspace.WorkspaceRef;
import com.ruoyi.ai.contract.workspace.WorkspaceStorage;
import com.ruoyi.ai.contract.workspace.WriteRequest;
import com.ruoyi.ai.contract.workspace.WriteResult;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import org.springframework.stereotype.Component;
import com.ruoyi.system.tool.AiToolProperties;
import com.ruoyi.system.tool.WorkspaceSandbox;

/** 本地会话沙箱到 WorkspaceStorage 的适配器，不向调用方暴露绝对路径。 */
@Component
public class LocalWorkspaceStorageAdapter implements WorkspaceStorage
{
    private final AiToolProperties properties;

    public LocalWorkspaceStorageAdapter(AiToolProperties properties)
    {
        this.properties = properties;
    }

    @Override
    public WorkspaceNode stat(WorkspaceRef workspace, String path, InvocationContext context)
    {
        try
        {
            Path root = WorkspaceSandbox.resolveRoot(properties, workspace.id(), false);
            Path target = WorkspaceSandbox.resolveSafe(root, path);
            if (!Files.exists(target)) return null;
            return node(root, target);
        }
        catch (IOException e)
        {
            throw new IllegalStateException("读取工作区失败", e);
        }
    }

    @Override
    public ReadHandle open(WorkspaceRef workspace, String path, ByteRange range,
                           InvocationContext context)
    {
        try
        {
            Path root = WorkspaceSandbox.resolveRoot(properties, workspace.id(), false);
            Path target = WorkspaceSandbox.resolveSafe(root, path);
            if (!Files.isRegularFile(target)) throw new IllegalArgumentException("文件不存在: " + path);
            InputStream input = Files.newInputStream(target, StandardOpenOption.READ);
            if (range != null)
            {
                input.skipNBytes(Math.min(range.startInclusive(), Files.size(target)));
                input = new LimitedInputStream(input,
                        Math.max(0, range.endExclusive() - range.startInclusive()));
            }
            return new LocalReadHandle(node(root, target), input);
        }
        catch (IOException e)
        {
            throw new IllegalStateException("打开工作区文件失败", e);
        }
    }

    @Override
    public WriteResult write(WorkspaceRef workspace, WriteRequest request,
                             InvocationContext context)
    {
        try
        {
            Path root = WorkspaceSandbox.resolveRoot(properties, workspace.id(), true);
            Path target = WorkspaceSandbox.resolveSafe(root, request.path());
            boolean created = !Files.exists(target);
            if (!created && !request.overwrite()) throw new IllegalStateException("文件已存在");
            if (!created && request.expectedEtag() != null
                    && !request.expectedEtag().equals(etag(target)))
                throw new IllegalStateException("文件已被其他写入修改");
            if (target.getParent() != null) Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), ".workspace-", ".tmp");
            try
            {
                Files.copy(request.content(), temporary, StandardCopyOption.REPLACE_EXISTING);
                try
                {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                }
                catch (AtomicMoveNotSupportedException ignored)
                {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            finally
            {
                Files.deleteIfExists(temporary);
            }
            return new WriteResult(node(root, target), created);
        }
        catch (IOException e)
        {
            throw new IllegalStateException("写入工作区失败", e);
        }
    }

    private static WorkspaceNode node(Path root, Path target) throws IOException
    {
        String relative = root.relativize(target).toString().replace('\\', '/');
        WorkspaceNode.NodeType type = Files.isDirectory(target)
                ? WorkspaceNode.NodeType.DIRECTORY : WorkspaceNode.NodeType.FILE;
        return new WorkspaceNode(relative, type, Files.isDirectory(target) ? 0 : Files.size(target),
                Files.isDirectory(target) ? null : Files.probeContentType(target),
                Files.getLastModifiedTime(target).toInstant(), etag(target));
    }

    private static String etag(Path path) throws IOException
    {
        return Long.toHexString(Files.getLastModifiedTime(path).toMillis()) + '-'
                + Long.toHexString(Files.isDirectory(path) ? 0 : Files.size(path));
    }

    private record LocalReadHandle(WorkspaceNode node, InputStream stream) implements ReadHandle
    {
        @Override public void close()
        {
            try { stream.close(); }
            catch (IOException ignored) { }
        }
    }

    private static final class LimitedInputStream extends FilterInputStream
    {
        private long remaining;
        private LimitedInputStream(InputStream input, long remaining)
        {
            super(input);
            this.remaining = remaining;
        }
        @Override public int read() throws IOException
        {
            if (remaining <= 0) return -1;
            int value = super.read();
            if (value >= 0) remaining--;
            return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException
        {
            if (remaining <= 0) return -1;
            int read = super.read(bytes, offset, (int) Math.min(length, remaining));
            if (read > 0) remaining -= read;
            return read;
        }
    }
}
