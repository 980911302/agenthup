package com.ruoyi.system.kb.chunker;

/**
 * 分块层级。向量默认只索引 LEAF；PARENT 供命中后上下文扩展。
 */
public final class ChunkLevels
{
    private ChunkLevels()
    {
    }

    public static final String LEAF = "LEAF";
    public static final String PARENT = "PARENT";
    public static final String SUMMARY = "SUMMARY";

    public static boolean isLeaf(String level)
    {
        return level == null || level.isBlank() || LEAF.equalsIgnoreCase(level);
    }

    public static boolean isParent(String level)
    {
        return PARENT.equalsIgnoreCase(level);
    }
}
