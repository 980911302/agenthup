package com.ruoyi.system.kb.graph;

/**
 * 文档图谱抽取状态(独立于 {@link com.ruoyi.system.kb.KbDocStatus})。
 */
public enum KbGraphStatus
{
    PENDING,
    EXTRACTING,
    MERGING,
    COMPLETED,
    FAILED
}
