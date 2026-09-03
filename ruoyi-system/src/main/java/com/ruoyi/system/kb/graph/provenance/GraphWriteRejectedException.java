package com.ruoyi.system.kb.graph.provenance;

/**
 * 旧 generation / 非 active run 写入被拒绝。
 */
public class GraphWriteRejectedException extends RuntimeException
{
    public GraphWriteRejectedException(String message)
    {
        super(message);
    }
}
