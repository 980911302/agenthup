package com.ruoyi.ai.contract.workspace;

import com.ruoyi.ai.contract.core.InvocationContext;

public interface WorkspaceStorage
{
    WorkspaceNode stat(WorkspaceRef workspace, String path, InvocationContext context);

    ReadHandle open(WorkspaceRef workspace, String path, ByteRange range, InvocationContext context);

    WriteResult write(WorkspaceRef workspace, WriteRequest request, InvocationContext context);
}
