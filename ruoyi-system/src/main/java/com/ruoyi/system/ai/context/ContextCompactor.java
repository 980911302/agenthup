package com.ruoyi.system.ai.context;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.system.ai.memory.ChatMessageRecorder;
import com.ruoyi.system.ai.memory.ContextBudget;
import com.ruoyi.system.ai.memory.TokenEstimator;
import com.ruoyi.system.ai.memory.longterm.IdleSessionExtractScheduler;
import com.ruoyi.system.ai.memory.longterm.MemoryExtractor;
import com.ruoyi.system.domain.AiChatMessage;
import com.ruoyi.system.domain.AiChatSession;
import com.ruoyi.system.domain.AiLlmCall;
import com.ruoyi.system.mapper.AiChatSessionMapper;
import com.ruoyi.system.mapper.AiLlmCallMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 历史压缩:token 超阈值时把最旧的一段对话交给 LLM 生成「前情提要」,写成 SUMMARY 行。
 *
 * <p>对标 OpenCode 的 {@code isOverflow() -> summarize}。与 {@link ContextCleaner} 是两层不同的兜底:
 * <ul>
 *   <li>{@code ContextCleaner}(轮内、每次工具结果后)只削工具往返,不动对话正文,不花钱</li>
 *   <li>本类(跨轮、每轮开始前)削对话正文本身,要调一次 LLM</li>
 * </ul>
 * 先清后压 —— 工具往返通常是大头,清掉够用就不必花钱压缩。
 *
 * <p><b>失败必须安静降级</b>:压缩是优化不是必需。上游超时/限流/欠费都不能让这轮对话发不出去,
 * 一律吞异常返回 false,最坏情况由调用前的 token 溢出保护兜底。
 *
 * @author ruoyi
 */
@Component
public class ContextCompactor
{
    private static final Logger log = LoggerFactory.getLogger(ContextCompactor.class);

    /** 单次压缩调用顺带提炼的事实条数上限,防止模型一次塞几十条垃圾(对齐 spec §8.4)。 */
    private static final int MAX_FACTS_PER_RUN = 10;

    /**
     * 前情提要的保留清单 —— <b>两种模式共用的唯一事实源</b>。
     *
     * <p>此前 PROMPT 与 SUMMARY_ONLY_PROMPT 各抄了一份,已经漂移:纯摘要那份漏掉了
     * 「用户明说要记住的内容」。用户说「记住我用 pnpm」,不管记忆提炼开没开,这句都该
     * 活过压缩进摘要;关掉搭车开关就丢这条,是实打实的行为退化。抽成常量根除复制粘贴。
     */
    private static final String SUMMARY_RULES = """
            前情提要须保留:
            1. 用户的目标、需求与明确约束
            2. 已完成的关键步骤与结论
            3. 未完成的待办
            4. 文件路径、标识符、数值等具体信息
            5. 用户明说要记住的内容("记住…""以后都…""下次别…")""";

    /** 开关关闭时用的纯摘要 prompt:不要求 facts 段,省掉这部分输出 token(压缩是同步调用)。 */
    private static final String SUMMARY_ONLY_PROMPT = """
            你是对话压缩器。把下面的对话历史压缩成一段「前情提要」,供后续对话继续使用。

            """
            + SUMMARY_RULES
            + """


            不得编造历史中没有的信息。直接输出提要正文,不加标题或前缀。

            对话历史:
            """;

    /**
     * 搭车 prompt:同一次调用出「前情提要」+「事实清单」两段。
     *
     * <p><b>标签只在输出规范里出现一次。</b> 早先版本把 {@code <summary>} / {@code <facts>}
     * 同时当作指令分节标题与输出格式,于是提示词里第一个 {@code <summary>…</summary>} 装的
     * 是「必须保留:1. 用户的目标…」这段指令。而 {@code extractTag} 取的是<b>第一个</b>匹配 ——
     * 模型一旦顺手回显模板,落库的前情提要就成了那段指令文本,而它会替换掉整段历史,
     * 后续对话全部基于一份垃圾摘要继续。测试也抓不到:StubChatModel 返回的都是良构输出。
     */
    private static final String PROMPT = """
            你是对话压缩器。读下面的对话历史,输出两段:前情提要 + 可长期复用的事实清单。

            """
            + SUMMARY_RULES
            + """


            事实清单只收「换一个会话仍然有用」的信息:
            - type 取 fact|preference|event|goal|rule 之一
            - 只写关于用户的信息,不要收录你自己给出的答案或知识
            - 无可记录时写 []

            两段都不得编造历史中没有的信息。严格按下列结构输出,不加标题、前缀或代码块:

            <summary>前情提要正文</summary>
            <facts>[{"content":"…","type":"…"}]</facts>

            对话历史:
            """;

    /**
     * 估算剩余量时为即将生成的摘要预留 token。
     *
     * <p>摘要还没生成,长度未知。预留余量避免计算结果刚好卡在 target 上,
     * 摘要写入后又超线并在下一轮立刻重复触发压缩。
     */
    private static final int SUMMARY_TOKEN_RESERVE = 1000;

    @Autowired
    private ChatMessageRecorder recorder;

    @Autowired
    private TokenEstimator tokenEstimator;

    @Autowired
    private ContextBudget contextBudget;

    /**
     * 只为共用「永不清理」的工具名单({@code ai.chat.context.clean.exclude-tools})。
     * required=false:裸 new 出来的单测实例照常工作(退化成没有豁免工具)。
     */
    @Autowired(required = false)
    private ContextCleaner contextCleaner;

    /**
     * 压缩时原样保留的工具结果总长上限(字符)。超出后丢弃多出来的那些并告警 ——
     * 保留是为了不损坏行为,不能反过来把提要撑得比原文还大。
     */
    @Value("${ai.chat.context.compact.preserve-tool-max-chars:12000}")
    private int preserveToolMaxChars;

    @Autowired
    private AiLlmCallMapper llmCallMapper;

    /**
     * 跨会话长期记忆写入口(压缩搭车落库用,spec §8.1)。
     *
     * <p>{@code required=false}:记忆组件未装配(未启用/测试无该 bean)时压缩照常跑,
     * 只是不提炼 —— 记忆对压缩是旁路。null 时静默跳过提炼,绝不影响摘要与压缩成功。
     */
    @Autowired(required = false)
    private MemoryExtractor memoryExtractor;

    /**
     * 空闲扫描位点存储:搭车提炼后同步推进,避免兜底扫描重提炼同一段历史。
     * 单测可不装配(位点不推进只是多提炼一次,不影响正确性)。
     */
    @Autowired(required = false)
    private IdleSessionExtractScheduler.ProgressStore extractProgressStore;

    /** 会话主行:压缩搭车落库时需要从 sessionId 解析 userId(记忆租户 userId 永远强制)。 */
    @Autowired(required = false)
    private AiChatSessionMapper sessionMapper;

    /**
     * 压缩搭车提炼开关(spec §12 {@code ai.memory.extract.piggyback-compaction},默认开)。
     *
     * <p>关掉后退化为纯摘要:facts 段不再要求模型输出,省下这段输出 token ——
     * 压缩是同步调用,多出的 facts 输出会加到首 token 延迟上,压力大时可关。
     */
    @Value("${ai.memory.extract.piggyback-compaction:true}")
    private boolean piggybackCompactionEnabled;

    @Value("${ai.chat.context.compact.enabled:true}")
    private boolean enabled;

    /** 保留最近几轮不压缩(以 USER 消息计一轮)。 */
    @Value("${ai.chat.context.compact.keep-recent-turns:4}")
    private int keepRecentTurns;

    /**
     * 压缩调用超时秒数:压缩发生在每轮首 token 之前,挂死会把整轮拖死。
     * 压缩是优化不是必需,超时安静跳过;{@code <=0} 关闭(直接同步调用)。
     */
    @Value("${ai.chat.context.compact.timeout-seconds:120}")
    private long compactTimeoutSeconds;

    /** 压缩调用的执行池:与并行工具共用;CallerRuns 饱和时退化为内联执行,等同旧行为。 */
    @Autowired
    @Qualifier("parallelToolTaskExecutor")
    private java.util.concurrent.Executor compactionExecutor;

    /**
     * 需要时压缩一次。
     *
     * @return true=本次确实压缩了(调用方可据此重新加载历史)
     */
    public boolean compactIfNeeded(String conversationId, String sessionId, Long agentId,
                                   ChatModel chatModel, Integer contextWindow,
                                   Integer maxOutputTokens, Long modelId, String modelName)
    {
        if (!enabled || conversationId == null || chatModel == null)
        {
            return false;
        }
        try
        {
            AiChatMessage last = recorder.latestSummary(conversationId);
            long fromId = last != null ? last.getMessageId() : 0L;

            long used = recorder.sumTokensSinceSummary(conversationId, fromId);
            int threshold = contextBudget.threshold(contextWindow, maxOutputTokens);
            if (used <= threshold)
            {
                return false;
            }

            // 压缩边界必须观察完整的未压缩历史；按条数截断会只总结最旧 200 条，
            // 新消息永远落在压缩器视野之外，导致上下文越聊越失真。
            List<AiChatMessage> rows = recorder.selectVisible(conversationId, fromId);
            int target = contextBudget.target(contextWindow, maxOutputTokens);
            long boundaryId = findBoundary(rows, target);
            if (boundaryId <= 0)
            {
                log.debug("可压缩范围为空,跳过压缩 conv={}", conversationId);
                return false;
            }

            Set<String> preserved = preservedToolNames();
            String history = renderHistory(rows, boundaryId, preserved);
            if (history.isBlank())
            {
                return false;
            }

            List<Message> prompt = new ArrayList<>();
            // 开关关闭时不要求 facts 段,省这段输出 token(见字段注释);开启则走搭车 prompt
            String promptText = (piggybackCompactionEnabled ? PROMPT : SUMMARY_ONLY_PROMPT) + history;
            prompt.add(new UserMessage(promptText));
            long startMs = System.currentTimeMillis();
            ChatResponse response = callWithTimeout(chatModel, new Prompt(prompt));
            long durationMs = System.currentTimeMillis() - startMs;
            String raw = response == null || response.getResult() == null
                    ? null : response.getResult().getOutput().getText();
            String summary = parseSummary(raw);
            if (summary == null || summary.isBlank())
            {
                log.warn("压缩返回空摘要,跳过 conv={}", conversationId);
                return false;
            }

            // 技能规则原样接在提要之后:压缩不能让「已加载的技能」退化成一句转述,
            // 否则模型仍以为自己加载过技能却拿不到规则(ContextCleaner 为此把 loadSkill 列为永不清理,
            // 压缩层必须认同一条线)。
            summary = summary + renderPreservedToolResults(rows, boundaryId, preserved);
            recorder.recordSummary(conversationId, sessionId, agentId, summary, boundaryId);
            recordLlmCall(conversationId, sessionId, agentId, modelId, modelName,
                    response, promptText, summary, durationMs);
            // 压缩成功后再把本次顺带提炼出的事实落库(搭车:失败绝不影响已成功的压缩)
            persistPiggybackFacts(sessionId, agentId, boundaryId, raw);
            log.info("上下文压缩完成 conv={} used={} threshold={} 覆盖至 messageId={} 摘要≈{}token",
                    conversationId, used, threshold, boundaryId, tokenEstimator.estimate(summary));
            return true;
        }
        catch (Exception e)
        {
            // 压缩是优化不是必需:上游超时/限流/唯一键冲突都不能让这轮对话发不出去
            log.warn("上下文压缩失败,本轮跳过 conv={}: {}", conversationId, e.getMessage());
            return false;
        }
    }

    /**
     * 压缩调用超时包裹:挂死时返回 null(走「空摘要跳过」分支),不拖死整轮。
     * <p>超时后被放弃的调用在执行池上自然结束,浪费有界。
     */
    private ChatResponse callWithTimeout(ChatModel chatModel, Prompt prompt)
    {
        if (compactTimeoutSeconds <= 0 || compactionExecutor == null)
        {
            return chatModel.call(prompt);
        }
        java.util.concurrent.CompletableFuture<ChatResponse> future =
                java.util.concurrent.CompletableFuture.supplyAsync(() -> chatModel.call(prompt),
                        compactionExecutor);
        try
        {
            return future.get(compactTimeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
        }
        catch (java.util.concurrent.TimeoutException e)
        {
            log.warn("上下文压缩调用超时(>{}s),本轮跳过", compactTimeoutSeconds);
            return null;
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            log.warn("上下文压缩调用被中断,本轮跳过");
            return null;
        }
        catch (java.util.concurrent.ExecutionException e)
        {
            // 还原内联调用的穿透语义:交给外层 catch 按「压缩失败跳过」统一处理
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw cause instanceof RuntimeException runtimeException ? runtimeException
                    : new IllegalStateException(cause);
        }
    }

    /**
     * 在至少保留一轮的前提下,保留尽可能多的轮次,同时让压缩后的估算量降到 target 以下。
     *
     * <p>从 {@code keepRecentTurns} 开始逐步减少保留轮数;最近几轮本身很大时,
     * 固定保留会使压缩后仍超阈值,进而每轮重复压缩却永远无法收敛。若一轮也超过
     * target,仍压到只剩最后一轮,把最坏情况交给 {@code ContextOverflowGuard}。
     *
     * @return 0 表示没有可压缩范围(只有一轮或更少)
     */
    private long findBoundary(List<AiChatMessage> rows, int target)
    {
        if (rows == null || rows.isEmpty())
        {
            return 0L;
        }
        List<Integer> userIdx = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++)
        {
            if ("USER".equals(rows.get(i).getMessageType()))
            {
                userIdx.add(i);
            }
        }
        if (userIdx.size() <= 1)
        {
            return 0L;
        }
        int maxKeep = Math.min(Math.max(keepRecentTurns, 1), userIdx.size() - 1);
        long boundary = 0L;
        for (int keepN = maxKeep; keepN >= 1; keepN--)
        {
            int cutIdx = userIdx.get(userIdx.size() - keepN);
            if (cutIdx <= 0)
            {
                continue;
            }
            boundary = rows.get(cutIdx - 1).getMessageId();
            if (remainingTokens(rows, cutIdx) + SUMMARY_TOKEN_RESERVE <= target)
            {
                return boundary;
            }
        }
        return boundary;
    }

    /** 从 fromIdx 到末尾的 token 之和,即压缩后要保留的历史量。 */
    private static long remainingTokens(List<AiChatMessage> rows, int fromIdx)
    {
        long sum = 0L;
        for (int i = fromIdx; i < rows.size(); i++)
        {
            Integer tokens = rows.get(i).getTokens();
            sum += tokens != null ? tokens : 0;
        }
        return sum;
    }

    /** 把边界之前的消息渲染成纯文本喂给压缩模型。 */
    private String renderHistory(List<AiChatMessage> rows, long boundaryId, Set<String> preserved)
    {
        StringBuilder sb = new StringBuilder();
        for (AiChatMessage r : rows)
        {
            if (r.getMessageId() > boundaryId)
            {
                break;
            }
            String type = r.getMessageType();
            if ("TOOL".equals(type))
            {
                if (preserved.contains(r.getToolName()))
                {
                    // 豁免工具(默认 loadSkill)的结果由 renderPreservedToolResults 原样接到提要后面,
                    // 这里只留个标记:既不必花 token 把技能正文喂给摘要模型,也告诉它别去复述。
                    sb.append("[工具] ").append(r.getToolName())
                      .append(" -> (结果原文已另行保留,不要在提要中复述)\n");
                    continue;
                }
                // 其余工具往返在提要里只留「调过什么、结果大意」,原文没有保留价值
                sb.append("[工具] ").append(r.getToolName()).append(" -> ")
                  .append(abbreviate(r.getToolResult(), 200)).append('\n');
            }
            else if ("USER".equals(type))
            {
                sb.append("[用户] ").append(abbreviate(r.getContent(), 2000));
                appendAttachmentHints(sb, r.getAttachments());
                sb.append('\n');
            }
            else if ("ASSISTANT".equals(type))
            {
                String text = abbreviate(r.getContent(), 2000);
                if (!text.isBlank())
                {
                    sb.append("[助手] ").append(text).append('\n');
                }
            }
        }
        return sb.toString();
    }

    /**
     * 边界之前「永不清理」工具的结果原文,原样接在提要之后。
     *
     * <p><b>为什么不交给摘要模型</b>:{@code loadSkill} 返回的是技能的操作规则,不是可以复述大意的
     * 数据。让 LLM 转述 2000 字的规则必然失真,而失真的规则比没有规则更糟 —— 模型仍以为自己
     * 加载过技能。这里绕开模型直接拼接,压缩前后逐字节一致。
     *
     * <p>顺带按结果原文去重:同一个技能被反复 {@code loadSkill} 时只保留一份。
     *
     * @return 保留段;没有豁免工具结果时返回空串
     */
    private String renderPreservedToolResults(List<AiChatMessage> rows, long boundaryId,
                                              Set<String> preserved)
    {
        if (preserved.isEmpty())
        {
            return "";
        }
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        int used = 0;
        for (AiChatMessage r : rows)
        {
            if (r.getMessageId() > boundaryId)
            {
                break;
            }
            if (!"TOOL".equals(r.getMessageType()) || !preserved.contains(r.getToolName()))
            {
                continue;
            }
            String result = r.getToolResult();
            if (result == null || result.isBlank() || seen.contains(result))
            {
                continue;
            }
            if (used + result.length() > preserveToolMaxChars)
            {
                log.warn("压缩保留段超出 {} 字符上限,丢弃剩余豁免工具结果 tool={}",
                        preserveToolMaxChars, r.getToolName());
                break;
            }
            used += result.length();
            seen.add(result);
        }
        if (seen.isEmpty())
        {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n\n## 已加载的技能规则(原文保留,继续按其执行)");
        for (String result : seen)
        {
            sb.append("\n\n").append(result);
        }
        return sb.toString();
    }

    /** 与清理层共用的「永不清理」工具名单;未注入 cleaner 时退化成空集。 */
    private Set<String> preservedToolNames()
    {
        return contextCleaner == null ? java.util.Set.of() : contextCleaner.excludedToolNames();
    }

    private static String abbreviate(String s, int max)
    {
        if (s == null)
        {
            return "";
        }
        String one = s.replaceAll("\\s+", " ").trim();
        return one.length() <= max ? one : one.substring(0, max) + "…";
    }

    /**
     * 附件转文本占位,例如 {@code [附件 image/jpeg: x.jpg]}。
     *
     * <p>图片本体进不了文本摘要,但附件存在及其基本类型必须留下;否则后续用户追问
     * 早期图片时,模型连"看不到该图片"都无法准确说明。附件 JSON 脏数据不影响压缩。
     */
    private static void appendAttachmentHints(StringBuilder sb, String attachmentsJson)
    {
        if (attachmentsJson == null || attachmentsJson.isBlank())
        {
            return;
        }
        try
        {
            JSONArray arr = JSON.parseArray(attachmentsJson);
            for (int i = 0; i < arr.size(); i++)
            {
                JSONObject attachment = arr.getJSONObject(i);
                String mime = attachment.getString("mime");
                String name = attachment.getString("name");
                if (name == null || name.isBlank())
                {
                    name = attachment.getString("path");
                }
                sb.append(" [附件 ").append(mime != null ? mime : "unknown")
                        .append(": ").append(name != null ? name : "未命名").append(']');
            }
        }
        catch (Exception e)
        {
            // 附件痕迹是锦上添花,一条脏 JSON 不该让整次压缩失败。
        }
    }

    /**
     * 从压缩模型输出里提取「前情提要正文」(spec §8.1 摘要优先解析)。
     *
     * <p>提取 {@code <summary>...</summary>} 段,存在则取段内正文;段缺失时回退到整段输出
     * (剥掉 facts 段)—— 既兼容旧的纯文本输出,也保住「搭车失败不能让摘要一起丢」的红线。
     * 摘要为空则整体判为「压缩无产出」,按原逻辑跳过压缩。
     */
    private static String parseSummary(String raw)
    {
        if (raw == null || raw.isBlank())
        {
            return null;
        }
        String inner = extractTag(raw, "summary");
        if (inner != null)
        {
            String trimmed = inner.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }
        // 无 summary 标签(旧格式或开关关闭时的纯文本):回退整段输出,剥掉可能残留的 facts 段
        String plain = raw.replaceAll("(?s)<facts>.*?</facts>", "").trim();
        return plain.isEmpty() ? null : plain;
    }

    /**
     * 从压缩模型输出里提取事实清单(spec §8.1)。
     *
     * <p>只取 {@code <facts>...</facts>} 段内文本做 JSON 解析,与摘要解析完全解耦:
     * {@code <facts>} 缺失或 JSON 畸形一律返回空 List(降级为「这次没提炼出事实」),
     * 任何解析异常都不许抛到外层 —— 压缩是关键路径,搭车的不能掀翻车。
     */
    static List<ExtractedFact> parseFacts(String raw)
    {
        if (raw == null || raw.isBlank())
        {
            return List.of();
        }
        String inner = extractTag(raw, "facts");
        if (inner == null || inner.isBlank())
        {
            return List.of();
        }
        try
        {
            JSONArray arr = JSON.parseArray(inner);
            if (arr == null)
            {
                return List.of();
            }
            List<ExtractedFact> out = new ArrayList<>();
            for (int i = 0; i < arr.size() && out.size() < MAX_FACTS_PER_RUN; i++)
            {
                JSONObject o = arr.getJSONObject(i);
                if (o == null)
                {
                    continue;
                }
                String content = o.getString("content");
                if (content == null || content.isBlank())
                {
                    continue;
                }
                String type = o.getString("type");
                // 类型白名单对齐 MemoryServiceImpl.normalizeType;未知收敛到 fact
                if (type == null || !isKnownType(type))
                {
                    type = "fact";
                }
                out.add(new ExtractedFact(content.trim(), type));
            }
            return out;
        }
        catch (Exception e)
        {
            // JSON 畸形:本次不提炼,摘要照常落库(绝不因此丢摘要)
            log.debug("压缩搭车 facts 解析失败,本次不提炼: {}", e.getMessage());
            return List.of();
        }
    }

    private static boolean isKnownType(String type)
    {
        return switch (type)
        {
            case "fact", "preference", "event", "goal", "rule" -> true;
            default -> false;
        };
    }

    /**
     * 提取 {@code <tag>...</tag>} 段内的原文。
     *
     * <p>标签不区分大小写(模型可能输出 {@code <SUMMARY>});只取第一个开始标签到对应的
     * 第一个结束标签,不解析嵌套(模型输出不该嵌套,嵌套就当畸形交给上层降级)。
     * 找不到开始/结束标签返回 null。
     */
    private static String extractTag(String raw, String tag)
    {
        String open = "<" + tag.toLowerCase(Locale.ROOT) + ">";
        String close = "</" + tag.toLowerCase(Locale.ROOT) + ">";
        String text = raw.toLowerCase(Locale.ROOT);
        int start = text.indexOf(open);
        if (start < 0)
        {
            return null;
        }
        int contentStart = start + open.length();
        int end = text.indexOf(close, contentStart);
        if (end < 0)
        {
            return null;
        }
        return raw.substring(contentStart, end);
    }

    /**
     * 压缩顺带提炼出的事实落库(spec §8.1「压缩搭车」写侧)。
     *
     * <p><b>层级判定保守方向(spec §8.3)</b>:一律落 agent 层
     * ({@code MemoryTenant.ofAgent})。压缩场景提炼的是「这段会话内上下文」,多数只在
     * 本 agent 语境下成立;是否升用户层交给提炼器(MemoryExtractor,批次3)的层级判定。
     * 错放 agent 层的代价只是少共享一点,错放用户层会污染该用户所有 agent —— 保守是对的。
     *
     * <p><b>落库统一走 {@link MemoryExtractor#persistFacts}</b>,不自己调 {@code memoryService.add}:
     * 那样会漏掉向量(读侧是纯向量检索,没有向量的台账行永远查不到)、{@code content_hash}
     * (后续提炼认不出重复)以及去重/supersede 判定。两套并行的落库逻辑迟早分叉。
     *
     * <p><b>提炼位点</b>:复用压缩的 {@code boundaryId}(sourceMessageId),并且<b>同步推进
     * 空闲扫描的位点</b> —— 否则 30 分钟后兜底扫描看到位点还是旧的,会把同一段历史再提炼
     * 一遍。userId 从会话主行解析(租户 userId 永远强制),解析不到时安静跳过。
     *
     * <p><b>安静降级</b>:任何失败(无 memoryService、无 session、userId 缺失、落库异常)
     * 都只记日志,绝不影响已成功的摘要与压缩 —— 压缩是关键路径,搭车不能掀翻车。
     */
    private void persistPiggybackFacts(String sessionId, Long agentId, long boundaryId, String raw)
    {
        if (!piggybackCompactionEnabled || memoryExtractor == null || sessionMapper == null
                || sessionId == null || agentId == null || raw == null)
        {
            return;
        }
        List<ExtractedFact> facts = parseFacts(raw);
        if (facts.isEmpty())
        {
            return;
        }
        try
        {
            AiChatSession session = sessionMapper.selectAiChatSessionById(sessionId);
            if (session == null || session.getUserId() == null)
            {
                log.debug("压缩搭车:会话无属主,跳过提炼 session={}", sessionId);
                return;
            }
            Long userId = session.getUserId();
            List<MemoryExtractor.Fact> payload = new ArrayList<>();
            for (ExtractedFact f : facts)
            {
                // scope 传 null:压缩搭车不判层级,一律落 agent 层(spec §8.3 保守方向)
                payload.add(new MemoryExtractor.Fact(f.content(), f.type(), null));
            }
            int persisted = memoryExtractor.persistFacts(userId, agentId, sessionId,
                    boundaryId, payload);
            log.debug("压缩搭车提炼落库 session={} 事实{}条 写入{}条", sessionId, facts.size(), persisted);

            // 位点同步推进:不推的话兜底扫描会重提炼同一段历史,而搭车与扫描的去重
            // 依赖 hash/向量,重复代价虽可控但纯属白烧一次 LLM
            advanceExtractProgress(sessionId, agentId, userId, boundaryId);
        }
        catch (Exception e)
        {
            log.warn("压缩搭车提炼落库失败,本次跳过: {}", e.getMessage());
        }
    }

    /** 推进空闲扫描位点;失败只记日志 —— 最坏是兜底扫描重提炼一遍,由去重兜住。 */
    private void advanceExtractProgress(String sessionId, Long agentId, Long userId, long boundaryId)
    {
        if (extractProgressStore == null || boundaryId <= 0)
        {
            return;
        }
        try
        {
            extractProgressStore.advance(sessionId, agentId, userId, boundaryId);
        }
        catch (Exception e)
        {
            log.debug("压缩搭车推进提炼位点失败 session={}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * 提炼出的单条事实(类型白名单在解析时已收敛)。
     *
     * @param content 事实正文
     * @param type    fact|preference|event|goal|rule
     */
    record ExtractedFact(String content, String type) {}

    /**
     * 压缩这次 LLM 调用也要进 {@code ai_llm_call}。
     *
     * <p>上游给了 usage 就用真实值({@code usage_source='0'}),没给则按
     * {@code TokenEstimator} 估算({@code '1'})。记账失败不能反过来把已经成功的
     * 压缩判成失败:摘要已经落库,这里只吞异常记日志。
     */
    private void recordLlmCall(String conversationId, String sessionId, Long agentId,
                               Long modelId, String modelName, ChatResponse response,
                               String promptText, String summary, long durationMs)
    {
        try
        {
            Usage usage = response != null && response.getMetadata() != null
                    ? response.getMetadata().getUsage() : null;
            int prompt;
            int completion;
            String source;
            if (usage != null && usage.getTotalTokens() != null && usage.getTotalTokens() > 0)
            {
                prompt = usage.getPromptTokens() != null ? usage.getPromptTokens() : 0;
                completion = usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0;
                source = "0";
            }
            else
            {
                prompt = tokenEstimator.estimate(promptText);
                completion = tokenEstimator.estimate(summary);
                source = "1";
            }

            AiLlmCall call = new AiLlmCall();
            call.setSessionId(sessionId);
            call.setAgentId(agentId);
            call.setConversationId(conversationId);
            // message_id 留空:这笔调用的产物是 SUMMARY 行,不是某条对话消息的归因。
            call.setModelId(modelId);
            call.setModelName(modelName);
            call.setCallSeq(0);
            call.setDepth(0);
            call.setFinishReason("compact");
            call.setPromptTokens(prompt);
            call.setCompletionTokens(completion);
            call.setTotalTokens(prompt + completion);
            call.setCacheHitTokens(0);
            call.setCacheMissTokens(0);
            call.setUsageSource(source);
            call.setDurationMs(durationMs);
            call.setCreateTime(new Date());
            llmCallMapper.insertLlmCall(call);
        }
        catch (Exception e)
        {
            log.warn("压缩调用记账失败 conv={}: {}", conversationId, e.getMessage());
        }
    }
}
