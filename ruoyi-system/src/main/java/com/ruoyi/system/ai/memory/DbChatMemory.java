package com.ruoyi.system.ai.memory;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.system.ai.agent.ModelInputModalities;
import com.ruoyi.system.ai.ContextFileStore;
import com.ruoyi.system.ai.context.ToolPlaceholders;
import com.ruoyi.system.domain.AiChatMessage;
import com.ruoyi.system.tool.AiToolProperties;
import com.ruoyi.system.ai.context.ToolResultText;
import com.ruoyi.system.tool.WorkspaceSandbox;
import com.ruoyi.system.tool.WorkspaceScopeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 直接实现 {@link ChatMemory},读写 {@code ai_chat_message}(§4.5)。
 *
 * <p><b>关键选择:实现 {@code ChatMemory} 接口而非 {@code ChatMemoryRepository}</b>。
 * {@code MessageWindowChatMemory.add()} 的实现是 {@code findByConversationId -> 合并截断 -> saveAll(全量覆盖)},
 * {@code saveAll} 是<b>覆盖语义</b>(参考实现就是 {@code Map.put})。走 Repository 就得接受
 * 「超出窗口的历史被物理删掉」,审计和前端时间线全没了。直接实现 {@code ChatMemory},
 * {@code add} 就是真追加,一张表够用。
 *
 * <p><b>窗口控制的位置变了</b>:不再在 {@code get()} 里用 {@code limit 20} 做条数截断(那是硬丢弃),
 * 而是把控制权交给压缩(§4.7):{@code get()} 取压缩点之后的<b>全部</b>,压缩保证这部分总量不超模型预算。
 * 不能再叠加按消息条数的 limit：工具密集型会话会很快超过条数，但 token 仍远低于预算；
 * 静默截断会让模型永远只看到最旧的一段历史。
 *
 * <p><b>本段不触发压缩</b>(第二段 {@code ContextCompactor} 才做),只实现 add/get/clear。
 *
 * @author ruoyi
 */
@Component
public class DbChatMemory implements ChatMemory
{
    private static final Logger log = LoggerFactory.getLogger(DbChatMemory.class);

    @Autowired
    private ChatMessageRecorder recorder;

    @Autowired
    private TokenEstimator tokenEstimator;

    /** 外置大字段的唯一读入口(工具结果全文);与前端「查看完整结果」共用同一套解析与校验 */
    @Autowired
    private ContextFileStore contextFileStore;

    /** 还原图片附件时用来定位会话工作区 */
    @Autowired
    private AiToolProperties aiToolProperties;

    @Autowired
    private WorkspaceScopeService workspaceScopeService;

    /** 历史附件还原前先问当前模型能不能看图；缺失时按不支持处理 */
    @Autowired(required = false)
    private ConversationVisionGate visionGate;

    /**
     * 与 {@code RecordingToolCallback.capForModel} 同一套阈值:跨轮重建必须还原成
     * 模型当时看到的那一条,少一个维度就会「本轮截了、下一轮又长回来」。
     * <p>占位符默认值直接引 {@link ToolResultText} 的常量,和 {@code ToolBudgetRegistry}
     * 读的是同一个配置键、同一个缺省值,不会两边各飘各的。
     */
    @Value("${ai.chat.tool.max-result-chars:" + ToolResultText.DEFAULT_MAX_CHARS + "}")
    private int maxResultChars;

    @Value("${ai.chat.tool.max-result-lines:" + ToolResultText.DEFAULT_MAX_LINES + "}")
    private int maxResultLines;

    @Override
    public void add(String conversationId, List<Message> messages)
    {
        if (conversationId == null || messages == null || messages.isEmpty())
        {
            return;
        }
        // conversationId 形如 sessionId:agentId;解析出 sessionId/agentId 用于落表
        String sessionId;
        Long agentId = null;
        int colon = conversationId.indexOf(':');
        if (colon > 0)
        {
            sessionId = conversationId.substring(0, colon);
            try
            {
                agentId = Long.valueOf(conversationId.substring(colon + 1));
            }
            catch (NumberFormatException e)
            {
                // 旧路径裸 sessionId 作 conversationId,agentId 留空
                sessionId = conversationId;
            }
        }
        else
        {
            sessionId = conversationId;
        }
        for (Message m : messages)
        {
            if (m instanceof ToolResponseMessage)
            {
                // 双保险:工具消息不该到这儿(internalToolExecution=true 时中间往返不会流到 advisor)
                continue;
            }
            String type = mapType(m);
            // add 进来的都是 USER/最终 ASSISTANT/SYSTEM,visible_to_llm='0'
            recorder.insert(conversationId, sessionId, agentId, type, m.getText(), "0",
                    tokenEstimator.estimate(m.getText()));
        }
        // 压缩触发留到第二段(ContextCompactor),本段只 add 不压缩
    }

    @Override
    public List<Message> get(String conversationId)
    {
        List<Message> result = new ArrayList<>();
        if (conversationId == null)
        {
            return result;
        }
        // 1. 取最新一条 SUMMARY 作为起点(压缩边界,§4.7)
        AiChatMessage summary = recorder.latestSummary(conversationId);
        long fromId = 0;
        if (summary != null)
        {
            // 前情提要必须是 UserMessage,不能用 SystemMessage:MessageChatMemoryAdvisor 会把
            // 第一条 SystemMessage 提到索引 0,把真正的 system prompt 挤到后面 ——
            // 每轮都可能变的摘要占住请求最前缀,整条历史的上游 KV-cache 全废。
            result.add(new UserMessage("【前情提要】" + summary.getContent()));
            fromId = summary.getMessageId();
        }
        // 2. 取压缩点之后的全部可见、非 SUMMARY 消息。容量只按 token 预算控制，
        //    禁止按条数截断，否则高频小工具调用会把真正的新消息挡在 limit 之后。
        List<AiChatMessage> rows = recorder.selectVisible(conversationId, fromId);
        for (int i = 0; i < rows.size(); i++)
        {
            AiChatMessage r = rows.get(i);
            // assistant(tool_calls):吞掉紧随其后的连续 TOOL 行,一起重建成协议要求的配对
            if ("ASSISTANT".equals(r.getMessageType()) && hasText(r.getToolCalls()))
            {
                List<AssistantMessage.ToolCall> calls = parseToolCalls(r);
                if (calls.isEmpty())
                {
                    // tool_calls 列脏了(JSON 解析不出来):退化成纯文本 assistant,
                    // 绝不能发一条空 tool_calls 的 assistant 出去
                    result.add(new AssistantMessage(r.getContent()));
                    continue;
                }
                int j = i + 1;
                List<AiChatMessage> toolRows = new ArrayList<>();
                while (j < rows.size() && "TOOL".equals(rows.get(j).getMessageType()))
                {
                    toolRows.add(rows.get(j));
                    j++;
                }
                result.add(AssistantMessage.builder()
                        .content(r.getContent() != null ? r.getContent() : "")
                        .toolCalls(calls)
                        .build());
                result.add(buildToolResponses(calls, toolRows));
                i = j - 1;
                continue;
            }
            // 孤儿 TOOL 行:前面没有对应的 assistant(运行中断、部分回滚都会造成)。
            // 单独发出去必然 400,只能丢弃 —— 库里原文还在,审计不受影响。
            if ("TOOL".equals(r.getMessageType()))
            {
                log.debug("跳过孤儿 TOOL 行 messageId={}", r.getMessageId());
                continue;
            }
            Message msg = toMessage(r);
            if (msg != null)
            {
                result.add(msg);
            }
        }
        return result;
    }

    private static boolean hasText(String s)
    {
        return s != null && !s.isBlank();
    }

    /**
     * 解析 tool_calls 列。被清理过的行把入参换成摘要 —— 与 {@code ContextCleaner} 出的文本一致,
     * 否则同一对工具往返在轮内与跨轮长得不一样,缓存前缀白维护。
     */
    private List<AssistantMessage.ToolCall> parseToolCalls(AiChatMessage r)
    {
        List<AssistantMessage.ToolCall> calls = new ArrayList<>();
        boolean pruned = "1".equals(r.getPruned());
        try
        {
            JSONArray arr = JSON.parseArray(r.getToolCalls());
            for (int k = 0; k < arr.size(); k++)
            {
                JSONObject o = arr.getJSONObject(k);
                String name = o.getString("name");
                String args = o.getString("arguments");
                calls.add(new AssistantMessage.ToolCall(
                        o.getString("id"),
                        o.getString("type") != null ? o.getString("type") : "function",
                        name,
                        pruned ? ToolPlaceholders.summarizeArgs(name, args) : args));
            }
        }
        catch (Exception e)
        {
            log.warn("tool_calls 解析失败 messageId={}: {}", r.getMessageId(), e.getMessage());
            return List.of();
        }
        return calls;
    }

    /**
     * 按 tool_call_id 把 TOOL 行配到各自的 tool_call 上。
     *
     * <p><b>必须每个 tool_call 都有响应</b>:少一条上游直接 400,整个会话从此发不出去。
     * 运行中断、回滚删过消息、老数据没有 tool_call_id,都会造成缺失,
     * 一律补一条合成响应 —— 宁可让模型看到「结果缺失」,也不能把会话卡死。
     */
    private ToolResponseMessage buildToolResponses(List<AssistantMessage.ToolCall> calls,
                                                   List<AiChatMessage> toolRows)
    {
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        Set<Integer> used = new HashSet<>();
        for (AssistantMessage.ToolCall tc : calls)
        {
            AiChatMessage hit = null;
            // 优先按 id 精确配
            for (int k = 0; k < toolRows.size(); k++)
            {
                if (!used.contains(k) && tc.id() != null
                        && tc.id().equals(toolRows.get(k).getToolCallId()))
                {
                    hit = toolRows.get(k);
                    used.add(k);
                    break;
                }
            }
            // 配不上就按顺序取一个没用过的(老数据没写 tool_call_id 时走这条)
            if (hit == null)
            {
                for (int k = 0; k < toolRows.size(); k++)
                {
                    if (!used.contains(k))
                    {
                        hit = toolRows.get(k);
                        used.add(k);
                        break;
                    }
                }
            }
            responses.add(new ToolResponseMessage.ToolResponse(
                    tc.id(), tc.name(), resolveToolData(tc, hit)));
        }
        return ToolResponseMessage.builder().responses(responses).build();
    }

    /** 一条 tool 响应的正文:占位 / 文件回读 / 表内预览 / 合成缺失说明。 */
    private String resolveToolData(AssistantMessage.ToolCall tc, AiChatMessage row)
    {
        if (row == null)
        {
            return ToolPlaceholders.buildPlaceholder(tc.name(), tc.arguments(),
                    "结果缺失(运行中断或已回滚)");
        }
        if ("1".equals(row.getPruned()))
        {
            return ToolPlaceholders.buildPlaceholder(row.getToolName(), row.getToolArgs(), null);
        }
        String data = row.getToolResult();
        // 大结果落了文件:表内只有 2KB 预览,回读文件才能还原模型当时看到的那一段。
        // 走 ContextFileStore.loadExternal 而不是自己 readString —— 前端「查看完整结果」
        // 读的是同一批文件,两套读法必然一边有越界校验一边没有,路径格式变了也只改得动一边。
        if (hasText(row.getToolResultPath()))
        {
            String full = contextFileStore.loadExternal(row.getToolResultPath());
            if (full != null)
            {
                data = full;
            }
            else
            {
                // loadExternal 内部已按原因告警;这里只记录降级后果:
                // 本轮该工具的上下文缩水成表内预览,而 tokens 列仍按全文估算,刻度条会偏高
                log.warn("工具结果文件不可读,上下文退化为表内预览 messageId={} path={}",
                        row.getMessageId(), row.getToolResultPath());
            }
        }
        // 统一按给模型的阈值截断,与当轮 capForModel 保持同一口径(同一个函数、同一组上限)
        return ToolResultText.cap(data != null ? data : "", maxResultLines, maxResultChars);
    }

    @Override
    public void clear(String conversationId)
    {
        if (conversationId != null)
        {
            recorder.deleteByConversationId(conversationId);
        }
    }

    private static String mapType(Message m)
    {
        switch (m.getMessageType())
        {
            case USER:
                return "USER";
            case ASSISTANT:
                return "ASSISTANT";
            case SYSTEM:
                return "SYSTEM";
            case TOOL:
                return "TOOL";
            default:
                return "ASSISTANT";
        }
    }

    private Message toMessage(AiChatMessage r)
    {
        String text = r.getContent();
        switch (r.getMessageType())
        {
            case "USER":
                return toUserMessage(r, text);
            case "ASSISTANT":
                return new AssistantMessage(text);
            case "SYSTEM":
                return new SystemMessage(text);
            case "SUMMARY":
                // selectVisible 已过滤 SUMMARY,理论上不会到这儿
                log.warn("SUMMARY 行意外进入 toMessage,conversationId={}", r.getConversationId());
                return null;
            default:
                return new UserMessage(text);
        }
    }

    /**
     * 还原 USER 消息,带上曾经上传的图片(§附件)。
     *
     * <p><b>为什么必须还原 media</b>:用户第一轮传了图并问"这是什么",第二轮追问"那右下角呢",
     * 如果记忆里只剩文本,模型这轮就完全看不到图了 —— 表现为它开始胡编。
     * 图片本体一直在工作区,这里按 attachments 里记的路径重新挂回去即可。
     *
     * <p>只还原模型收得下的媒体(图片/PDF/音频,逐份按 {@link ModelInputModalities} 判定);
     * 其余附件模型本来就是靠 read 按需读的,消息正文里已经有路径清单。
     * 文件被清空工作区删掉时静默跳过,不影响这轮对话。
     */
    Message toUserMessage(AiChatMessage r, String text)
    {
        String json = r.getAttachments();
        if (json == null || json.isEmpty() || r.getSessionId() == null)
        {
            return new UserMessage(text);
        }
        ModelInputModalities caps = visionGate == null
                ? null : visionGate.modalities(r.getConversationId());
        if (caps == null)
        {
            // 门控查不出结果时按纯文本处理。不能让 null 漏进下面的循环:那里的 NPE 会被
            // 兜底 catch 吞掉,表现为"媒体静默消失",比直接降级更难查。
            caps = ModelInputModalities.of(null);
        }
        List<Media> medias = new ArrayList<>();
        // 送不回去的历史媒体按模态计数,末尾补一句说明,避免模型以为自己还看得见
        Map<String, Integer> omitted = new LinkedHashMap<>();
        try
        {
            Path root = workspaceScopeService.resolveRoot(aiToolProperties, r.getSessionId(), false);
            for (Object item : JSON.parseArray(json))
            {
                JSONObject o = (JSONObject) item;
                String mime = o.getString("mime");
                String path = o.getString("path");
                if (mime == null || path == null)
                {
                    continue;
                }
                MimeType mimeType;
                try
                {
                    mimeType = MimeTypeUtils.parseMimeType(mime);
                }
                catch (Exception ignored)
                {
                    continue;
                }
                String modality = ModelInputModalities.modalityOf(mimeType);
                if (modality == null)
                {
                    // 非媒体附件(txt/csv 等):正文里本就有路径清单,模型按需 read
                    continue;
                }
                if (!caps.accepts(mimeType))
                {
                    omitted.merge(modality, 1, Integer::sum);
                    continue;
                }
                Path file = WorkspaceSandbox.resolveSafe(root, path);
                if (!Files.isRegularFile(file))
                {
                    log.debug("附件已不存在,跳过还原: {}", path);
                    continue;
                }
                medias.add(Media.builder()
                        .mimeType(mimeType)
                        .data(new FileSystemResource(file))
                        .name(historyMediaName(o, file))
                        .build());
            }
        }
        catch (Exception e)
        {
            // 还原失败不能让整轮对话挂掉,退化成纯文本
            log.warn("还原消息附件失败 messageId={}: {}", r.getMessageId(), e.getMessage());
        }
        String finalText = omitted.isEmpty() ? text : omitHistoryMedia(text, omitted);
        if (medias.isEmpty())
        {
            return new UserMessage(finalText);
        }
        return UserMessage.builder().text(finalText).media(medias).build();
    }

    /** PDF 走 input_file 时 name 就是模型看到的 filename,优先用落库的原始名。 */
    private static String historyMediaName(JSONObject o, Path file)
    {
        String name = o.getString("name");
        return name == null || name.isBlank() ? file.getFileName().toString() : name;
    }

    static String omitHistoryMedia(String text, Map<String, Integer> omitted)
    {
        StringBuilder sb = new StringBuilder(text == null ? "" : text);
        sb.append("\n[历史媒体已省略：");
        boolean first = true;
        for (Map.Entry<String, Integer> e : omitted.entrySet())
        {
            if (!first)
            {
                sb.append("、");
            }
            first = false;
            sb.append(e.getValue()).append(" 份").append(label(e.getKey()));
        }
        sb.append("，当前模型收不到这些内容]");
        return sb.toString();
    }

    private static String label(String modality)
    {
        if (modality == null)
        {
            return "媒体";
        }
        switch (modality)
        {
            case ModelInputModalities.IMAGE: return "图片";
            case ModelInputModalities.FILE:  return "文档";
            case ModelInputModalities.VIDEO: return "视频";
            case ModelInputModalities.AUDIO: return "音频";
            default: return "媒体";
        }
    }

    /** @deprecated 保留供旧单测,新逻辑走 {@link #omitHistoryMedia}。 */
    @Deprecated
    static String omitHistoryImages(String text)
    {
        String base = text == null ? "" : text;
        return base + "\n[历史图片已省略：当前模型不支持读图]";
    }
}
