package com.ruoyi.adapter.workspace;

import com.ruoyi.ai.contract.core.InvocationContext;
import com.ruoyi.ai.contract.workspace.ByteRange;
import com.ruoyi.ai.contract.workspace.ReadHandle;
import com.ruoyi.ai.contract.workspace.WorkspaceRef;
import com.ruoyi.ai.contract.workspace.WriteRequest;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.ruoyi.system.tool.AiToolProperties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LocalWorkspaceStorageAdapterTest
{
    @TempDir Path temp;

    @Test
    void writesLogicalPathAndReadsRequestedRange() throws Exception
    {
        AiToolProperties properties = new AiToolProperties();
        properties.setWorkspaceRoot(temp.toString());
        LocalWorkspaceStorageAdapter storage = new LocalWorkspaceStorageAdapter(properties);
        WorkspaceRef workspace = new WorkspaceRef("550e8400-e29b-41d4-a716-446655440000");
        InvocationContext context = InvocationContext.system("trace-1");

        storage.write(workspace, new WriteRequest("docs/a.txt",
                new ByteArrayInputStream("abcdef".getBytes(StandardCharsets.UTF_8)),
                6, "text/plain", null, false), context);

        assertEquals("docs/a.txt", storage.stat(workspace, "docs/a.txt", context).path());
        try (ReadHandle handle = storage.open(workspace, "docs/a.txt", new ByteRange(1, 4), context))
        {
            assertEquals("bcd", new String(handle.stream().readAllBytes(), StandardCharsets.UTF_8));
        }
        assertThrows(SecurityException.class,
                () -> storage.stat(workspace, "../../outside", context));
    }
}
