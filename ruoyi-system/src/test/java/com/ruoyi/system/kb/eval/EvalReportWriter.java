package com.ruoyi.system.kb.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;

/**
 * 将评测报告写入 target/kb-eval/，不作为源码提交。
 */
public final class EvalReportWriter
{
    private EvalReportWriter()
    {
    }

    public static Path defaultReportDir()
    {
        // 从 user.dir 或模块 target 解析
        Path moduleTarget = Path.of("ruoyi-system", "target", "kb-eval");
        if (Files.isDirectory(Path.of("ruoyi-system")))
        {
            return moduleTarget;
        }
        return Path.of("target", "kb-eval");
    }

    public static Path writeJson(String fileName, Object report) throws IOException
    {
        Path dir = defaultReportDir();
        Files.createDirectories(dir);
        Path out = dir.resolve(fileName);
        String json = JSON.toJSONString(report, JSONWriter.Feature.PrettyFormat);
        Files.writeString(out, json, StandardCharsets.UTF_8);
        return out;
    }

    public static Map<String, Object> envelope(String suite, Map<String, Object> metrics)
    {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("suite", suite);
        env.put("generatedAt", Instant.now().toString());
        env.put("deterministic", true);
        env.put("llmCalls", 0);
        env.put("embeddingCalls", 0);
        env.put("metrics", metrics);
        return env;
    }
}
