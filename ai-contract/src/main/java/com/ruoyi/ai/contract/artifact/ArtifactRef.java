package com.ruoyi.ai.contract.artifact;

import java.net.URI;

/** 二进制和大对象只通过逻辑引用跨边界传递。 */
public record ArtifactRef(String id, String mediaType, String name, long size,
                          URI downloadUrl, String sha256)
{
}
