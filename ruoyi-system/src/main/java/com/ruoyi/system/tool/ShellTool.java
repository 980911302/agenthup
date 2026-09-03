package com.ruoyi.system.tool;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.ruoyi.common.utils.StringUtils;

/**
 * Pi {@code bash}:在配置的 cwd 执行命令。绝对/相对 workdir 都不进会话沙箱。
 */
@Component
public class ShellTool implements ToolOutcomeAware, UiArtifactAware
{
    private static final Logger log = LoggerFactory.getLogger(ShellTool.class);

    static final int DEFAULT_MAX_LINES = 2000;
    static final int DEFAULT_MAX_BYTES = 50 * 1024;
    static final int MAX_CAPTURE_BYTES = 1024 * 1024;

    /**
     * 进程 exit 之后再等管道收尾的宽限。
     *
     * <p>命令末尾的输出可能还在管道里没读完,exit 一到就收流会把它静默丢掉;
     * 但也不能无限等 —— 后台常驻子孙进程会一直握着这根管道。
     */
    static final long IDLE_GRACE_MS = 300L;

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    /** stdin 接空设备:命令等输入时立刻拿到 EOF,不会挂在无人应答的读上 */
    private static final File NULL_DEVICE = new File(WINDOWS ? "NUL" : "/dev/null");

    private final ThreadLocal<Boolean> lastCallOk = new ThreadLocal<>();
    private final ThreadLocal<List<UiArtifact>> lastArtifacts = new ThreadLocal<>();

    private static final List<Pattern> DANGEROUS = List.of(
        Pattern.compile("rm\\s+(-[a-zA-Z]*f[a-zA-Z]*\\s+)?(/|~|/\\*|\\\\)"),
        Pattern.compile("rm\\s+-rf\\s+/"),
        Pattern.compile("rm\\s+-rf\\s+/\\*"),
        Pattern.compile("shutdown\\b"),
        Pattern.compile("reboot\\b"),
        Pattern.compile("poweroff\\b"),
        Pattern.compile("halt\\b"),
        Pattern.compile("mkfs\\b"),
        Pattern.compile("dd\\s+.*\\bif="),
        Pattern.compile(":\\s*\\(\\)\\s*\\{\\s*:\\s*\\|\\s*:\\s*&\\s*\\}"),
        Pattern.compile("\\bcurl\\b.*\\|\\s*(ba)?sh"),
        Pattern.compile("\\bwget\\b.*\\|\\s*(ba)?sh"),
        Pattern.compile("\\b(chmod|chown)\\s+-R\\s+.*\\s+/"),
        Pattern.compile(">\\s*/dev/sd"),
        Pattern.compile("mkfs\\."),
        Pattern.compile("\\bformat\\s+[a-zA-Z]:"),
        Pattern.compile("del\\s+/[fq]\\s+"),
        Pattern.compile("rd\\s+/s\\s+/q\\s+"),
        Pattern.compile("Remove-Item\\s+.*-Recurse.*C:\\\\", Pattern.CASE_INSENSITIVE)
    );

    private final AiToolProperties aiToolProperties;

    public ShellTool(AiToolProperties aiToolProperties)
    {
        this.aiToolProperties = aiToolProperties;
    }

    public ToolCallback bashTool()
    {
        ToolCallback inner = FunctionToolCallback.builder("bash",
                (Function<BashRequest, String>) this::bash)
            .description("Execute a bash command in the current working directory. Returns stdout and stderr. "
                + "Output is truncated to last " + DEFAULT_MAX_LINES + " lines or "
                + (DEFAULT_MAX_BYTES / 1024) + "KB (whichever is hit first). "
                + "If truncated, full output is saved to a temp file. "
                + "Optionally provide a timeout in seconds. "
                + "This call blocks until the command exits, so long-running services "
                + "(dev/preview servers, watchers, daemons) MUST be backgrounded with output "
                + "redirected, e.g. `nohup <cmd> > /tmp/x.log 2>&1 &`, then poll readiness with "
                + "curl in a separate call. A foreground service will simply hit the timeout "
                + "and be killed along with its child processes. "
                + "Beware `pkill -f` / `pgrep -f`: they match the FULL command line, and your "
                + "command runs as `bash -lc \"<whole command>\"`, so the pattern matches the "
                + "hosting shell itself and kills it (exit code 143). Use a bracket trick like "
                + "`pkill -f \"[v]ite preview\"`, or match on the port instead, e.g. "
                + "`fuser -k 4173/tcp` or `kill $(cat /tmp/x.pid)`.")
            .inputType(BashRequest.class)
            .build();
        return new OutcomeForwardingCallback(inner, this, this);
    }

    @Override
    public Boolean lastCallOk()
    {
        Boolean ok = lastCallOk.get();
        lastCallOk.remove();
        return ok;
    }

    @Override
    public List<UiArtifact> lastArtifacts()
    {
        List<UiArtifact> artifacts = lastArtifacts.get();
        lastArtifacts.remove();
        return artifacts;
    }

    private String bash(BashRequest request)
    {
        lastCallOk.remove();
        lastArtifacts.remove();
        if (!aiToolProperties.isShellEnabled())
        {
            return finishCall(false, "Shell is disabled (ruoyi.ai.tool.shell-enabled=false).");
        }
        if (request == null || StringUtils.isEmpty(request.command))
        {
            return finishCall(false, "command is required");
        }
        String command = request.command.trim();
        String danger = matchDangerous(command);
        if (danger != null)
        {
            log.warn("bash blocked dangerous command: {}", command);
            return finishCall(false, "拒绝执行危险命令（命中黑名单）: " + danger);
        }

        long timeoutMs = resolveTimeoutMs(request.timeout);
        try
        {
            Path workDir = ProjectPaths.resolve(aiToolProperties, request.workdir);
            if (!Files.isDirectory(workDir))
            {
                return finishCall(false, "Working directory does not exist: " + workDir
                        + "\nCannot execute bash commands.");
            }
            WorkspaceChanges.Manifest before = WorkspaceChanges.snapshot(ProjectPaths.cwd(aiToolProperties));
            boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
            List<String> cmd = new ArrayList<>();
            if (windows)
            {
                cmd.add("cmd");
                cmd.add("/c");
                cmd.add(command);
            }
            else
            {
                cmd.add("bash");
                cmd.add("-lc");
                cmd.add(command);
            }

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(workDir.toFile());
            pb.redirectErrorStream(true);
            pb.redirectInput(ProcessBuilder.Redirect.from(NULL_DEVICE));
            Process process = pb.start();

            // StringBuffer 而非 StringBuilder:pump 线程写、主线程读,超时路径下
            // join 可能等不到 pump 结束,此时仍要能读出一个一致的快照。
            StringBuffer captured = new StringBuffer();
            Charset charset = windows ? Charset.defaultCharset() : StandardCharsets.UTF_8;

            // 读流必须另起线程。原先在主线程读到 EOF 再 waitFor,而 EOF 要等所有持有
            // 管道写端的进程退出 —— `nohup python3 -m http.server &` 这类常驻后代会
            // 一直握着它,于是读循环永不返回,后面那句带超时的 waitFor 根本走不到,
            // 整个工具调用线程无限挂死(不是超时失败)。
            // 现在改成:主线程只等 exit,读流交给守护线程(管道被握住时它退不出,
            // 非守护会让 JVM 关不掉)。
            Thread pump = new Thread(() -> pumpOutput(process, charset, captured), "bash-out-pump");
            pump.setDaemon(true);
            pump.start();

            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished)
            {
                killTree(process);
                joinQuietly(pump);
                return finishCall(false, formatOutput(captured.toString(), workDir, null)
                        + "\nCommand timed out before completion.", before);
            }
            // exit 之后给管道一小段收尾时间,别把末尾输出丢了
            joinQuietly(pump);
            int code = process.exitValue();
            return finishCall(code == 0, formatOutput(captured.toString(), workDir, code), before);
        }
        catch (Exception e)
        {
            log.warn("bash failed: {}", e.getMessage());
            return finishCall(false, "执行失败: " + e.getMessage());
        }
    }

    /** 把合并后的 stdout/stderr 抽干到 sink。进程被强杀时管道会断,那里的 IO 异常是预期的。 */
    private static void pumpOutput(Process process, Charset charset, StringBuffer sink)
    {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), charset)))
        {
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) >= 0)
            {
                // 必须一直读到底把管道抽空,否则子进程写满缓冲区会卡住。
                // 但保留的是「尾部」而非头部:命令的结论都在末尾(错误栈、最终结果),
                // 保头会把最有用的部分丢掉 —— 对齐 pi 对 bash 输出用 truncateTail。
                sink.append(buf, 0, n);
                if (sink.length() > MAX_CAPTURE_BYTES)
                {
                    sink.delete(0, sink.length() - MAX_CAPTURE_BYTES);
                }
            }
        }
        catch (Exception ignored)
        {
        }
    }

    /**
     * 杀进程树,不只是杀 bash 自己。
     *
     * <p>只 {@code destroyForcibly()} 父进程的话,{@code nohup} 出去的服务会活下来继续
     * 占着端口,下次启动同一个服务直接 Address already in use。先取子孙快照再动手,
     * 避免父进程先死导致子孙被 reparent 后找不回来。
     */
    private static void killTree(Process process)
    {
        List<ProcessHandle> descendants = process.descendants().toList();
        process.destroyForcibly();
        for (ProcessHandle handle : descendants)
        {
            handle.destroyForcibly();
        }
    }

    private static void joinQuietly(Thread pump)
    {
        try
        {
            pump.join(IDLE_GRACE_MS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }

    private long resolveTimeoutMs(Double timeoutSeconds)
    {
        if (timeoutSeconds != null && timeoutSeconds > 0 && Double.isFinite(timeoutSeconds))
        {
            long ms = (long) (timeoutSeconds * 1000);
            long cap = 10L * 60 * 1000;
            return Math.min(ms, cap);
        }
        long configured = aiToolProperties.getShellTimeoutMs();
        return configured > 0 ? configured : 30_000L;
    }

    private String formatOutput(String raw, Path workDir, Integer exit)
    {
        String full = raw == null ? "" : raw;
        String preview = tailPreview(full);
        StringBuilder sb = new StringBuilder();
        if (exit != null)
        {
            sb.append("Command exited with code ").append(exit).append(".\n");
        }
        sb.append("workDir=").append(workDir).append('\n');
        if (preview.length() < full.length())
        {
            try
            {
                Path tmp = Files.createTempFile("bash-output-", ".txt");
                Files.writeString(tmp, full, StandardCharsets.UTF_8);
                sb.append("[Truncated: last ").append(DEFAULT_MAX_LINES).append(" lines or ")
                    .append(DEFAULT_MAX_BYTES / 1024).append("KB. Full output: ")
                    .append(tmp.toAbsolutePath()).append("]\n");
            }
            catch (Exception e)
            {
                sb.append("[Truncated; failed to save full output: ").append(e.getMessage()).append("]\n");
            }
        }
        if (!preview.isEmpty())
        {
            sb.append(preview);
        }
        else if (exit != null)
        {
            sb.append("(no output)");
        }
        return sb.toString();
    }

    static String tailPreview(String full)
    {
        if (full == null || full.isEmpty())
        {
            return "";
        }
        String[] lines = full.split("\n", -1);
        int from = Math.max(0, lines.length - DEFAULT_MAX_LINES);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < lines.length; i++)
        {
            if (i > from)
            {
                sb.append('\n');
            }
            sb.append(lines[i]);
        }
        String preview = sb.toString();
        if (preview.length() <= DEFAULT_MAX_BYTES)
        {
            return preview;
        }
        return preview.substring(preview.length() - DEFAULT_MAX_BYTES);
    }

    private String finishCall(boolean ok, String text)
    {
        lastCallOk.set(ok);
        return text;
    }

    private String finishCall(boolean ok, String text, WorkspaceChanges.Manifest before)
    {
        finishCall(ok, text);
        UiArtifact artifact = WorkspaceChanges.diffArtifact(before,
                WorkspaceChanges.snapshot(ProjectPaths.cwd(aiToolProperties)));
        if (artifact != null)
        {
            lastArtifacts.set(List.of(artifact));
        }
        return text;
    }

    private static String matchDangerous(String command)
    {
        for (Pattern p : DANGEROUS)
        {
            if (p.matcher(command).find())
            {
                return p.pattern();
            }
        }
        return null;
    }

    private static final class OutcomeForwardingCallback
            implements ToolCallback, ToolOutcomeAware, UiArtifactAware
    {
        private final ToolCallback inner;
        private final ToolOutcomeAware outcome;
        private final UiArtifactAware artifacts;

        private OutcomeForwardingCallback(ToolCallback inner, ToolOutcomeAware outcome,
                                          UiArtifactAware artifacts)
        {
            this.inner = inner;
            this.outcome = outcome;
            this.artifacts = artifacts;
        }

        @Override
        public ToolDefinition getToolDefinition()
        {
            return inner.getToolDefinition();
        }

        @Override
        public String call(String toolInput)
        {
            return inner.call(toolInput);
        }

        @Override
        public String call(String toolInput, ToolContext toolContext)
        {
            return inner.call(toolInput, toolContext);
        }

        @Override
        public Boolean lastCallOk()
        {
            return outcome.lastCallOk();
        }

        @Override
        public List<UiArtifact> lastArtifacts()
        {
            return artifacts.lastArtifacts();
        }
    }

    public static class BashRequest
    {
        @JsonProperty(value = "command", required = true)
        @JsonPropertyDescription("The command to execute")
        public String command;

        @JsonProperty("timeout")
        @JsonPropertyDescription("Timeout in seconds (optional, no default timeout)")
        public Double timeout;

        @JsonProperty("workdir")
        @JsonPropertyDescription("The working directory to run the command in. Defaults to the current directory. Use this instead of 'cd' commands.")
        public String workdir;
    }
}
