package com.ruoyi.system.tool;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * edit 的文本健壮性与并发安全,对齐 pi 的 edit-diff / file-mutation-queue。
 *
 * <p>覆盖三类此前会直接失败或静默丢数据的情况:CRLF 文件、带 BOM 的文件、
 * 行尾空白差异,以及并行批次里两个写操作撞同一文件。
 */
class FileToolsEditRobustnessTest
{
    private FileTools newTools(Path root)
    {
        AiToolProperties props = new AiToolProperties();
        props.setWorkspaceRoot(root.toString());
        return new FileTools(props);
    }

    private String edit(FileTools tools, Path file, String oldText, String newText)
    {
        FileTools.EditRequest req = new FileTools.EditRequest();
        req.path = file.toString();
        FileTools.EditOp op = new FileTools.EditOp();
        op.oldText = oldText;
        op.newText = newText;
        req.edits = List.of(op);
        return (String) org.springframework.test.util.ReflectionTestUtils
                .invokeMethod(tools, "edit", req);
    }

    @Test
    void CRLF文件能被LF的oldText匹配且行尾不被改掉(@TempDir Path dir) throws Exception
    {
        Path file = dir.resolve("crlf.txt");
        Files.writeString(file, "alpha\r\nbeta\r\ngamma\r\n", StandardCharsets.UTF_8);

        // 模型给的 oldText 一律是 LF
        String out = edit(newTools(dir), file, "beta", "BETA");

        assertTrue(out.startsWith("Edited"), "CRLF 文件应能匹配 LF 的 oldText: " + out);
        String after = Files.readString(file, StandardCharsets.UTF_8);
        assertEquals("alpha\r\nBETA\r\ngamma\r\n", after,
                "行尾必须保持 CRLF,不能因为一次 edit 把整个文件行尾改掉");
    }

    @Test
    void BOM文件编辑后BOM保留(@TempDir Path dir) throws Exception
    {
        Path file = dir.resolve("bom.txt");
        Files.writeString(file, "﻿hello world\n", StandardCharsets.UTF_8);

        String out = edit(newTools(dir), file, "hello", "HELLO");

        assertTrue(out.startsWith("Edited"), out);
        String after = Files.readString(file, StandardCharsets.UTF_8);
        assertEquals("﻿HELLO world\n", after, "BOM 必须原样保留");
    }

    @Test
    void 行尾空白差异走模糊匹配兜底(@TempDir Path dir) throws Exception
    {
        Path file = dir.resolve("trail.txt");
        // 文件里每行末尾有空格,模型复制时通常会丢掉
        Files.writeString(file, "int a = 1;   \nint b = 2;\t\nint c = 3;\n", StandardCharsets.UTF_8);

        String out = edit(newTools(dir), file, "int a = 1;\nint b = 2;", "int a = 10;\nint b = 20;");

        assertTrue(out.startsWith("Edited"), "行尾空白差异应由模糊匹配救回: " + out);
        String after = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(after.contains("int a = 10;"), after);
        assertTrue(after.contains("int b = 20;"), after);
        assertTrue(after.contains("int c = 3;"), "未涉及的行不能被改动: " + after);
    }

    @Test
    void 模糊匹配命中多处时拒绝执行(@TempDir Path dir) throws Exception
    {
        Path file = dir.resolve("dup.txt");
        Files.writeString(file, "foo();  \nbar();\nfoo();\t\nbar();\n", StandardCharsets.UTF_8);

        String out = edit(newTools(dir), file, "foo();\nbar();", "baz();");

        assertFalse(out.startsWith("Edited"),
                "模糊匹配也必须遵守唯一性,命中多处应拒绝: " + out);
    }

    @Test
    void 并发写同一文件不丢更新(@TempDir Path dir) throws Exception
    {
        Path file = dir.resolve("concurrent.txt");
        Files.writeString(file, "L1\nL2\n", StandardCharsets.UTF_8);
        FileTools tools = newTools(dir);

        // 两个 edit 改同一文件的不同行。无锁时两边各自读原文、各自全量回写,
        // 后写的会把先写的那次替换整个抹掉。
        int threads = 2;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try
        {
            pool.submit(() -> {
                ready.countDown();
                await(go);
                return edit(tools, file, "L1", "X1");
            });
            pool.submit(() -> {
                ready.countDown();
                await(go);
                return edit(tools, file, "L2", "X2");
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));
        }
        finally
        {
            pool.shutdownNow();
        }

        String after = Files.readString(file, StandardCharsets.UTF_8);
        assertEquals("X1\nX2\n", after,
                "两次修改都应保留;丢了任意一次说明文件级串行化没生效");
    }

    @Test
    void 锁表在使用完毕后回收(@TempDir Path dir) throws Exception
    {
        Path file = dir.resolve("lock.txt");
        Files.writeString(file, "a\n", StandardCharsets.UTF_8);

        int before = FileMutationQueue.activeLockCount();
        edit(newTools(dir), file, "a", "b");

        assertEquals(before, FileMutationQueue.activeLockCount(),
                "锁条目必须在引用归零后摘除,否则长会话里 Map 无界增长");
    }

    @Test
    void 模糊匹配的边界(@TempDir Path dir)
    {
        assertNull(FileTools.fuzzyFindByLines("a\nb\n", "zzz"), "找不到应返回 null");
        assertNull(FileTools.fuzzyFindByLines("a\n", "a\nb\nc\n"), "needle 比 hay 长应返回 null");

        int[] hit = FileTools.fuzzyFindByLines("x\nfoo  \ny\n", "foo");
        assertNotNull(hit);
        assertEquals("foo  ", "x\nfoo  \ny\n".substring(hit[0], hit[1]),
                "映射回原文时必须带上被归一化掉的行尾空白");
    }

    private static void await(CountDownLatch latch)
    {
        try
        {
            latch.await(5, TimeUnit.SECONDS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }
}
