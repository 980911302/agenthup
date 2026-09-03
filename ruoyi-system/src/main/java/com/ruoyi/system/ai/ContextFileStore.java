package com.ruoyi.system.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.ai.session.SessionIds;

/**
 * 大字段外置存储:把超大的工具结果 / 思考正文写到文件,数据库只留预览与引用。
 *
 * <pre>
 * {contextPath}/{sessionId}/tools/{uuid}.txt      工具返回原文
 * {contextPath}/{sessionId}/thinking/{uuid}.txt   推理链正文
 * {contextPath}/{sessionId}/run-steps/{id}.txt    run_step.output 审计留痕
 * </pre>
 *
 * <p><b>事实源边界</b>:{@code ai_chat_message} / {@code ai_chat_run} 是消息信封与运行状态的
 * 事实源;这里的文件是它们大字段的不可变外置正文,由 {@code tool_result_path} 引用。
 * 引用存的是<b>相对本根目录</b>的路径,不是绝对路径 —— 换挂载点/换容器/换启动目录之后
 * 老数据才还找得到。生产多实例必须把本目录放在共享、持久化且有备份的存储上。
 * 外置写失败时调用方会退化为正文完整入库,不丢审计。
 *
 * <p><b>曾经还写一份 {@code {agentId}.md} 人读快照</b>(frontmatter + History/Current/ToolCalls
 * 分段),每轮追加两次。但它没有任何消费方:唯一的读取点只拿它判空来决定要不要初始化,
 * 内容从没进过 prompt;{@code appendTurn} / {@code writeCurrent} / {@code clearCurrent} /
 * {@code appendToolCall} / {@code loadToolCalls} 更是零调用。内容与 {@code ai_chat_message}
 * 完全重复,当审计也是冗余。整套连同它的解析/重建机制已删除,本类只保留大字段外置这一个职责。
 *
 * @author ruoyi
 */
@Component
public class ContextFileStore
{
    private static final Logger log = LoggerFactory.getLogger(ContextFileStore.class);

    /**
     * 并发保护:同一路径的写入需要互斥。
     *
     * <p><b>用固定条数的分段锁,不用「按路径建锁」的 Map</b>:后者条目只增不减 ——
     * 每个溢出文件都会留下一个条目,会话删了也不回收,长跑进程里就是一处无界增长。
     * 分段锁把内存钉死成常量,代价只是不同文件偶尔共用一把锁(纯粹多等一下,不影响正确性)。
     */
    private static final int LOCK_STRIPES = 64;
    private static final Object[] FILE_LOCKS = new Object[LOCK_STRIPES];

    static
    {
        for (int i = 0; i < LOCK_STRIPES; i++)
        {
            FILE_LOCKS[i] = new Object();
        }
    }

    /** 取该路径对应的分段锁。同一路径必然落到同一把,互斥语义与原来按路径建锁一致。 */
    private static Object lockFor(Path file)
    {
        return FILE_LOCKS[Math.floorMod(file.normalize().hashCode(), LOCK_STRIPES)];
    }

    private final Path contextRoot;

    public ContextFileStore(@Value("${ai.chat.context-path:/data/ai/sessions}") String contextPath)
    {
        // 必须绝对化:配置值默认是相对路径(./agent-java/ai/sessions),留成相对的话
        // 目录归属判定与文件读取都跟着进程工作目录漂 —— 换个方式启动(IDE / ry.sh / 容器)
        // 就指向了别的地方,历史工具结果整批读不出来,上下文静默退化成表内预览。
        this.contextRoot = Paths.get(contextPath).toAbsolutePath().normalize();
        try
        {
            Files.createDirectories(contextRoot);
        }
        catch (IOException e)
        {
            log.error("无法创建上下文根目录: {}", contextRoot, e);
        }
        log.info("ContextFileStore 初始化，根目录={}", contextRoot);
    }

    /**
     * 会话目录。
     *
     * <p><b>纵深防御</b>:sessionId 源头是客户端生成的,这里又直接拿它当目录名。
     * 现有调用方都在上游过了 {@link SessionIds} 校验,但那是「靠调用方自觉」——
     * 新增一个忘了校验的调用方,这里就是路径穿越点。所以在真正拼路径的地方再挡一道,
     * 并在 normalize 后复核没有逃出 contextRoot。
     */
    public Path sessionDir(String sessionId)
    {
        if (!SessionIds.isWellFormed(sessionId))
        {
            throw new SecurityException("非法的会话ID: " + sessionId);
        }
        Path dir = contextRoot.resolve(SessionIds.normalize(sessionId)).normalize();
        if (!dir.startsWith(contextRoot))
        {
            throw new SecurityException("会话上下文目录越界: " + sessionId);
        }
        return dir;
    }

    /* ==================== 大字段外置(§4.9) ==================== */

    /**
     * 把超大的工具返回原文写到文件,表内只留预览。
     *
     * @param sessionId 会话ID
     * @param messageId 消息ID(为空时用 UUID)
     * @param content   工具返回原文
     * @return 相对上下文根目录的路径,写入 ai_chat_message.tool_result_path
     */
    public String saveToolResult(String sessionId, Long messageId, String content)
    {
        return saveOverflow(sessionId, "tools", messageId, content, "工具结果");
    }

    /**
     * 把超长的思考(推理链)正文写到文件,表内只留预览。
     * <p>与 {@link #saveToolResult} 同机制,只是换个子目录 —— 思考体量常大于正文,
     * 混进 {@code tools/} 会让审计目录难以辨认。路径同样写进
     * {@code ai_chat_message.tool_result_path}(该列即「本行大字段的外置引用」)。
     *
     * @return 相对上下文根目录的路径
     */
    public String saveThinking(String sessionId, Long messageId, String content)
    {
        return saveOverflow(sessionId, "thinking", messageId, content, "思考");
    }

    /**
     * 把超长的 run_step.output_data 写到文件,表内只留预览。
     * 断线恢复不读回此文件,只作审计留痕。
     */
    public String saveRunStepOutput(String sessionId, String stepId, String content)
    {
        Long key = null;
        if (stepId != null)
        {
            key = (long) stepId.hashCode() & 0xffffffffL;
        }
        return saveOverflow(sessionId, "run-steps", key, content, "步骤输出");
    }

    /**
     * 读取外置大字段文件全文(工具结果 / 思考溢出落盘的文件)。
     *
     * <p><b>这是读外置大字段的唯一入口</b>。跨轮重建上下文({@code DbChatMemory})与前端
     * 「查看完整结果」读的是同一批文件,分成两套读法必然一边有护栏一边没有 ——
     * 越界校验、存在性判断、失败兜底都收在这里一处。
     *
     * @param path ai_chat_message.tool_result_path
     * @return 文件全文;path 为空 / 越界 / 文件不存在时返回 null,调用方自行兜底
     */
    public String loadExternal(String path)
    {
        if (path == null || path.isBlank())
        {
            return null;
        }
        try
        {
            Path file = resolveExternal(path);
            // 只允许读上下文根目录内的文件,防 path 被篡改成任意文件
            if (!file.startsWith(contextRoot))
            {
                log.warn("外置大字段路径越界,拒绝读取: {}", path);
                return null;
            }
            if (!Files.isRegularFile(file) || !Files.isReadable(file))
            {
                log.warn("外置大字段文件不存在或不可读: {}", path);
                return null;
            }
            return Files.readString(file, StandardCharsets.UTF_8);
        }
        catch (Exception e)
        {
            log.warn("读取外置大字段失败 path={}: {}", path, e.getMessage());
            return null;
        }
    }

    /**
     * 把 {@code tool_result_path} 解析成真实文件位置,兼容三种历史写法。
     *
     * <p>现在写入的是<b>相对上下文根目录</b>的路径({@code {sessionId}/tools/xxx.txt}):
     * 只有这样,换挂载点、换容器路径、换启动目录之后老数据才还找得到 ——
     * 数据库里存的应该是「在这个存储里的位置」,不是「某台机器上的某个绝对路径」。
     *
     * <p>兼容:①相对根目录(新写法);②进程工作目录相对路径(context-path 曾是 ./ 开头时的旧写法);
     * ③绝对路径(更早的写法)。②③都要绝对化后再交给调用方做越界校验。
     */
    private Path resolveExternal(String path)
    {
        Path raw = Paths.get(path);
        if (raw.isAbsolute())
        {
            return raw.normalize();
        }
        Path underRoot = contextRoot.resolve(raw).normalize();
        if (Files.isRegularFile(underRoot))
        {
            return underRoot;
        }
        // 落到这儿说明不是新写法,按旧的「相对进程工作目录」再试一次
        return raw.toAbsolutePath().normalize();
    }

    /**
     * 大字段外置的公共实现:落 {@code {contextPath}/{sessionId}/{subDir}/{id}.txt},
     * 但<b>返回相对根目录的那一段</b>({@code {sessionId}/{subDir}/{id}.txt})。
     *
     * <p>存相对路径是为了让引用跟着存储走而不是跟着机器走,详见 {@link #resolveExternal}。
     *
     * @param label 出错时的日志措辞
     */
    private String saveOverflow(String sessionId, String subDir, Long messageId,
                                String content, String label)
    {
        Path dir = sessionDir(sessionId).resolve(subDir);
        String name = (messageId != null ? messageId.toString()
                : UUID.randomUUID().toString().replace("-", "")) + ".txt";
        Path file = dir.resolve(name);
        Object lock = lockFor(file);
        synchronized (lock)
        {
            try
            {
                Files.createDirectories(dir);
                Files.writeString(file, content == null ? "" : content, StandardCharsets.UTF_8);
                return contextRoot.relativize(file.normalize()).toString();
            }
            catch (IOException e)
            {
                log.error("保存{}失败: {}", label, file, e);
                throw new ServiceException("保存" + label + "失败: " + file);
            }
        }
    }

    /* ==================== 删除 ==================== */

    /**
     * 删除单条外置大字段。失败只打日志,不能让回滚事务失败。
     */
    public void deleteExternal(String path)
    {
        if (path == null || path.isBlank())
        {
            return;
        }
        try
        {
            Path file = resolveExternal(path);
            if (!file.startsWith(contextRoot))
            {
                log.warn("外置大字段路径越界,拒绝删除: {}", path);
                return;
            }
            Files.deleteIfExists(file);
        }
        catch (Exception e)
        {
            log.warn("删除外置大字段失败 path={}: {}", path, e.getMessage());
        }
    }

    /**
     * 删除某会话下的全部外置文件(会话删除 / 清空记忆时调用)。
     */
    public void deleteSession(String sessionId)
    {
        Path dir = sessionDir(sessionId);
        if (!Files.exists(dir))
        {
            return;
        }
        try
        {
            Files.walk(dir)
                .sorted((a, b) -> b.compareTo(a)) // 先删文件再删目录
                .forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                });
            log.info("删除会话上下文目录: {}", dir);
        }
        catch (IOException e)
        {
            log.error("删除会话上下文目录失败: {}", dir, e);
        }
    }
}
