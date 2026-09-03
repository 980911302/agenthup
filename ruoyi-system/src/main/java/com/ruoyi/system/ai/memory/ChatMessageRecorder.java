package com.ruoyi.system.ai.memory;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.system.ai.ContextFileStore;
import com.ruoyi.system.ai.context.ToolPlaceholders;
import com.ruoyi.system.domain.AiChatMessage;
import com.ruoyi.system.mapper.AiChatMessageMapper;
import com.ruoyi.system.tool.ToolAttachment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

/**
 * 消息写入 + 工具摘要读取 + 大字段溢出(§4.6/§4.9)。
 *
 * <p>是 {@link DbChatMemory} 与 {@code RecordingToolCallback} 共同的写入门面,
 * 把「写一条 ai_chat_message」这件事收口在这里,避免散落多处。
 *
 * <p>三条写通路(§4.6)都写同一张表,靠 {@code message_id} 自增保证时间线顺序:
 * <ul>
 *   <li>{@link DbChatMemory#add} -> {@link #insert}(USER / 最终 ASSISTANT,visible=0)</li>
 *   <li>{@code RecordingToolCallback} -> {@link #recordToolCall}(TOOL,visible=1)</li>
 *   <li>(第二段)AgentRunner 流式过程 -> {@link #insert}(THINKING、中间 ASSISTANT,visible=1)</li>
 * </ul>
 *
 * @author ruoyi
 */
@Component
public class ChatMessageRecorder
{
    private static final Logger log = LoggerFactory.getLogger(ChatMessageRecorder.class);

    @Autowired
    private AiChatMessageMapper mapper;

    @Autowired
    private ContextFileStore contextFileStore;

    @Autowired
    private TokenEstimator tokenEstimator;

    @Autowired(required = false)
    private com.ruoyi.system.mapper.AiChatRunStepMapper runStepMapper;

    /** 只用于消息条数累加;required=false 让既有单测不必额外 mock */
    @Autowired(required = false)
    private com.ruoyi.system.mapper.AiChatSessionMapper sessionMapper;

    /** 大字段内联上限(§4.9),超出溢出到文件,表内只留预览 */
    @Value("${ai.chat.inline-limit:2048}")
    private int inlineLimit;

    /**
     * 消息落库的唯一出口:插入 + 会话消息条数原子加一。
     *
     * <p>计数放在这里而不是各调用点,是因为「消息条数」的定义就是本表的落表行数 ——
     * 任何绕开这里的插入都会让 {@code ai_chat_session.message_count} 与实际行数漂移。
     * 该列此前长期恒为 0(建表时随 context_length 一起加,但从未进过 ORM 层)。
     *
     * <p>累加失败不能连累消息本身:计数是统计值,消息是审计流,后者重要得多。
     */
    private void persist(AiChatMessage m)
    {
        mapper.insertMessage(m);
        String sessionId = m.getSessionId();
        if (sessionMapper == null || sessionId == null || sessionId.isBlank())
        {
            return;
        }
        try
        {
            sessionMapper.addSessionMessageCount(sessionId, 1);
        }
        catch (Exception e)
        {
            log.warn("会话消息计数累加失败 sessionId={}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * 工具往返是否进 LLM 上下文(§跨轮保留)。
     *
     * <p>关闭时 TOOL 行与中间 ASSISTANT 行写 {@code visible_to_llm='1'},维持改造前行为,
     * 便于灰度与回滚。历史数据写入时是什么就是什么,不会被开关追溯改变 ——
     * 打开开关后老会话的工具往返仍然不进上下文,不会突然变形。
     */
    @Value("${ai.chat.context.tool-messages-in-context:true}")
    private boolean toolMessagesInContext;

    /**
     * 通用消息插入(USER/ASSISTANT/SYSTEM/THINKING/SUMMARY)。
     *
     * @param conversationId LLM记忆键(sessionId:agentId)
     * @param sessionId      会话ID
     * @param agentId        产生该消息的智能体ID
     * @param messageType    USER/ASSISTANT/SYSTEM/TOOL/THINKING/SUMMARY
     * @param content        正文
     * @param visibleToLlm   '0' 参与 LLM 上下文 / '1' 只给前端
     * @param tokens         token 数(可空,空则按估算补)
     * @return 新插入行的 message_id
     */
    public long insert(String conversationId, String sessionId, Long agentId,
                       String messageType, String content, String visibleToLlm, Integer tokens)
    {
        return insert(conversationId, sessionId, agentId, messageType, content, visibleToLlm, tokens,
                null, defaultKind(messageType), null, null);
    }

    public long insert(String conversationId, String sessionId, Long agentId,
                       String messageType, String content, String visibleToLlm, Integer tokens,
                       String runId, String messageKind, String stepId, String parentStepId)
    {
        AiChatMessage m = new AiChatMessage();
        m.setConversationId(conversationId);
        m.setSessionId(sessionId);
        m.setAgentId(agentId);
        m.setRunId(runId);
        m.setMessageKind(messageKind);
        m.setStepId(stepId);
        m.setParentStepId(parentStepId);
        m.setMessageType(messageType);
        m.setContent(content);
        m.setVisibleToLlm(visibleToLlm);
        // tokens 列永远是 TokenEstimator 估算值,供环图占比/预算用,口径全局一致。
        // 真实用量只写 ai_llm_call 与会话汇总,不回填到本列(上游没有"单条消息多少 token")。
        m.setTokens(tokens != null ? tokens : tokenEstimator.estimate(content));
        // usage_source 描述的是下方 prompt/completion 归因字段,不是 tokens 列
        m.setUsageSource("1");
        m.setPromptTokens(0);
        m.setCompletionTokens(0);
        m.setCreateTime(new Date());
        persist(m);
        return m.getMessageId();
    }

    /**
     * THINKING 行写入(含大字段溢出,§4.9)。
     *
     * <p><b>visible_to_llm='1',绝不进 LLM 上下文</b>:思考是模型自己的草稿,回灌下一轮会污染推理,
     * 且体量常大于正文。{@code selectVisible} 与 {@code sumTokensSinceSummary} 都已按
     * 「visible='0' 或 TOOL」取,THINKING 天然被排除在上下文与预算之外 —— 它只喂前端时间线。
     *
     * <p>超过 {@code inline-limit} 的正文落 {@code thinking/*.txt},表内留预览 + 路径,
     * 与工具结果同一套溢出机制。写文件失败时退化为整段入库,不能因为审计投影写不了就丢记录。
     *
     * @param content 本段思考正文
     * @return 新插入行的 message_id;content 为空时返回 0 且不写库
     */
    public long recordThinking(String conversationId, String sessionId, Long agentId, String content)
    {
        return recordThinking(conversationId, sessionId, agentId, content, null, null, null);
    }

    public long recordThinking(String conversationId, String sessionId, Long agentId, String content,
                               String runId, String stepId, String parentStepId)
    {
        if (content == null || content.isEmpty())
        {
            return 0L;
        }
        AiChatMessage m = new AiChatMessage();
        m.setConversationId(conversationId);
        m.setSessionId(sessionId);
        m.setAgentId(agentId);
        m.setRunId(runId);
        m.setMessageKind(ChatMessageKind.REASONING);
        m.setStepId(stepId);
        m.setParentStepId(parentStepId);
        m.setMessageType("THINKING");
        m.setVisibleToLlm("1");
        // tokens 仅供前端展示;上下文预算的 sum 已把 THINKING 排除,这里写真实估算不会污染刻度
        m.setTokens(tokenEstimator.estimate(content));
        m.setUsageSource("1");
        m.setPromptTokens(0);
        m.setCompletionTokens(0);
        m.setCreateTime(new Date());
        if (content.length() > inlineLimit)
        {
            try
            {
                String path = contextFileStore.saveThinking(sessionId, null, content);
                m.setContent(content.substring(0, inlineLimit) + "\n…[已截断,完整内容见附件]");
                m.setToolResultPath(path);
            }
            catch (Exception e)
            {
                log.warn("思考正文溢出到文件失败,退化为整段入库: session={}", sessionId, e);
                m.setContent(content);
            }
        }
        else
        {
            m.setContent(content);
        }
        persist(m);
        return m.getMessageId();
    }

    /**
     * TOOL 行写入(含大字段溢出,§4.9)。
     * <p>工具消息 {@code visible_to_llm='1'}:绝不能进滑动窗口,OpenAI 协议要求带 tool_calls 的
     * assistant 消息后紧跟对应 tool_call_id 的 tool 消息,按条数滑动的窗口会从中间切开 -> 400。
     * 结构上 TOOL 行就进不去(§4.3)。
     *
     * @param conversationId LLM记忆键
     * @param sessionId       会话ID
     * @param agentId         调用工具的智能体ID
     * @param subAgentId      被调用的子智能体ID(agent-as-tool 时非空)
     * @param toolName        工具名(子智能体调用时为 agentCode)
     * @param args            工具入参(JSON 字符串)
     * @param result          工具返回原文(可能很大)
     * @param toolSource      builtin/mcp/agent
     * @param durationMs      执行耗时
     * @param ok              是否成功
     * @return 新插入行的 message_id
     */
    public long recordToolCall(String conversationId, String sessionId, Long agentId, Long subAgentId,
                              String toolName, String args, String result,
                              String toolSource, long durationMs, boolean ok)
    {
        return recordToolCall(conversationId, sessionId, agentId, subAgentId,
                toolName, args, result, toolSource, durationMs, ok, null, null);
    }

    /**
     * TOOL 行写入(含大字段溢出,§4.9),可带附件。
     * <p>附件(如生图工具产出的图片)写入 {@code ai_chat_message.attachments} 列(JSON 字符串),
     * 与用户上传附件同字段同格式,前端 {@code parseAttachments} 统一解析。
     *
     * @param attachments 工具产出附件列表(可空)
     */
    public long recordToolCall(String conversationId, String sessionId, Long agentId, Long subAgentId,
                              String toolName, String args, String result,
                              String toolSource, long durationMs, boolean ok,
                              List<ToolAttachment> attachments)
    {
        return recordToolCall(conversationId, sessionId, agentId, subAgentId,
                toolName, args, result, toolSource, durationMs, ok, attachments, null);
    }

    /** TOOL 行写入(含大字段溢出,§4.9),可带附件与调用 ID。 */
    public long recordToolCall(String conversationId, String sessionId, Long agentId, Long subAgentId,
                              String toolName, String args, String result,
                              String toolSource, long durationMs, boolean ok,
                              List<ToolAttachment> attachments, String toolCallId)
    {
        return recordToolCall(conversationId, sessionId, agentId, subAgentId, toolName, args, result,
                toolSource, durationMs, ok, attachments, toolCallId, null, toolCallId, null);
    }

    public long recordToolCall(String conversationId, String sessionId, Long agentId, Long subAgentId,
                              String toolName, String args, String result,
                              String toolSource, long durationMs, boolean ok,
                              List<ToolAttachment> attachments, String toolCallId,
                              String runId, String stepId, String parentStepId)
    {
        return recordToolCall(conversationId, sessionId, agentId, subAgentId, toolName, args, result,
                toolSource, durationMs, ok, attachments, toolCallId, runId, stepId, parentStepId,
                result);
    }

    /**
     * TOOL 行写入,显式给出「本轮真正进上下文的那段正文」。
     *
     * <p><b>{@code contextText} 由调用方传入而不是这里重算</b>:调用方(RecordingToolCallback)
     * 已经用 {@link com.ruoyi.system.ai.context.ToolResultText} 裁过一次交给模型,
     * 这里再照着推一遍就成了同一个量的第二份公式 —— 截断策略一改就会两边打架
     * (历史上就漏过行数维度:本轮截到 2000 行,tokens 却按全量算)。
     *
     * <p>{@code result} 仍是<b>全文</b>:表与溢出文件存全文供审计,
     * 只有 {@code tokens} 按进上下文的那段算。两者职责不同,不能混用同一个值。
     *
     * @param result      工具返回原文(落库/落文件)
     * @param contextText 本轮交给模型的正文(算 tokens);与 result 相同则表示未截断
     */
    public long recordToolCall(String conversationId, String sessionId, Long agentId, Long subAgentId,
                              String toolName, String args, String result,
                              String toolSource, long durationMs, boolean ok,
                              List<ToolAttachment> attachments, String toolCallId,
                              String runId, String stepId, String parentStepId,
                              String contextText)
    {
        AiChatMessage m = new AiChatMessage();
        m.setConversationId(conversationId);
        m.setSessionId(sessionId);
        m.setAgentId(agentId);
        m.setSubAgentId(subAgentId);
        m.setRunId(runId);
        m.setMessageKind(ChatMessageKind.TOOL_RESULT);
        m.setStepId(stepId);
        m.setParentStepId(parentStepId);
        m.setMessageType("TOOL");
        m.setToolCallId(toolCallId);
        m.setVisibleToLlm(toolMessagesInContext ? "0" : "1");
        m.setPruned("0");
        m.setToolName(toolName);
        m.setToolArgs(args);
        m.setToolSource(toolSource);
        m.setToolDurationMs(durationMs);
        m.setToolSuccess(ok ? "0" : "1");
        // tokens 按「真正进上下文的那段文本」估算:
        //   开关开 -> 调用方裁好的 contextText(本轮所见 / 跨轮重建 / tokens 三者同源)
        //   开关关 -> 仍按兼容旧行为的截断摘要文本
        // 两种口径都不能写 0,否则上下文刻度条系统性偏低。
        if (toolMessagesInContext)
        {
            m.setTokens(tokenEstimator.estimate(contextText != null ? contextText : result));
        }
        else
        {
            String a = truncate(args, 200);
            String r = truncate(result, 200);
            m.setTokens(tokenEstimator.estimate(
                    "- " + (toolName != null ? toolName : "") + "(" + a + ") -> " + r + "\n"));
        }
        m.setUsageSource("1");
        m.setCreateTime(new Date());
        // 大字段溢出:tool_result 超 inlineLimit 落文件,表内留预览
        if (result != null && result.length() > inlineLimit)
        {
            try
            {
                String path = contextFileStore.saveToolResult(sessionId, null, result);
                m.setToolResult(result.substring(0, inlineLimit) + "\n…[已截断,完整内容见附件]");
                m.setToolResultPath(path);
            }
            catch (Exception e)
            {
                // 溢出失败不阻断主流程,退化为整段入库
                log.warn("工具结果溢出到文件失败,退化为整段入库: tool={}", toolName, e);
                m.setToolResult(result);
            }
        }
        else
        {
            m.setToolResult(result);
        }
        // 附件(生图工具产出的图片等)写入 attachments 列,与用户上传附件同字段
        if (attachments != null && !attachments.isEmpty())
        {
            m.setAttachments(JSON.toJSONString(attachments));
        }
        persist(m);
        bindStepMessage(runId, stepId, m.getMessageId());
        return m.getMessageId();
    }

    private void bindStepMessage(String runId, String stepId, Long messageId)
    {
        if (runStepMapper == null || runId == null || stepId == null || messageId == null)
        {
            return;
        }
        try
        {
            runStepMapper.bindMessageId(runId, stepId, messageId);
        }
        catch (Exception e)
        {
            log.warn("绑定 run_step.message_id 失败 run={} step={}: {}", runId, stepId, e.getMessage());
        }
    }

    /**
     * 中间 ASSISTANT 行落库:模型发出 tool_calls 的那一轮。
     *
     * <p><b>为什么必须落</b>:跨轮重建工具往返时,OpenAI 协议要求 tool 消息前面有一条带
     * 对应 tool_call_id 的 assistant。这条行就是它。顺带把模型调工具前说的那句话
     * (「我先读一下配置文件」)也保住了 —— 改造前这段文本是彻底丢弃的。
     *
     * <p>{@code tool_calls} 列存 JSON 数组 {@code [{id,type,name,arguments}]},
     * arguments 存**原始入参**不做截断:它是重建 assistant 消息的原料,
     * 被清理后由 {@code pruned} 标记在装配时换成摘要,不在写入时提前劣化。
     *
     * @return 新行 message_id;toolCalls 为空时返回 0 且不写库
     */
    public long recordAssistantToolCalls(String conversationId, String sessionId, Long agentId,
                                         String text, List<AssistantMessage.ToolCall> toolCalls)
    {
        return recordAssistantToolCalls(conversationId, sessionId, agentId, text, toolCalls,
                null, null, null);
    }

    public long recordAssistantToolCalls(String conversationId, String sessionId, Long agentId,
                                         String text, List<AssistantMessage.ToolCall> toolCalls,
                                         String runId, String stepId, String parentStepId)
    {
        if (toolCalls == null || toolCalls.isEmpty())
        {
            return 0L;
        }
        JSONArray arr = new JSONArray();
        int argTokens = 0;
        for (AssistantMessage.ToolCall tc : toolCalls)
        {
            JSONObject o = new JSONObject();
            o.put("id", tc.id());
            o.put("type", tc.type());
            o.put("name", tc.name());
            o.put("arguments", tc.arguments());
            arr.add(o);
            argTokens += tokenEstimator.estimate(tc.name()) + tokenEstimator.estimate(tc.arguments());
        }
        AiChatMessage m = new AiChatMessage();
        m.setConversationId(conversationId);
        m.setSessionId(sessionId);
        m.setAgentId(agentId);
        m.setRunId(runId);
        m.setMessageKind(ChatMessageKind.ASSISTANT_TOOL_CALL);
        m.setStepId(stepId);
        m.setParentStepId(parentStepId);
        m.setMessageType("ASSISTANT");
        m.setContent(text != null ? text : "");
        m.setToolCalls(arr.toJSONString());
        m.setVisibleToLlm(toolMessagesInContext ? "0" : "1");
        m.setPruned("0");
        // tokens 只算 text + tool_calls(name/args);tool 结果算在各自的 TOOL 行,不重复计
        m.setTokens(tokenEstimator.estimate(m.getContent()) + argTokens);
        m.setUsageSource("1");
        m.setPromptTokens(0);
        m.setCompletionTokens(0);
        m.setCreateTime(new Date());
        persist(m);
        return m.getMessageId();
    }

    /** 取最新一条 SUMMARY(压缩边界)。 */
    public AiChatMessage latestSummary(String conversationId)
    {
        return mapper.selectLatestSummary(conversationId);
    }

    /** 取压缩点之后、可见、非 SUMMARY 的消息。 */
    public List<AiChatMessage> selectVisible(String conversationId, long fromId)
    {
        return mapper.selectVisible(conversationId, fromId);
    }

    private static String defaultKind(String messageType)
    {
        if (messageType == null) return null;
        return switch (messageType)
        {
            case "USER" -> ChatMessageKind.USER_INPUT;
            case "ASSISTANT" -> ChatMessageKind.ASSISTANT_FINAL;
            case "THINKING" -> ChatMessageKind.REASONING;
            case "SUMMARY" -> ChatMessageKind.SUMMARY;
            case "SYSTEM" -> ChatMessageKind.SYSTEM;
            case "TOOL" -> ChatMessageKind.TOOL_RESULT;
            default -> null;
        };
    }

    /** 预算检查:可见消息 token 之和。 */
    public long sumTokensSinceSummary(String conversationId, long lastSummaryId)
    {
        return mapper.sumTokensSinceSummary(conversationId, lastSummaryId);
    }

    /** 压缩点之后工具往返 tokens 之和(分类计量时从 messages 扣除)。 */
    public long sumToolTokensSinceSummary(String conversationId, long lastSummaryId)
    {
        return mapper.sumToolTokensSinceSummary(conversationId, lastSummaryId);
    }

    /** 取最新 ASSISTANT 消息(流结束后回填 token 用)。 */
    public AiChatMessage latestAssistant(String conversationId)
    {
        return mapper.selectLatestAssistant(conversationId);
    }

    /**
     * 回填 ASSISTANT 消息的调用归因字段(不改 tokens 列)。
     * <p>tokens 列保持 insert 时的估算值 —— 上游只给每次调用总量,没有"单条消息多少 token",
     * 用整轮 completion 标最后一条 ASSISTANT 会在工具循环里错误归因。
     *
     * @param messageId        消息 ID
     * @param promptTokens     该轮输入(归因展示)
     * @param completionTokens 该轮输出(归因展示)
     * @param modelName        上游模型名
     * @param usageSource      0真实 1估算 —— 描述的是上面两个归因字段,不是 tokens
     */
    public void updateAttribution(long messageId, int promptTokens, int completionTokens,
                                  String modelName, String usageSource)
    {
        mapper.updateAttributionById(messageId, promptTokens, completionTokens, modelName, usageSource);
    }

    /**
     * 把被 {@code ContextCleaner} 清掉的工具往返在库里标成已清理。
     *
     * <p><b>只标记不改原文</b>:{@code tool_result} / {@code tool_args} 原样留着,
     * 审计与前端时间线照常能看到全文;只有装配给 LLM 时才按标记出占位。
     * 这是本项目「ai_chat_message 是唯一事实源」与 OpenCode「prune 直接改写 session」
     * 之间的取舍点 —— 我们选了不可逆性更低的那个。
     *
     * <p>{@code tokens} 同步改成占位文本的估算值,否则上下文刻度条会一直按原文算,
     * 清了半天面板纹丝不动。
     *
     * <p>任何一条失败都不影响其它条,也不影响本轮对话 —— 标记丢了最坏就是下一轮重清一次。
     */
    public void markPruned(String conversationId, List<String> toolCallIds)
    {
        if (conversationId == null || toolCallIds == null || toolCallIds.isEmpty())
        {
            return;
        }
        for (String id : toolCallIds)
        {
            if (id == null || id.isEmpty())
            {
                continue;
            }
            try
            {
                AiChatMessage row = mapper.selectByToolCallId(conversationId, id);
                if (row == null || "1".equals(row.getPruned()))
                {
                    continue;
                }
                String placeholder = ToolPlaceholders.buildPlaceholder(
                        row.getToolName(), row.getToolArgs(), null);
                mapper.markToolPruned(conversationId, id, tokenEstimator.estimate(placeholder));
                mapper.markAssistantPruned(conversationId, id);
            }
            catch (Exception e)
            {
                log.warn("回写 pruned 标记失败 conv={} toolCallId={}: {}",
                        conversationId, id, e.getMessage());
            }
        }
    }

    /**
     * 写一条 SUMMARY 行(压缩边界)。
     *
     * <p>{@code summary_to_id} 记录本摘要覆盖到哪一条 message_id,供审计追溯"这段提要是从哪来的"。
     * {@code DbChatMemory.get} 的加载边界用的是 SUMMARY 行**自身**的 message_id ——
     * 压缩后的新消息都排在它后面,以它为界最简单也最不容易错。
     *
     * <p>表上有 {@code uk_conv_summary_to} 唯一索引,同一范围重复压缩会撞唯一键,
     * 天然防住"同一段历史被压两次"。
     */
    public long recordSummary(String conversationId, String sessionId, Long agentId,
                              String summaryText, long summaryToId)
    {
        AiChatMessage m = new AiChatMessage();
        m.setConversationId(conversationId);
        m.setSessionId(sessionId);
        m.setAgentId(agentId);
        m.setMessageType("SUMMARY");
        m.setContent(summaryText);
        m.setVisibleToLlm("0");
        m.setSummaryToId(summaryToId);
        m.setPruned("0");
        m.setTokens(tokenEstimator.estimate(summaryText));
        m.setUsageSource("1");
        m.setPromptTokens(0);
        m.setCompletionTokens(0);
        m.setCreateTime(new Date());
        persist(m);
        return m.getMessageId();
    }

    /** 清空某 conversation 的全部消息(clearMemory 用)。 */
    public void deleteByConversationId(String conversationId)
    {
        mapper.deleteByConversationId(conversationId);
    }

    private static String truncate(String s, int max)
    {
        if (s == null)
        {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
