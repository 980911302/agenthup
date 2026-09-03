package com.ruoyi.ai.contract.workspace;

import java.io.InputStream;

public interface ReadHandle extends AutoCloseable
{
    WorkspaceNode node();

    InputStream stream();

    @Override
    void close();
}
