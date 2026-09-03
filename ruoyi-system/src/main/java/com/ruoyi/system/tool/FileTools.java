package com.ruoyi.system.tool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.content.Media;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.ruoyi.common.utils.StringUtils;

/**
 * Pi 工作区工具:read / write / edit / grep / find / ls。
 * 路径相对 {@link AiToolProperties#getCwd()},绝对路径不限制。
 */
@Component
public class FileTools implements PromptMediaAware, AttachmentAware, UiArtifactAware
{
    private static final Logger log = LoggerFactory.getLogger(FileTools.class);

    static final int DEFAULT_MAX_LINES = 2000;
    static final int DEFAULT_MAX_BYTES = 50 * 1024;
    static final int GREP_DEFAULT_LIMIT = 100;
    static final int GREP_MAX_LINE_LENGTH = 500;
    static final int FIND_DEFAULT_LIMIT = 1000;
    static final int LS_DEFAULT_LIMIT = 500;
    static final int MAX_LINE_CHARS = 2000;

    /**
     * 单份媒体挂进模型上下文的体积上限。base64 会再膨胀约 33%,20MB 的原文件已接近
     * 主流上游的请求体上限;超过就只报路径,不挂 Media —— 否则整轮请求会被打回。
     */
    static final long MAX_MEDIA_BYTES = 20L * 1024 * 1024;

    private final AiToolProperties aiToolProperties;
    private final ThreadLocal<List<Media>> lastMedia = new ThreadLocal<>();
    private final ThreadLocal<List<ToolAttachment>> lastAttach = new ThreadLocal<>();
    private final ThreadLocal<List<UiArtifact>> lastArtifacts = new ThreadLocal<>();

    public FileTools(AiToolProperties aiToolProperties)
    {
        this.aiToolProperties = aiToolProperties;
    }

    public ToolCallback readTool()
    {
        ToolCallback inner = FunctionToolCallback.builder("read",
                (Function<ReadRequest, String>) this::read)
            .description("Read a file from the workspace. Paths are relative to the workspace root. "
                + "Text files come back as numbered lines; media files do not come back as text at all. "
                + "Media means images (jpg, png, gif, webp, bmp), PDF documents, and audio (mp3, wav): "
                + "reading one loads it directly into your perceptual context, so you perceive the content "
                + "itself and can describe, quote, or analyse it in your reply. "
                + "A file loaded this way stays loaded for the remainder of the current turn — reading the "
                + "same path twice in one turn consumes context and returns nothing new. It does not carry "
                + "over to later turns; read it again there if you still need it. "
                + "Whether you actually perceive a media file depends on the model in use. If it loads but "
                + "you perceive nothing, say so plainly — never guess at or invent its content. "
                + "Media above " + (MAX_MEDIA_BYTES / 1024 / 1024) + "MB is refused; the file still exists, "
                + "so inspect or downsample it by other means. "
                + "For text files, output is truncated to "
                + DEFAULT_MAX_LINES + " lines or " + (DEFAULT_MAX_BYTES / 1024)
                + "KB (whichever is hit first). Use offset/limit for large files. "
                + "When you need the full file, continue with offset until complete.")
            .inputType(ReadRequest.class)
            .build();
        return new AwareForwardingCallback(inner, this);
    }

    public ToolCallback writeTool()
    {
        ToolCallback inner = FunctionToolCallback.builder("write",
                (Function<WriteRequest, String>) this::write)
            .description("Writes a file to the local filesystem. Overwrites the existing file if there is one at the provided path. "
                + "ALWAYS prefer editing existing files. NEVER write new files unless explicitly required. "
                + "NEVER proactively create documentation files (*.md) or README files unless the user asked.")
            .inputType(WriteRequest.class)
            .build();
        return new AwareForwardingCallback(inner, this);
    }

    public ToolCallback editTool()
    {
        ToolCallback inner = FunctionToolCallback.builder("edit",
                (Function<EditRequest, String>) this::edit)
            .description("Performs exact string replacements in files. "
                + "edits[].oldText must match exactly and be unique in the original file. "
                + "The edit will FAIL if oldText is not found, or if it matches multiple times "
                + "(provide more surrounding context, or merge nearby changes into one edit). "
                + "Each edit is matched against the original file, not incrementally. "
                + "Do not include overlapping or nested edits.")
            .inputType(EditRequest.class)
            .build();
        return new AwareForwardingCallback(inner, this);
    }

    public ToolCallback grepTool()
    {
        return FunctionToolCallback.builder("grep",
                (Function<GrepRequest, String>) this::grep)
            .description("Search file contents for a pattern. Returns matching lines with file paths and line numbers. "
                + "Respects .gitignore. Output is truncated to " + GREP_DEFAULT_LIMIT
                + " matches or " + (DEFAULT_MAX_BYTES / 1024) + "KB (whichever is hit first). "
                + "Long lines are truncated to " + GREP_MAX_LINE_LENGTH + " chars.")
            .inputType(GrepRequest.class)
            .build();
    }

    public ToolCallback findTool()
    {
        return FunctionToolCallback.builder("find",
                (Function<FindRequest, String>) this::find)
            .description("Search for files by glob pattern. Returns matching file paths relative to the search directory. "
                + "Respects .gitignore. Output is truncated to " + FIND_DEFAULT_LIMIT
                + " results or " + (DEFAULT_MAX_BYTES / 1024) + "KB (whichever is hit first).")
            .inputType(FindRequest.class)
            .build();
    }

    public ToolCallback lsTool()
    {
        return FunctionToolCallback.builder("ls",
                (Function<LsRequest, String>) this::ls)
            .description("List directory contents. Returns entries sorted alphabetically, with '/' suffix for directories. "
                + "Includes dotfiles. Output is truncated to " + LS_DEFAULT_LIMIT
                + " entries or " + (DEFAULT_MAX_BYTES / 1024) + "KB (whichever is hit first).")
            .inputType(LsRequest.class)
            .build();
    }

    @Override
    public List<Media> lastPromptMedia()
    {
        return lastMedia.get();
    }

    @Override
    public List<ToolAttachment> lastAttachments()
    {
        return lastAttach.get();
    }

    @Override
    public List<UiArtifact> lastArtifacts()
    {
        List<UiArtifact> artifacts = lastArtifacts.get();
        lastArtifacts.remove();
        return artifacts;
    }

    private String read(ReadRequest request)
    {
        lastMedia.remove();
        lastAttach.remove();
        if (request == null || StringUtils.isEmpty(request.path))
        {
            return "path is required";
        }
        try
        {
            Path file = ProjectPaths.resolve(aiToolProperties, request.path);
            if (!Files.exists(file))
            {
                return "Path not found: " + file;
            }
            if (Files.isDirectory(file))
            {
                return ls(lsRequest(file.toString(), null));
            }
            if (!Files.isRegularFile(file))
            {
                return "Not a regular file: " + file;
            }
            MimeType mediaMime = mediaMime(file);
            if (mediaMime != null)
            {
                return readMedia(file, mediaMime);
            }
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            int offset = request.offset != null && request.offset > 0 ? request.offset : 1;
            int limit = request.limit != null && request.limit > 0 ? request.limit : DEFAULT_MAX_LINES;
            int from = Math.min(offset - 1, lines.size());
            int to = Math.min(from + limit, lines.size());
            StringBuilder sb = new StringBuilder();
            int bytes = 0;
            int nextOffset = 0;
            for (int i = from; i < to; i++)
            {
                String raw = lines.get(i);
                if (raw.length() > MAX_LINE_CHARS)
                {
                    raw = raw.substring(0, MAX_LINE_CHARS);
                }
                String line = (i + 1) + ": " + raw;
                int add = line.length() + 1;
                if (bytes + add > DEFAULT_MAX_BYTES)
                {
                    nextOffset = i + 1;
                    break;
                }
                if (sb.length() > 0)
                {
                    sb.append('\n');
                }
                sb.append(line);
                bytes += add;
            }
            if (nextOffset == 0 && to < lines.size())
            {
                nextOffset = to + 1;
            }
            if (sb.length() == 0)
            {
                return from >= lines.size() ? "(offset beyond end of file)" : "";
            }
            if (nextOffset > 0)
            {
                sb.append("\n\n[Truncated: showing lines ").append(from + 1).append('-')
                    .append(nextOffset - 1).append(" of ").append(lines.size())
                    .append(". Continue with offset=").append(nextOffset).append(']');
            }
            return sb.toString();
        }
        catch (Exception e)
        {
            log.warn("read failed: {}", e.getMessage());
            return "Read failed: " + e.getMessage();
        }
    }

    /**
     * 图片走两条出口:{@code lastMedia} 给模型,{@code lastAttach} 给前端。
     *
     * <p>两者的可达范围不同。模型侧直接读绝对路径,读到哪算哪;而前端下载接口
     * {@code /ai/chat/workspace/{sessionId}/download?path=} 走 {@code WorkspaceSandbox}
     * ——它会把传入路径的前导 {@code /} 剥掉再拼到会话沙箱根下面,所以只认沙箱内的
     * 相对路径。工具自 Pi 对齐后改用 {@code ProjectPaths},根是 cwd 而非会话沙箱,
     * 两者互不重叠:直接把绝对路径塞进附件,前端必然 404 拿到一张碎图。
     *
     * <p>因此只有落在会话沙箱内的图片才登记附件,并转成沙箱相对路径;沙箱外的图片
     * 前端本来就取不到,宁可不给附件,也不给一个注定裂开的。
     */
    /**
     * 图片只进模型的视觉上下文,**不登记前端附件**。
     *
     * <p>附件表示「我产出了这个东西给你看」,那是 drawImage / captureScreenshot 的语义。
     * read 是模型自己回头查看已有文件,对用户没有新信息 —— 那张图早在生成它的那条消息
     * 里展示过了。登记附件会让同一张图在聊天流里出现两次(实测:drawImage 生成 8 张、
     * 模型 read 了其中一张检查,界面就渲染出 9 张)。
     *
     * <p>此前这个重复被另一个 bug 掩盖着:read 登记的是绝对路径,而下载接口只认沙箱
     * 相对路径,那份附件一律 404 显示成碎图,看起来像「一张好图 + 一张坏图」。
     */
    private void attachMedia(Path file, MimeType type)
    {
        // name 必须给:PDF 走 input_file 时它就是模型看到的 filename
        lastMedia.set(List.of(Media.builder()
                .mimeType(type)
                .data(new FileSystemResource(file.toFile()))
                .name(file.getFileName().toString())
                .build()));
        lastAttach.remove();
    }

    /**
     * 读一份媒体文件:挂进模型自己的上下文,不登记前端附件(理由见 {@link #attachMedia} 上方)。
     *
     * <p>这里不判断模型认不认这种模态 —— 工具层没有模型配置,判定属于
     * {@code ModelInputModalities} 的门控层。工具只负责把它认得出的媒体挂上去,
     * 送不出去的会在门控层转成给模型的文字说明。唯一在这里拦的是体积。
     */
    private String readMedia(Path file, MimeType type) throws IOException
    {
        long size = Files.size(file);
        if (size > MAX_MEDIA_BYTES)
        {
            lastMedia.remove();
            return "File too large to load into context: " + file
                    + " (" + type + ", " + size + " bytes, limit " + MAX_MEDIA_BYTES + "). "
                    + "The file exists; use other tools to inspect or downsample it first.";
        }
        attachMedia(file, type);
        String kind = "application".equals(type.getType()) ? "Document" : capitalize(type.getType());
        return kind + " loaded into your context: " + file
                + " (" + type + ", " + size + " bytes). "
                + "Its content is available to you directly now — work from what you perceive, "
                + "and answer about it in your own words rather than quoting this message back. "
                + "It stays loaded for the rest of this turn, so reading the same path again here "
                + "returns nothing new. "
                + "If it loaded but you perceive no content, say so plainly instead of guessing: "
                + "the model in use may not accept this input type.";
    }

    private static String capitalize(String s)
    {
        return s == null || s.isEmpty() ? s
                : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * 能挂进模型上下文的媒体类型 → MIME。不是这些的按文本读。
     *
     * <p>音频的 MIME 必须是字面 {@code audio/mp3} / {@code audio/wav} —— Spring AI 的
     * {@code mapToMediaContent} 判的就是这两个字符串,写成标准的 {@code audio/mpeg}
     * 会掉进兜底分支被当成图片发出去。
     *
     * <p>视频也认:它送不出去,但认出来才能在门控层给一句「视频无法送入模型」,
     * 而不是把二进制当文本读出满屏乱码。
     */
    private static MimeType mediaMime(Path file)
    {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        String mime = null;
        if (name.endsWith(".jpg") || name.endsWith(".jpeg"))
        {
            mime = "image/jpeg";
        }
        else if (name.endsWith(".png"))
        {
            mime = "image/png";
        }
        else if (name.endsWith(".gif"))
        {
            mime = "image/gif";
        }
        else if (name.endsWith(".webp"))
        {
            mime = "image/webp";
        }
        else if (name.endsWith(".bmp"))
        {
            mime = "image/bmp";
        }
        else if (name.endsWith(".pdf"))
        {
            mime = "application/pdf";
        }
        else if (name.endsWith(".mp3"))
        {
            mime = "audio/mp3";
        }
        else if (name.endsWith(".wav"))
        {
            mime = "audio/wav";
        }
        else if (name.endsWith(".mp4"))
        {
            mime = "video/mp4";
        }
        else if (name.endsWith(".webm"))
        {
            mime = "video/webm";
        }
        else if (name.endsWith(".mov"))
        {
            mime = "video/quicktime";
        }
        return mime == null ? null : MimeTypeUtils.parseMimeType(mime);
    }

    private String write(WriteRequest request)
    {
        lastArtifacts.remove();
        if (request == null || StringUtils.isEmpty(request.path))
        {
            return "path is required";
        }
        if (request.content == null)
        {
            return "content must not be null (use an empty string)";
        }
        try
        {
            Path file = ProjectPaths.resolve(aiToolProperties, request.path);
            // 同一文件的写串行化:工具在同一批次里是并行执行的,两个 write 撞同一个文件
            // 会后者整体覆盖前者,且不报任何错
            return FileMutationQueue.withLock(file, () -> {
                try
                {
                    boolean existed = Files.exists(file);
                    Path parent = file.getParent();
                    if (parent != null)
                    {
                        Files.createDirectories(parent);
                    }
                    Files.writeString(file, request.content, StandardCharsets.UTF_8);
                    publishMutation(file, existed
                            ? WorkspaceChanges.Operation.MODIFY : WorkspaceChanges.Operation.CREATE);
                    return "Wrote " + file + " (" + request.content.length() + " chars)";
                }
                catch (Exception e)
                {
                    log.warn("write failed: {}", e.getMessage());
                    return "Write failed: " + e.getMessage();
                }
            });
        }
        catch (Exception e)
        {
            log.warn("write failed: {}", e.getMessage());
            return "Write failed: " + e.getMessage();
        }
    }

    private String edit(EditRequest request)
    {
        lastArtifacts.remove();
        if (request == null || StringUtils.isEmpty(request.path))
        {
            return "path is required";
        }
        List<EditOp> ops = request.edits;
        if (ops == null || ops.isEmpty())
        {
            return "edits[] is required";
        }
        try
        {
            Path file = ProjectPaths.resolve(aiToolProperties, request.path);
            // 读-改-写必须整体在锁内:并行批次里两个 edit 各自读到原文、各自全量回写,
            // 后写的会把先写的那次替换整个抹掉,且不报错
            return FileMutationQueue.withLock(file, () -> applyEdits(file, ops));
        }
        catch (Exception e)
        {
            log.warn("edit failed: {}", e.getMessage());
            return "Edit failed: " + e.getMessage();
        }
    }

    /** edit 的读-改-写主体,调用方保证已持有该文件的互斥锁。 */
    private String applyEdits(Path file, List<EditOp> ops)
    {
        try
        {
            if (!Files.isRegularFile(file))
            {
                return "File not found: " + file;
            }
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            // 匹配一律在「去 BOM + 全 LF」的规范形态上做:文件是 CRLF 而模型给的
            // oldText 是 LF 时,直接 indexOf 必然找不到,edit 会报 not found —— 而
            // 这跟模型写得对不对无关。写回时按原文件的行尾与 BOM 还原,不改变文件形态。
            boolean hadBom = !raw.isEmpty() && raw.charAt(0) == '﻿';
            String withoutBom = hadBom ? raw.substring(1) : raw;
            String lineEnding = detectLineEnding(withoutBom);
            String original = normalizeToLf(withoutBom);
            record Span(int start, int end, String neu) {}
            List<Span> spans = new ArrayList<>();
            for (int i = 0; i < ops.size(); i++)
            {
                EditOp op = ops.get(i);
                String oldText = op == null || op.oldText == null ? "" : op.oldText;
                String newText = op == null || op.newText == null ? "" : op.newText;
                if (oldText.isEmpty())
                {
                    return "edits[" + i + "].oldText must not be empty";
                }
                oldText = normalizeToLf(stripBom(oldText));
                newText = normalizeToLf(stripBom(newText));
                int first = original.indexOf(oldText);
                if (first < 0)
                {
                    // 精确匹配不上时按行尾空白归一化再找一次:模型复制整段代码时
                    // 很容易丢掉行尾空格,为此整条 edit 失败不值得
                    int[] fuzzy = fuzzyFindByLines(original, oldText);
                    if (fuzzy == null)
                    {
                        return "oldText not found in content (edits[" + i + "])";
                    }
                    spans.add(new Span(fuzzy[0], fuzzy[1], newText));
                    continue;
                }
                int second = original.indexOf(oldText, first + oldText.length());
                if (second >= 0)
                {
                    return "Found multiple matches for oldText. Provide more surrounding lines in oldText to identify the correct match. (edits[" + i + "])";
                }
                spans.add(new Span(first, first + oldText.length(), newText));
            }
            spans.sort(Comparator.comparingInt(Span::start));
            for (int i = 1; i < spans.size(); i++)
            {
                if (spans.get(i).start() < spans.get(i - 1).end())
                {
                    return "Overlapping or nested edits are not allowed. Merge nearby changes into one edit.";
                }
            }
            StringBuilder out = new StringBuilder();
            int cursor = 0;
            for (Span s : spans)
            {
                out.append(original, cursor, s.start());
                out.append(s.neu());
                cursor = s.end();
            }
            out.append(original.substring(cursor));
            // 还原成原文件的形态:行尾与 BOM 都不因为一次 edit 而改变,
            // 否则整个文件在 diff 里全行标红
            String result = restoreLineEndings(out.toString(), lineEnding);
            if (hadBom)
            {
                result = '﻿' + result;
            }
            Files.writeString(file, result, StandardCharsets.UTF_8);
            publishMutation(file, WorkspaceChanges.Operation.MODIFY);
            return "Edited " + file + " (" + spans.size() + " replacement" + (spans.size() == 1 ? "" : "s") + ")";
        }
        catch (Exception e)
        {
            log.warn("edit failed: {}", e.getMessage());
            return "Edit failed: " + e.getMessage();
        }
    }

    private static String stripBom(String s)
    {
        return !s.isEmpty() && s.charAt(0) == '﻿' ? s.substring(1) : s;
    }

    private void publishMutation(Path file, WorkspaceChanges.Operation operation)
    {
        WorkspaceChanges.Change change = WorkspaceChanges.current(
                ProjectPaths.cwd(aiToolProperties), file, operation);
        UiArtifact artifact = WorkspaceChanges.artifact(change == null ? List.of() : List.of(change));
        if (artifact != null)
        {
            lastArtifacts.set(List.of(artifact));
        }
    }

    /** 首个 \n 前有没有 \r 就按它定;没有换行时按 LF。 */
    private static String detectLineEnding(String s)
    {
        int lf = s.indexOf('\n');
        if (lf < 0)
        {
            return "\n";
        }
        return lf > 0 && s.charAt(lf - 1) == '\r' ? "\r\n" : "\n";
    }

    private static String normalizeToLf(String s)
    {
        return s.indexOf('\r') < 0 ? s : s.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String restoreLineEndings(String lfText, String lineEnding)
    {
        return "\n".equals(lineEnding) ? lfText : lfText.replace("\n", lineEnding);
    }

    /**
     * 精确匹配失败后的兜底:按行去掉行尾空白再比。
     *
     * <p>只处理「整行集合」的匹配 —— needle 的首尾都要落在行边界上。行内片段的
     * 匹配已由前面的 indexOf 覆盖;这里救的是模型整段复制代码、行尾空格被吃掉的情况。
     * 归一化只删每行末尾的空白,行内偏移不变,所以能按行号精确映射回原文位置。
     *
     * @return {@code [start, end)},找不到或找到多处都返回 null(多处一律拒绝,与精确匹配同规则)
     */
    static int[] fuzzyFindByLines(String hay, String needle)
    {
        String[] hLines = hay.split("\n", -1);
        String[] nLines = needle.split("\n", -1);
        if (nLines.length > hLines.length)
        {
            return null;
        }
        int[] hStart = new int[hLines.length];
        int pos = 0;
        for (int i = 0; i < hLines.length; i++)
        {
            hStart[i] = pos;
            pos += hLines[i].length() + 1;
        }
        String[] nNorm = new String[nLines.length];
        for (int i = 0; i < nLines.length; i++)
        {
            nNorm[i] = stripTrailingWhitespace(nLines[i]);
        }
        int[] hit = null;
        for (int i = 0; i + nLines.length <= hLines.length; i++)
        {
            boolean ok = true;
            for (int j = 0; j < nNorm.length; j++)
            {
                if (!stripTrailingWhitespace(hLines[i + j]).equals(nNorm[j]))
                {
                    ok = false;
                    break;
                }
            }
            if (!ok)
            {
                continue;
            }
            if (hit != null)
            {
                return null;
            }
            int last = i + nLines.length - 1;
            hit = new int[] {hStart[i], hStart[last] + hLines[last].length()};
        }
        return hit;
    }

    private static String stripTrailingWhitespace(String line)
    {
        int end = line.length();
        while (end > 0 && (line.charAt(end - 1) == ' ' || line.charAt(end - 1) == '\t'))
        {
            end--;
        }
        return end == line.length() ? line : line.substring(0, end);
    }

    private String grep(GrepRequest request)
    {
        if (request == null || StringUtils.isEmpty(request.pattern))
        {
            return "pattern is required";
        }
        try
        {
            Path start = ProjectPaths.resolve(aiToolProperties, request.path);
            if (!Files.exists(start))
            {
                return "Path not found: " + start;
            }
            boolean literal = Boolean.TRUE.equals(request.literal);
            boolean ignoreCase = Boolean.TRUE.equals(request.ignoreCase);
            int flags = ignoreCase ? java.util.regex.Pattern.CASE_INSENSITIVE : 0;
            java.util.regex.Pattern regex;
            try
            {
                String src = literal ? java.util.regex.Pattern.quote(request.pattern) : request.pattern;
                regex = java.util.regex.Pattern.compile(src, flags);
            }
            catch (Exception e)
            {
                return "Invalid regex: " + e.getMessage();
            }
            int context = request.context != null && request.context > 0 ? request.context : 0;
            int limit = request.limit != null && request.limit > 0 ? request.limit : GREP_DEFAULT_LIMIT;
            String glob = StringUtils.isNotEmpty(request.glob) ? request.glob.trim() : null;
            GitIgnoreRules ignore = GitIgnoreRules.load(start.isAbsolute() && Files.isDirectory(start)
                    ? start : start.getParent() == null ? start : Files.isDirectory(start) ? start : start.getParent());
            if (Files.isRegularFile(start))
            {
                ignore = GitIgnoreRules.load(start.getParent() == null ? start : start.getParent());
            }
            List<String> hits = new ArrayList<>();
            int[] bytes = {0};
            walkFiles(start, glob, ignore, file -> {
                if (hits.size() >= limit || bytes[0] >= DEFAULT_MAX_BYTES)
                {
                    return false;
                }
                try
                {
                    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                    String rel = relativize(start.isAbsolute() && Files.isDirectory(start)
                            ? start : ProjectPaths.cwd(aiToolProperties), file);
                    for (int i = 0; i < lines.size() && hits.size() < limit; i++)
                    {
                        if (!regex.matcher(lines.get(i)).find())
                        {
                            continue;
                        }
                        int from = Math.max(0, i - context);
                        int to = Math.min(lines.size() - 1, i + context);
                        for (int j = from; j <= to && hits.size() < limit; j++)
                        {
                            String text = lines.get(j);
                            if (text.length() > GREP_MAX_LINE_LENGTH)
                            {
                                text = text.substring(0, GREP_MAX_LINE_LENGTH);
                            }
                            String row = rel + ":" + (j + 1) + ":" + text;
                            bytes[0] += row.length() + 1;
                            if (bytes[0] > DEFAULT_MAX_BYTES)
                            {
                                return false;
                            }
                            hits.add(row);
                        }
                    }
                }
                catch (Exception ignored)
                {
                }
                return hits.size() < limit && bytes[0] < DEFAULT_MAX_BYTES;
            });
            if (hits.isEmpty())
            {
                return "No matches found";
            }
            StringBuilder sb = new StringBuilder();
            for (String h : hits)
            {
                sb.append(h).append('\n');
            }
            if (hits.size() >= limit || bytes[0] >= DEFAULT_MAX_BYTES)
            {
                sb.append("\n[").append(limit).append(" results limit reached. Use limit=")
                    .append(limit * 2).append(" for more, or refine pattern]");
            }
            return sb.toString().trim();
        }
        catch (Exception e)
        {
            log.warn("grep failed: {}", e.getMessage());
            return "Grep failed: " + e.getMessage();
        }
    }

    private String find(FindRequest request)
    {
        if (request == null || StringUtils.isEmpty(request.pattern))
        {
            return "pattern is required";
        }
        try
        {
            Path start = ProjectPaths.resolve(aiToolProperties, request.path);
            if (!Files.exists(start))
            {
                return "Path not found: " + start;
            }
            int limit = request.limit != null && request.limit > 0 ? request.limit : FIND_DEFAULT_LIMIT;
            GitIgnoreRules ignore = GitIgnoreRules.load(Files.isDirectory(start) ? start
                    : start.getParent() == null ? start : start.getParent());
            List<String> matches = new ArrayList<>();
            walkFiles(start, request.pattern.trim(), ignore, file -> {
                if (matches.size() >= limit)
                {
                    return false;
                }
                matches.add(relativize(Files.isDirectory(start) ? start : ProjectPaths.cwd(aiToolProperties), file));
                return matches.size() < limit;
            });
            if (matches.isEmpty())
            {
                return "No files found matching pattern";
            }
            StringBuilder sb = new StringBuilder();
            int bytes = 0;
            for (String m : matches)
            {
                bytes += m.length() + 1;
                if (bytes > DEFAULT_MAX_BYTES)
                {
                    sb.append("\n[").append(DEFAULT_MAX_BYTES / 1024).append("KB limit reached]");
                    break;
                }
                sb.append(m).append('\n');
            }
            if (matches.size() >= limit)
            {
                sb.append("\n[").append(limit).append(" results limit reached. Use limit=")
                    .append(limit * 2).append(" for more, or refine pattern]");
            }
            return sb.toString().trim();
        }
        catch (Exception e)
        {
            log.warn("find failed: {}", e.getMessage());
            return "Find failed: " + e.getMessage();
        }
    }

    private String ls(LsRequest request)
    {
        try
        {
            Path dir = ProjectPaths.resolve(aiToolProperties, request == null ? null : request.path);
            if (!Files.exists(dir))
            {
                return "Path not found: " + dir;
            }
            if (!Files.isDirectory(dir))
            {
                return "Not a directory: " + dir;
            }
            int limit = request != null && request.limit != null && request.limit > 0
                    ? request.limit : LS_DEFAULT_LIMIT;
            List<String> names = new ArrayList<>();
            try (var stream = Files.list(dir))
            {
                names = stream.map(p -> p.getFileName().toString())
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
            }
            if (names.isEmpty())
            {
                return "(empty directory)";
            }
            StringBuilder sb = new StringBuilder();
            int count = 0;
            int bytes = 0;
            for (String name : names)
            {
                if (count >= limit)
                {
                    sb.append("\n[").append(limit).append(" entries limit reached. Use limit=")
                        .append(limit * 2).append(" for more]");
                    break;
                }
                Path full = dir.resolve(name);
                String row = name;
                try
                {
                    if (Files.isDirectory(full))
                    {
                        row = name + "/";
                    }
                }
                catch (Exception ignored)
                {
                }
                bytes += row.length() + 1;
                if (bytes > DEFAULT_MAX_BYTES)
                {
                    sb.append("\n[").append(DEFAULT_MAX_BYTES / 1024).append("KB limit reached]");
                    break;
                }
                sb.append(row).append('\n');
                count++;
            }
            return sb.toString().trim();
        }
        catch (Exception e)
        {
            log.warn("ls failed: {}", e.getMessage());
            return "ls failed: " + e.getMessage();
        }
    }

    private static LsRequest lsRequest(String path, Integer limit)
    {
        LsRequest r = new LsRequest();
        r.path = path;
        r.limit = limit;
        return r;
    }

    private static String relativize(Path root, Path file)
    {
        try
        {
            return root.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize())
                    .toString().replace('\\', '/');
        }
        catch (IllegalArgumentException e)
        {
            return file.toAbsolutePath().normalize().toString().replace('\\', '/');
        }
    }

    private void walkFiles(Path start, String glob, GitIgnoreRules ignore, java.util.function.Function<Path, Boolean> consumer)
        throws IOException
    {
        java.nio.file.PathMatcher matcher = null;
        if (StringUtils.isNotEmpty(glob))
        {
            String g = glob.contains("/") || glob.contains("**") ? glob : "**/" + glob;
            matcher = start.getFileSystem().getPathMatcher("glob:" + g);
        }
        if (Files.isRegularFile(start))
        {
            if ((matcher == null || matchGlob(matcher, start, start.getParent() == null ? start : start.getParent()))
                    && (ignore == null || !ignore.ignores(start)))
            {
                consumer.apply(start);
            }
            return;
        }
        Path root = start;
        java.nio.file.PathMatcher m = matcher;
        Files.walkFileTree(start, new SimpleFileVisitor<>()
        {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
            {
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                if (GitIgnoreRules.skipWalkName(name) || (ignore != null && !dir.equals(root) && ignore.ignoresDirectory(dir)))
                {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
            {
                if (ignore != null && ignore.ignores(file))
                {
                    return FileVisitResult.CONTINUE;
                }
                if (m != null && !matchGlob(m, file, root))
                {
                    return FileVisitResult.CONTINUE;
                }
                if (attrs.size() > 2L * 1024 * 1024)
                {
                    return FileVisitResult.CONTINUE;
                }
                Boolean cont = consumer.apply(file);
                return Boolean.FALSE.equals(cont) ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc)
            {
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static boolean matchGlob(java.nio.file.PathMatcher matcher, Path file, Path root)
    {
        Path name = file.getFileName();
        if (name != null && matcher.matches(name))
        {
            return true;
        }
        try
        {
            return matcher.matches(root.relativize(file))
                    || matcher.matches(Path.of(root.relativize(file).toString().replace('\\', '/')));
        }
        catch (Exception e)
        {
            return false;
        }
    }

    private static final class AwareForwardingCallback
            implements ToolCallback, PromptMediaAware, AttachmentAware, UiArtifactAware
    {
        private final ToolCallback inner;
        private final FileTools owner;

        private AwareForwardingCallback(ToolCallback inner, FileTools owner)
        {
            this.inner = inner;
            this.owner = owner;
        }

        @Override
        public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition()
        {
            return inner.getToolDefinition();
        }

        @Override
        public String call(String toolInput)
        {
            return inner.call(toolInput);
        }

        @Override
        public String call(String toolInput, org.springframework.ai.chat.model.ToolContext toolContext)
        {
            return inner.call(toolInput, toolContext);
        }

        @Override
        public List<Media> lastPromptMedia()
        {
            return owner.lastPromptMedia();
        }

        @Override
        public List<ToolAttachment> lastAttachments()
        {
            return owner.lastAttachments();
        }

        @Override
        public List<UiArtifact> lastArtifacts()
        {
            return owner.lastArtifacts();
        }
    }

    @SuppressWarnings("unused")
    private static final MimeType WEBP = MimeTypeUtils.parseMimeType("image/webp");

    public static class ReadRequest
    {
        @JsonProperty(value = "path", required = true)
        @JsonPropertyDescription("Path to the file to read (relative or absolute)")
        public String path;

        @JsonProperty("offset")
        @JsonPropertyDescription("Line number to start reading from (1-indexed)")
        public Integer offset;

        @JsonProperty("limit")
        @JsonPropertyDescription("Maximum number of lines to read")
        public Integer limit;
    }

    public static class WriteRequest
    {
        @JsonProperty(value = "path", required = true)
        @JsonPropertyDescription("Path to the file to write (relative or absolute)")
        public String path;

        @JsonProperty(value = "content", required = true)
        @JsonPropertyDescription("Content to write to the file")
        public String content;
    }

    public static class EditRequest
    {
        @JsonProperty(value = "path", required = true)
        @JsonPropertyDescription("Path to the file to edit (relative or absolute)")
        public String path;

        @JsonProperty(value = "edits", required = true)
        @JsonPropertyDescription("One or more targeted replacements. Each edit is matched against the original file, not incrementally. Do not include overlapping or nested edits.")
        public List<EditOp> edits;
    }

    public static class EditOp
    {
        @JsonProperty(value = "oldText", required = true)
        @JsonPropertyDescription("Exact text for one targeted replacement. It must be unique in the original file and must not overlap with any other edits[].oldText in the same call.")
        public String oldText;

        @JsonProperty(value = "newText", required = true)
        @JsonPropertyDescription("Replacement text for this targeted edit.")
        public String newText;
    }

    public static class GrepRequest
    {
        @JsonProperty(value = "pattern", required = true)
        @JsonPropertyDescription("Search pattern (regex or literal string)")
        public String pattern;

        @JsonProperty("path")
        @JsonPropertyDescription("Directory or file to search (default: current directory)")
        public String path;

        @JsonProperty("glob")
        @JsonPropertyDescription("Filter files by glob pattern, e.g. '*.ts' or '**/*.spec.ts'")
        public String glob;

        @JsonProperty("ignoreCase")
        @JsonPropertyDescription("Case-insensitive search (default: false)")
        public Boolean ignoreCase;

        @JsonProperty("literal")
        @JsonPropertyDescription("Treat pattern as literal string instead of regex (default: false)")
        public Boolean literal;

        @JsonProperty("context")
        @JsonPropertyDescription("Number of lines to show before and after each match (default: 0)")
        public Integer context;

        @JsonProperty("limit")
        @JsonPropertyDescription("Maximum number of matches to return (default: 100)")
        public Integer limit;
    }

    public static class FindRequest
    {
        @JsonProperty(value = "pattern", required = true)
        @JsonPropertyDescription("Glob pattern to match files, e.g. '*.ts', '**/*.json', or 'src/**/*.spec.ts'")
        public String pattern;

        @JsonProperty("path")
        @JsonPropertyDescription("Directory to search in (default: current directory)")
        public String path;

        @JsonProperty("limit")
        @JsonPropertyDescription("Maximum number of results (default: 1000)")
        public Integer limit;
    }

    public static class LsRequest
    {
        @JsonProperty("path")
        @JsonPropertyDescription("Directory to list (default: current directory)")
        public String path;

        @JsonProperty("limit")
        @JsonPropertyDescription("Maximum number of entries to return (default: 500)")
        public Integer limit;
    }
}
