package com.ruoyi.system.tool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pi 工作区工具:read / write / edit / grep / find / ls。
 */
class FileToolsTest
{
    @TempDir
    Path cwd;

    private FileTools tools;

    @BeforeEach
    void setUp()
    {
        AiToolProperties props = new AiToolProperties();
        props.setCwd(cwd.toAbsolutePath().toString());
        tools = new FileTools(props);
    }

    @Test
    void readPrefixesLineNumbersAndPaginates() throws Exception
    {
        Files.writeString(cwd.resolve("a.txt"), "alpha\nbeta\ngamma\n");
        String page = call(tools.readTool(), "{\"path\":\"a.txt\",\"offset\":2,\"limit\":1}");
        assertTrue(page.contains("2: beta"), page);
        assertTrue(page.contains("offset=3"), page);
        String all = call(tools.readTool(), "{\"path\":\"a.txt\"}");
        assertTrue(all.contains("1: alpha"));
        assertTrue(all.contains("3: gamma"));
    }

    @Test
    void writeCreatesFile()
    {
        String out = call(tools.writeTool(), "{\"path\":\"n.txt\",\"content\":\"hello\"}");
        assertTrue(out.toLowerCase().contains("n.txt"));
        assertEquals("hello", java.nio.file.Path.of(cwd.resolve("n.txt").toString())
                .toFile().exists() ? read("n.txt") : "");
        assertEquals("hello", read("n.txt"));
    }

    @Test
    void writePublishesStructuredWorkspaceChange()
    {
        ToolCallback callback = tools.writeTool();
        call(callback, "{\"path\":\"reports/result.txt\",\"content\":\"hello\"}");

        List<UiArtifact> artifacts = ((UiArtifactAware) callback).lastArtifacts();
        assertEquals(1, artifacts.size());
        assertEquals(UiArtifactNames.WORKSPACE_CHANGES, artifacts.get(0).name());
        JSONObject payload = JSON.parseObject(JSON.toJSONString(artifacts.get(0).payload()));
        assertEquals(1, payload.getIntValue("createdCount"));
        assertEquals("CREATE", payload.getJSONArray("files").getJSONObject(0).getString("operation"));
        assertEquals("reports/result.txt", payload.getJSONArray("files").getJSONObject(0).getString("path"));
    }

    @Test
    void editAppliesMultipleDisjointReplacementsAgainstOriginal() throws Exception
    {
        Files.writeString(cwd.resolve("e.txt"), "one two three");
        String out = call(tools.editTool(), """
                {"path":"e.txt","edits":[
                  {"oldText":"one","newText":"1"},
                  {"oldText":"three","newText":"3"}
                ]}
                """);
        assertFalse(out.toLowerCase().contains("error") && out.contains("oldText"), out);
        assertEquals("1 two 3", read("e.txt"));
    }

    @Test
    void editFailsWhenOldTextIsNotUnique() throws Exception
    {
        Files.writeString(cwd.resolve("d.txt"), "aa aa");
        String out = call(tools.editTool(),
                "{\"path\":\"d.txt\",\"edits\":[{\"oldText\":\"aa\",\"newText\":\"b\"}]}");
        assertTrue(out.toLowerCase().contains("multiple") || out.contains("多处") || out.contains("unique"), out);
        assertEquals("aa aa", read("d.txt"));
    }

    @Test
    void grepRespectsGitignore() throws Exception
    {
        Files.writeString(cwd.resolve(".gitignore"), "skip.txt\n");
        Files.writeString(cwd.resolve("keep.txt"), "needle");
        Files.writeString(cwd.resolve("skip.txt"), "needle");
        String out = call(tools.grepTool(), "{\"pattern\":\"needle\"}");
        assertTrue(out.contains("keep.txt"), out);
        assertFalse(out.contains("skip.txt"), out);
    }

    @Test
    void findReturnsRelativeMatches() throws Exception
    {
        Files.createDirectories(cwd.resolve("src"));
        Files.writeString(cwd.resolve("src/A.java"), "class A {}");
        Files.writeString(cwd.resolve("README.md"), "x");
        String out = call(tools.findTool(), "{\"pattern\":\"**/*.java\"}");
        assertTrue(out.contains("src/A.java") || out.contains("src\\A.java"), out);
        assertFalse(out.contains("README.md"), out);
    }

    @Test
    void lsMarksDirectoriesWithSlash() throws Exception
    {
        Files.createDirectory(cwd.resolve("dir"));
        Files.writeString(cwd.resolve("f.txt"), "x");
        String out = call(tools.lsTool(), "{}");
        assertTrue(out.contains("dir/"), out);
        assertTrue(out.contains("f.txt"), out);
    }

    private String read(String name)
    {
        try
        {
            return Files.readString(cwd.resolve(name));
        }
        catch (Exception e)
        {
            throw new AssertionError(e);
        }
    }

    private static String call(ToolCallback tool, String args)
    {
        return tool.call(args);
    }
}
