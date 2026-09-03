package com.ruoyi.system.tool;

import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceToolsPromptTest
{
    @Test
    void listsSnippetsAndEditGuidelines()
    {
        String s = WorkspaceToolsPrompt.buildSection(Set.of("read", "write", "edit", "bash"));
        assertTrue(s.contains("- read: Read file contents"));
        assertTrue(s.contains("- bash: Execute bash commands"));
        assertTrue(s.contains("Use read to examine files instead of cat or sed."));
        assertTrue(s.contains("Use write only for new files or complete rewrites."));
        assertTrue(s.contains("Use bash for file operations like ls, rg, find"));
        assertTrue(s.contains("edits[].oldText must match exactly"));
    }

    @Test
    void omitsBashExploreGuidelineWhenGrepIsMounted()
    {
        String s = WorkspaceToolsPrompt.buildSection(Set.of("bash", "grep", "find", "ls"));
        assertFalse(s.contains("Use bash for file operations like ls, rg, find"));
        assertTrue(s.contains("- grep:"));
    }

    @Test
    void emptyWhenNoWorkspaceTools()
    {
        assertTrue(WorkspaceToolsPrompt.buildSection(Set.of("searchKnowledge")).isEmpty());
    }
}
