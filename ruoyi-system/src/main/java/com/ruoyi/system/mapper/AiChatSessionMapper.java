package com.ruoyi.system.mapper;

import java.util.List;
import com.ruoyi.system.domain.AiChatSession;
import com.ruoyi.system.domain.AiChatSessionAgent;

/**
 * 会话 Mapper
 * <p>
 * 管理 ai_chat_session 主表 + ai_chat_session_agent 关联表。
 *
 * @author ruoyi
 */
public interface AiChatSessionMapper
{
    /* ==================== ai_chat_session 主表 ==================== */

    /**
     * 查询会话详情(含参与的智能体列表)
     */
    public AiChatSession selectAiChatSessionById(String sessionId);

    /** 会话所属且同属主的有效项目；无项目或脏数据返回 null。 */
    public Long selectOwnedProjectIdBySessionId(String sessionId);

    /** 查询有效项目内的全部有效会话，用于旧会话目录无损归并。 */
    public List<String> selectOwnedSessionIdsByProjectId(Long projectId);

    /** 锁定会话主行，串行化“创建运行”和删除/清空/回滚。 */
    public AiChatSession selectAiChatSessionByIdForUpdate(String sessionId);

    /**
     * 查询会话列表(带 agentCount 等聚合字段)
     */
    public List<AiChatSession> selectAiChatSessionList(AiChatSession aiChatSession);

    /**
     * 新增会话
     */
    public int insertAiChatSession(AiChatSession aiChatSession);

    /** 并发创建运行时幂等创建会话空壳。 */
    public int insertAiChatSessionIfAbsent(AiChatSession aiChatSession);

    /** 软删会话复活(del_flag 2→0)。 */
    public int reviveAiChatSession(String sessionId);

    /**
     * 修改会话
     */
    public int updateAiChatSession(AiChatSession aiChatSession);

    /** 只更新客户端声明三列 + update_time，避免整行覆盖。 */
    public int updateClientDeclare(AiChatSession aiChatSession);

    /**
     * 会话累计 token 原子加(避免并发竞态)
     */
    public int addSessionTokens(@org.apache.ibatis.annotations.Param("sessionId") String sessionId,
                                @org.apache.ibatis.annotations.Param("tokens") long tokens,
                                @org.apache.ibatis.annotations.Param("contextLength") long contextLength);

    /**
     * 会话累计 token 原子加(含 prompt/completion/调用次数拆分)
     */
    public int addSessionTokenDetail(@org.apache.ibatis.annotations.Param("sessionId") String sessionId,
                                     @org.apache.ibatis.annotations.Param("tokens") long tokens,
                                     @org.apache.ibatis.annotations.Param("promptTokens") long promptTokens,
                                     @org.apache.ibatis.annotations.Param("completionTokens") long completionTokens,
                                     @org.apache.ibatis.annotations.Param("callCount") int callCount,
                                     @org.apache.ibatis.annotations.Param("contextLength") long contextLength);

    /**
     * 会话消息条数原子加。
     *
     * <p>与 token 记账分开:token 是 LLM 调用维度(一次调用可能产出 thinking +
     * assistant + tool 多条消息),消息数是落表行数维度,两者不能共用一条语句。
     */
    public int addSessionMessageCount(@org.apache.ibatis.annotations.Param("sessionId") String sessionId,
                                      @org.apache.ibatis.annotations.Param("delta") int delta);

    /**
     * 删除会话(逻辑删除)
     */
    public int deleteAiChatSessionById(String sessionId);

    /**
     * 批量删除会话
     */
    public int deleteAiChatSessionByIds(String[] sessionIds);

    /* ==================== ai_chat_session_agent 关联表 ==================== */

    /**
     * 查询某会话下的全部智能体(带 agent 名称等展示字段)
     */
    public List<AiChatSessionAgent> selectAgentsBySessionId(String sessionId);

    /**
     * 查询某会话下某智能体(判断是否已接入)
     */
    public AiChatSessionAgent selectSessionAgent(@org.apache.ibatis.annotations.Param("sessionId") String sessionId,
                                                 @org.apache.ibatis.annotations.Param("agentId") Long agentId);

    /**
     * 新增关联(worker 首次接入时)
     */
    public int insertSessionAgent(AiChatSessionAgent agent);

    /**
     * 关联表 token 原子加 + 轮数 +1 + 更新最后活动时间
     */
    public int addAgentTokens(@org.apache.ibatis.annotations.Param("sessionId") String sessionId,
                              @org.apache.ibatis.annotations.Param("agentId") Long agentId,
                              @org.apache.ibatis.annotations.Param("tokens") long tokens);

    /**
     * 关联表 token 原子加(含 prompt/completion/调用次数) + 轮数 +1
     */
    public int addAgentTokenDetail(@org.apache.ibatis.annotations.Param("sessionId") String sessionId,
                                   @org.apache.ibatis.annotations.Param("agentId") Long agentId,
                                   @org.apache.ibatis.annotations.Param("tokens") long tokens,
                                   @org.apache.ibatis.annotations.Param("promptTokens") long promptTokens,
                                   @org.apache.ibatis.annotations.Param("completionTokens") long completionTokens,
                                   @org.apache.ibatis.annotations.Param("callCount") int callCount);

    /**
     * 会话内每个智能体的累计消耗,主智能体(supervisor)固定排首位,其余按消耗降序。
     *
     * <p>供上下文面板的「会话消耗」区块用。注意它与环图是<b>两个口径</b>:
     * 环图量的是「当前 prompt 占窗口多少」(只算 depth=0,子 agent 不占父的窗口),
     * 这里量的是「累计花掉多少」(含子 agent,无上限)。
     *
     * @return 每行 agentId/agentName/role/tokens/promptTokens/completionTokens/callCount
     */
    public List<java.util.Map<String, Object>> selectAgentSpendBySession(String sessionId);

    /**
     * 删除某会话下的全部关联(会话删除时清理)
     */
    public int deleteAgentsBySessionId(String sessionId);
}
