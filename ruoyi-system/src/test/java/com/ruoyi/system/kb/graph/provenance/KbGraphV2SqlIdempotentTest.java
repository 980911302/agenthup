package com.ruoyi.system.kb.graph.provenance;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 校验增量 SQL 文件存在且以幂等写法为主（IF NOT EXISTS / add column if not exists）。
 */
class KbGraphV2SqlIdempotentTest
{
    @Test
    void migrationFileIsIdempotent() throws Exception
    {
        Path sql = resolveSql("sql/kb_graph_v2.sql");
        String text = Files.readString(sql, StandardCharsets.UTF_8);
        assertTrue(text.contains("create table if not exists kb_graph_run"), "missing run table");
        assertTrue(text.contains("add column if not exists active_run_id"), "missing active_run_id");
        assertTrue(text.contains("add column if not exists generation"), "missing generation");
        assertTrue(text.contains("add column if not exists graph_version"), "missing graph_version");
        assertTrue(text.toLowerCase().contains("if not exists"), "should use if not exists");
    }

    @Test
    void fullScriptsIncludeV2Schema() throws Exception
    {
        String pg = Files.readString(resolveSql("sql/kb_pg.sql"), StandardCharsets.UTF_8);
        String graph = Files.readString(resolveSql("sql/kb_graph.sql"), StandardCharsets.UTF_8);
        assertTrue(pg.contains("kb_graph_run"));
        assertTrue(pg.contains("active_run_id"));
        assertTrue(graph.contains("kb_graph_run"));
        assertTrue(graph.contains("graph_version"));
    }

    private static Path resolveSql(String relative) throws Exception
    {
        Path p = Path.of(relative);
        if (Files.isRegularFile(p))
        {
            return p;
        }
        Path alt = Path.of("..").resolve(relative).normalize();
        if (Files.isRegularFile(alt))
        {
            return alt;
        }
        // surefire cwd 可能是模块目录
        Path fromModule = Path.of("..", relative).normalize();
        if (Files.isRegularFile(fromModule))
        {
            return fromModule;
        }
        fail("cannot find " + relative + " from " + Path.of(".").toAbsolutePath());
        return p;
    }
}
