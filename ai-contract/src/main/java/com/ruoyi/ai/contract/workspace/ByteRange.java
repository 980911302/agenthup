package com.ruoyi.ai.contract.workspace;

public record ByteRange(long startInclusive, long endExclusive)
{
    public ByteRange
    {
        if (startInclusive < 0 || endExclusive < startInclusive)
        {
            throw new IllegalArgumentException("无效字节范围");
        }
    }
}
