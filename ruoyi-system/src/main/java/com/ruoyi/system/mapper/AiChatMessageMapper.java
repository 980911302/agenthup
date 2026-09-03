package com.ruoyi.system.mapper;

import com.ruoyi.system.domain.AiChatMessage;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 对话消息 Mapper(ai_chat_message 单表双视图,§4.1)。
 *
 * @author ruoyi
 */
public interface AiChatMessageMapper {

    /** 插入一条消息(createTime 由调用方传入,便于 H2 测试;生产传 new Date()) */
    int insertMessage(AiChatMessage message);

    /** 按主键查消息(对账回填 result_summary / tokens 用) */
    AiChatMessage selectMessageById(@Param("messageId") Long messageId);

    /** 精确读取某次 Run 的语义消息；同类若有多条取最后一条。 */
    AiChatMessage selectByRunAndKind(@Param("runId") String runId,
                                     @Param("messageKind") String messageKind);

    /** 取最新一条 SUMMARY(压缩边界)。无则返回 null。 */
    AiChatMessage selectLatestSummary(@Param("conversationId") String conversationId);

    /** 取压缩点之后、可见、非 SUMMARY 的消息(LLM 上下文增量)。无 summaryId 传 0。 */
    List<AiChatMessage> selectVisible(@Param("conversationId") String conversationId,
                                      @Param("fromId") long fromId);

    /** 精确读取某个 Run 的全部消息事实，终态恢复不依赖会话分页。 */
    List<AiChatMessage> selectByRunId(@Param("runId") String runId);

    /** 预算检查:可见消息 token 之和(自最新 SUMMARY 之后)。 */
    long sumTokensSinceSummary(@Param("conversationId") String conversationId,
                               @Param("lastSummaryId") long lastSummaryId);

    /** 压缩点之后 TOOL 行 tokens 之和(分类计量时从 messages 扣除)。 */
    long sumToolTokensSinceSummary(@Param("conversationId") String conversationId,
                                   @Param("lastSummaryId") long lastSummaryId);

    /** 前端时间线:全量按 message_id。 */
    List<AiChatMessage> selectTimelineBySession(@Param("sessionId") String sessionId);

    /**
     * 时间线分页(游标):按 message_id 倒序取 limit 条,用于「加载更早」。
     * @param beforeMessageId 只取 message_id 小于该值的消息;null 表示最新一页
     */
    List<AiChatMessage> selectTimelinePage(@Param("sessionId") String sessionId,
                                           @Param("beforeMessageId") Long beforeMessageId,
                                           @Param("limit") int limit);

    /** 定位某条消息之前最近的 USER 消息(补齐完整轮次用) */
    AiChatMessage selectPrevUserBefore(@Param("sessionId") String sessionId,
                                       @Param("beforeMessageId") Long beforeMessageId,
                                       @Param("firstMessageId") Long firstMessageId);

    /** 从 fromMessageId(含)开始的连续消息区间(升序),保证轮次完整 */
    List<AiChatMessage> selectTimelineRangeFrom(@Param("sessionId") String sessionId,
                                                @Param("fromMessageId") Long fromMessageId,
                                                @Param("toMessageId") Long toMessageId);

    /** 某条消息之前还有多少消息(判断是否还有更早) */
    int countBefore(@Param("sessionId") String sessionId, @Param("messageId") Long messageId);

    /** 会话内全部用户消息(右侧音轨导航用,按时间升序) */
    List<AiChatMessage> selectUserMessagesBySession(@Param("sessionId") String sessionId,
                                                    @Param("limit") int limit);

    /** 会话消息计数。 */
    int countBySession(@Param("sessionId") String sessionId);

    int deleteByConversationId(@Param("conversationId") String conversationId);

    /** 清空业务会话下所有 agent conversation 的消息。 */
    int deleteBySessionId(@Param("sessionId") String sessionId);

    /** 取 conversation 下最新一条 ASSISTANT 消息(回填 token 用) */
    AiChatMessage selectLatestAssistant(@Param("conversationId") String conversationId);

    /** 取某 conversation 最后一条 USER 消息(重新生成时的回滚起点) */
    AiChatMessage selectLatestUser(@Param("conversationId") String conversationId);

    /**
     * 取 message_id >= fromId 的消息 ID 列表(回滚前解绑 llm_call 用)。
     * <p>只查 ID:content/tool_args/tool_result 都是 longtext,
     * 为了几个 ID 走 selectTimelineBySession 会把整个会话的正文全拉回内存。
     */
    List<String> selectToolResultPathsFrom(@Param("conversationId") String conversationId,
                                           @Param("fromId") Long fromId);

    List<Long> selectIdsFrom(@Param("conversationId") String conversationId,
                             @Param("fromId") Long fromId);

    /** 删除 message_id >= fromId 的全部消息(回滚最后一轮，含 USER 本身) */
    int deleteFromMessageId(@Param("conversationId") String conversationId,
                            @Param("fromId") Long fromId);

    /** 回填本轮 USER 消息的附件元数据(JSON)。写入时机与 token 回填相同 */
    int updateAttachmentsById(@Param("messageId") Long messageId,
                              @Param("attachments") String attachments);

    /**
     * 流结束后回填 ASSISTANT 归因字段(不改 tokens 列)。
     * <p>{@code usage_source} 描述的是 prompt/completion 归因字段是否来自上游,不是 tokens 列。
     */
    int updateAttributionById(@Param("messageId") Long messageId,
                              @Param("promptTokens") int promptTokens,
                              @Param("completionTokens") int completionTokens,
                              @Param("modelName") String modelName,
                              @Param("usageSource") String usageSource);

    /** 测试专用:直接把某行标成已清理 */
    int markPrunedForTest(@Param("messageId") long messageId);

    /** 按 tool_call_id 取一条(回写 pruned 标记用) */
    AiChatMessage selectByToolCallId(@Param("conversationId") String conversationId,
                                     @Param("toolCallId") String toolCallId);

    /** 把 TOOL 行标成已清理,并把 tokens 改成占位文本的估算值 */
    int markToolPruned(@Param("conversationId") String conversationId,
                       @Param("toolCallId") String toolCallId,
                       @Param("tokens") int tokens);

    /** 把持有该 tool_call_id 的 ASSISTANT 行一并标成已清理(入参也被摘要了) */
    int markAssistantPruned(@Param("conversationId") String conversationId,
                            @Param("toolCallId") String toolCallId);

    // ---- 测试专用:H2 建表/删表,普通运行不触发 ----
    void createTableForTest();

    void dropTableForTest();
}
