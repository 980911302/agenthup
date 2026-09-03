package com.ruoyi.system.kb.graph;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * sourceIds / filePaths 合并:去重 + 保留最早(KEEP)。
 * <p>高频实体若不封顶会把单节点属性撑到 MB 级;最早出现的来源通常是定义性章节。
 */
public final class GraphSourceIds
{
    private GraphSourceIds() {}

    /**
     * 合并两个 id 列表,保持「先出现」顺序,截断到 max。
     */
    public static List<Long> mergeLongs(List<Long> existing, List<Long> incoming, int max)
    {
        LinkedHashSet<Long> set = new LinkedHashSet<>();
        if (existing != null)
        {
            for (Long id : existing)
            {
                if (id != null)
                {
                    set.add(id);
                }
            }
        }
        if (incoming != null)
        {
            for (Long id : incoming)
            {
                if (id != null)
                {
                    set.add(id);
                }
            }
        }
        List<Long> out = new ArrayList<>(set);
        if (out.size() > max)
        {
            return new ArrayList<>(out.subList(0, max));
        }
        return out;
    }

    /**
     * 合并路径列表;超限时末尾用「更多文件」占位(若尚未存在)。
     */
    public static List<String> mergePaths(List<String> existing, List<String> incoming, int max)
    {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (existing != null)
        {
            for (String p : existing)
            {
                if (p != null && !p.isBlank()
                    && !KbGraphConstants.MORE_FILES_PLACEHOLDER.equals(p))
                {
                    set.add(p);
                }
            }
        }
        if (incoming != null)
        {
            for (String p : incoming)
            {
                if (p != null && !p.isBlank()
                    && !KbGraphConstants.MORE_FILES_PLACEHOLDER.equals(p))
                {
                    set.add(p);
                }
            }
        }
        List<String> out = new ArrayList<>(set);
        if (out.size() > max)
        {
            List<String> capped = new ArrayList<>(out.subList(0, max - 1));
            capped.add(KbGraphConstants.MORE_FILES_PLACEHOLDER);
            return capped;
        }
        return out;
    }

    /** 从列表中移除指定 id(删除文档时摘除引用) */
    public static List<Long> removeAll(List<Long> existing, List<Long> toRemove)
    {
        if (existing == null || existing.isEmpty())
        {
            return List.of();
        }
        if (toRemove == null || toRemove.isEmpty())
        {
            return new ArrayList<>(existing);
        }
        var remove = new java.util.HashSet<>(toRemove);
        List<Long> out = new ArrayList<>();
        for (Long id : existing)
        {
            if (id != null && !remove.contains(id))
            {
                out.add(id);
            }
        }
        return out;
    }
}
