package com.ruoyi.system.tool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * bash 工具遇到常驻后台子孙进程时不得挂死。
 *
 * <p>对标 pi 的 {@code bash-close-hang-windows.test.ts}:命令拉起一个继承了 stdout 管道、
 * 永不退出的后代,shell 自己很快退出。修复前主线程在「读到 EOF」上无限阻塞 ——
 * 而 EOF 要等所有持有管道写端的进程退出,那个后代永远不退,于是带超时的 waitFor
 * 根本执行不到,整个工具调用线程挂死。
 *
 * <p>这些用例直接起真实进程,不 mock。Windows 上 shell 语法不同,跳过。
 */
@DisabledOnOs(OS.WINDOWS)
class ShellToolHangTest
{
    /** 留足余量:断言的是「有没有挂死」,不是「快不快」 */
    private static final long HANG_THRESHOLD_MS = 20_000L;

    private ShellTool newTool()
    {
        return newTool(5_000L);
    }

    private ShellTool newTool(long timeoutMs)
    {
        AiToolProperties props = new AiToolProperties();
        props.setShellTimeoutMs(timeoutMs);
        return new ShellTool(props);
    }

    private String run(ShellTool tool, Path workDir, String command) throws Exception
    {
        ShellTool.BashRequest req = new ShellTool.BashRequest();
        req.command = command;
        req.workdir = workDir.toString();
        return (String) org.springframework.test.util.ReflectionTestUtils
                .invokeMethod(tool, "bash", req);
    }

    @Test
    void 后台常驻进程握住管道时不得挂死(@TempDir Path dir) throws Exception
    {
        ShellTool tool = newTool();
        // sleep 300 继承 stdout 管道并存活远超超时;shell 自己立刻退出。
        // 修复前:读 EOF 永远等不到 sleep 结束 -> 挂死。
        String command = "nohup sleep 300 >/dev/null 2>&1 & echo started";

        long start = System.currentTimeMillis();
        String out = run(tool, dir, command);
        long cost = System.currentTimeMillis() - start;

        assertTrue(cost < HANG_THRESHOLD_MS,
                "命令应立即返回而不是挂死,实际耗时 " + cost + "ms");
        assertTrue(out.contains("started"), "正常输出应被捕获: " + out);
    }

    @Test
    void 命令自身超时时杀掉整棵进程树(@TempDir Path dir) throws Exception
    {
        // 超时 2s,后代进程 5s 后才写文件 —— 写入时刻必须落在超时之后,
        // 否则文件在超时前就出现了,断言的就不是「有没有杀掉后代」。
        ShellTool tool = newTool(2_000L);
        Path marker = dir.resolve("alive.txt");
        String command = "( sleep 5; echo alive > " + marker.toAbsolutePath() + " ) & sleep 30";

        long start = System.currentTimeMillis();
        String out = run(tool, dir, command);
        long cost = System.currentTimeMillis() - start;

        assertTrue(cost < HANG_THRESHOLD_MS, "应在超时后返回,实际 " + cost + "ms");
        assertTrue(out.contains("timed out"), "应报告超时: " + out);

        // 等过后代原定的写入时刻,它若还活着就会留下文件
        TimeUnit.SECONDS.sleep(6);
        assertFalse(Files.exists(marker),
                "超时后后代进程仍在运行并写出了文件,说明没有杀进程树");
    }

    @Test
    void 命令末尾输出不被提前收流丢掉(@TempDir Path dir) throws Exception
    {
        ShellTool tool = newTool();
        String out = run(tool, dir, "echo head; sleep 0.2; echo tail");

        assertTrue(out.contains("head"), "开头输出丢失: " + out);
        assertTrue(out.contains("tail"),
                "结尾输出丢失 —— exit 之后没有给管道收尾宽限: " + out);
    }

    @Test
    void 等待标准输入的命令不得挂死(@TempDir Path dir) throws Exception
    {
        ShellTool tool = newTool();

        long start = System.currentTimeMillis();
        run(tool, dir, "cat");
        long cost = System.currentTimeMillis() - start;

        assertTrue(cost < HANG_THRESHOLD_MS,
                "stdin 接空设备后 cat 应立刻拿到 EOF,实际耗时 " + cost + "ms");
    }
}
