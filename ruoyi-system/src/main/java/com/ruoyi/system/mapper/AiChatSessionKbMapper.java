package com.ruoyi.system.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 会话-知识库关联 Mapper(ai_chat_session_kb)
 * <p>
 * 会话级多选知识库：读写某会话挂了哪些知识库。
 *
 * @author ruoyi
 */
public interface AiChatSessionKbMapper
{
    /**
     * 查询某会话选中的知识库ID，按 sort 升序(前端选择顺序)。
     */
    public List<Long> selectKbIdsBySessionId(String sessionId);

    /**
     * 删除某会话的全部关联(会话删除或整组重写时)。
     */
    public int deleteBySessionId(String sessionId);

    /**
     * 批量插入(foreach 按传入顺序自增 sort，从 0 起)。
     */
    public int batchInsert(@Param("sessionId") String sessionId,
                           @Param("kbIds") List<Long> kbIds);

    /**
     * 删除某知识库的全部会话关联(知识库删除时清理孤儿)。
     */
    public int deleteByKbId(Long kbId);
}
