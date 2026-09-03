package com.ruoyi.system.mapper;

import java.util.List;

import com.ruoyi.system.domain.AiChatSpecialEvent;
import com.ruoyi.system.domain.AiChatSpecialEventSummary;
import org.apache.ibatis.annotations.Param;

/** 会话特殊事件 Mapper。生命周期只跟 session,禁止 deleteByRunId。 */
public interface AiChatSpecialEventMapper
{
    int upsert(AiChatSpecialEvent event);

    int insert(AiChatSpecialEvent event);

    int updateIfVersion(AiChatSpecialEvent event);

    AiChatSpecialEvent selectBySessionEvent(@Param("sessionId") String sessionId,
                                            @Param("eventId") String eventId);

    List<AiChatSpecialEvent> selectBySessionId(@Param("sessionId") String sessionId);

    int deleteBySessionId(@Param("sessionId") String sessionId);

    List<AiChatSpecialEventSummary> selectSummariesBySessionId(@Param("sessionId") String sessionId);

    List<AiChatSpecialEvent> selectPayloads(@Param("sessionId") String sessionId,
                                            @Param("messageId") Long messageId,
                                            @Param("name") String name);

    void createTableForTest();
}
