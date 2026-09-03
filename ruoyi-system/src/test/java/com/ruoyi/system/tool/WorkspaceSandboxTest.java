package com.ruoyi.system.tool;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkspaceSandboxTest
{
    @TempDir
    Path tmp;

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void resolveSafe_rejectsSymlinkPointingOutsideSandbox() throws Exception
    {
        Path sandbox = tmp.resolve("box");
        Files.createDirectories(sandbox);
        Path secret = tmp.resolve("secret.txt");
        Files.writeString(secret, "classified");
        Files.createSymbolicLink(sandbox.resolve("leak"), secret);

        SecurityException ex = assertThrows(SecurityException.class,
                () -> WorkspaceSandbox.resolveSafe(sandbox, "leak"));
        assertEquals(true, ex.getMessage().contains("符号链接") || ex.getMessage().contains("穿越"),
                ex.getMessage());
    }

    @Test
    void resolveSafe_allowsOrdinaryRelativeFile() throws Exception
    {
        Path sandbox = tmp.resolve("box");
        Files.createDirectories(sandbox);
        Files.writeString(sandbox.resolve("ok.txt"), "hi");
        Path got = WorkspaceSandbox.resolveSafe(sandbox, "ok.txt");
        assertEquals(sandbox.resolve("ok.txt").toAbsolutePath().normalize(), got);
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void resolveSafe_rejectsPathThroughDirectorySymlink() throws Exception
    {
        Path sandbox = tmp.resolve("box");
        Files.createDirectories(sandbox);
        Path outside = tmp.resolve("outside");
        Files.createDirectories(outside);
        Files.createSymbolicLink(sandbox.resolve("leak"), outside);

        assertThrows(SecurityException.class,
                () -> WorkspaceSandbox.resolveSafe(sandbox, "leak/new.txt"));
    }
}
