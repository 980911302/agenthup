package com.ruoyi.system.tool;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Pi 风格:工具清单一行一句,用法规则进 system prompt 的 Guidelines。
 */
public final class WorkspaceToolsPrompt
{
    public static final Set<String> TOOLS = Set.of("read", "write", "edit", "bash", "grep", "find", "ls");

    private static final Map<String, String> SNIPPETS = new LinkedHashMap<>();

    static
    {
        SNIPPETS.put("read", "Read file contents");
        SNIPPETS.put("bash", "Execute bash commands (ls, grep, find, etc.)");
        SNIPPETS.put("edit", "Make precise file edits with exact text replacement, including multiple disjoint edits in one call");
        SNIPPETS.put("write", "Create or overwrite files");
        SNIPPETS.put("grep", "Search file contents for patterns (respects .gitignore)");
        SNIPPETS.put("find", "Find files by glob pattern (respects .gitignore)");
        SNIPPETS.put("ls", "List directory contents");
    }

    private WorkspaceToolsPrompt()
    {
    }

    public static String buildSection(Collection<String> mounted)
    {
        if (mounted == null || mounted.stream().noneMatch(TOOLS::contains))
        {
            return "";
        }
        StringBuilder toolsList = new StringBuilder();
        for (Map.Entry<String, String> e : SNIPPETS.entrySet())
        {
            if (mounted.contains(e.getKey()))
            {
                toolsList.append("- ").append(e.getKey()).append(": ").append(e.getValue()).append("\n");
            }
        }
        if (toolsList.isEmpty())
        {
            return "";
        }
        StringBuilder g = new StringBuilder();
        boolean hasBash = mounted.contains("bash");
        boolean hasExplore = mounted.contains("grep") || mounted.contains("find") || mounted.contains("ls");
        if (hasBash && !hasExplore)
        {
            g.append("- Use bash for file operations like ls, rg, find\n");
        }
        if (mounted.contains("read"))
        {
            g.append("- Use read to examine files instead of cat or sed.\n");
        }
        if (mounted.contains("edit"))
        {
            g.append("- Use edit for precise changes (edits[].oldText must match exactly)\n");
            g.append("- When changing multiple separate locations in one file, use one edit call with multiple entries in edits[] instead of multiple edit calls\n");
            g.append("- Each edits[].oldText is matched against the original file, not after earlier edits are applied. Do not emit overlapping or nested edits. Merge nearby changes into one edit.\n");
            g.append("- Keep edits[].oldText as small as possible while still being unique in the file. Do not pad with large unchanged regions.\n");
        }
        if (mounted.contains("write"))
        {
            g.append("- Use write only for new files or complete rewrites.\n");
        }
        g.append("- Be concise in your responses\n");
        g.append("- Show file paths clearly when working with files\n");

        return """
                Available tools:
                %s
                Guidelines:
                %s""".formatted(toolsList.toString().stripTrailing(), g.toString().stripTrailing());
    }
}
