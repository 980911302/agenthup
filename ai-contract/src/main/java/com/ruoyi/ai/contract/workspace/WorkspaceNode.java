package com.ruoyi.ai.contract.workspace;

import java.time.Instant;

public record WorkspaceNode(String path, NodeType type, long size, String mediaType,
                            Instant modifiedAt, String etag)
{
    public enum NodeType { FILE, DIRECTORY }
}
