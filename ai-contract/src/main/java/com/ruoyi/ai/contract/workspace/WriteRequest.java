package com.ruoyi.ai.contract.workspace;

import java.io.InputStream;

public record WriteRequest(String path, InputStream content, long size, String mediaType,
                           String expectedEtag, boolean overwrite)
{
}
