package com.ruoyi.system.service;

import java.util.List;
import com.ruoyi.system.domain.AiChatSession;
import com.ruoyi.system.domain.AiChatSessionAgent;

/**
 * 会话 服务层
 * <p>
 * 管理会话生命周期 + token 累加 + agent 接入。
 * 上下文文件读写不在此服务内，由 ContextFileStore 负责。
 *
 * @author ruoyi
 */
public interface IAiChatSessionService
{
    /**
     * 查询会话详情(含参与的智能体列表)
     */
    public AiChatSession selectAiChatSessionById(String sessionId);

    /**
     * 查询会话列表
     */
    public List<AiChatSession> selectAiChatSessionList(AiChatSession aiChatSession);

    /**
     * 新增会话(返回主键)
     */
    public int insertAiChatSession(AiChatSession aiChatSession);

    /**
     * 修改会话
     */
    public int updateAiChatSession(AiChatSession aiChatSession);

    /**
     * 删除会话(逻辑删除，同时清理关联表与磁盘附属物)。
     * <p>含：消息 / 运行记录 / 智能体关联、
     * ContextFileStore、会话工作区沙箱、工具预算。
     * LLM 明细是已发生的计量凭证，必须保留以支持个人用量统计。
     */
    public int deleteAiChatSessionById(String sessionId);

    /**
     * 批量删除会话（级联范围同 {@link #deleteAiChatSessionById}）
     */
    public int deleteAiChatSessionByIds(String[] sessionIds);

    /**
     * 查询某会话下的全部智能体
     */
    public List<AiChatSessionAgent> selectAgentsBySessionId(String sessionId);

    /**
     * 接入智能体：若该 agent 未接入过本会话则新增关联，已存在则跳过。
     * 用于 worker 首次被 supervisor 路由到时自动登记。
     *
     * @param sessionId 会话ID
     * @param agentId   智能体ID
     * @param role      角色(supervisor/worker)
     * @return 该会话-智能体关联记录
     */
    public AiChatSessionAgent ensureAgentJoined(String sessionId, Long agentId, String role);

    /**
     * 记录一次 agent 调用的 token 消耗。
     * 同时更新：关联表 tokens_used + turn_count + last_active_time
     *          会话表 total_tokens + context_length
     * 全部原子加，并发安全。
     *
     * @param sessionId      会话ID
     * @param agentId        智能体ID
     * @param tokens         本次消耗 token
     * @param contextLength  本次新增的上下文长度(字符数)
     */
    public void recordTokenUsage(String sessionId, Long agentId, long tokens, long contextLength);

    /**
     * 记录 LLM 调用明细汇总(含 prompt/completion/调用次数拆分)。
     *
     * @param sessionId         会话ID
     * @param agentId           智能体ID(可空)
     * @param tokens            合计 token
     * @param promptTokens      输入 token
     * @param completionTokens  输出 token
     * @param callCount         本轮 LLM 调用次数
     * @param contextLength     本次新增上下文长度(字符数)
     */
    public void recordTokenUsage(String sessionId, Long agentId, long tokens,
                                 long promptTokens, long completionTokens,
                                 int callCount, long contextLength);

    /**
     * 保存会话选中的知识库(整组替换)：先对每个 kbId 校验当前用户有 USE 权限，再删旧插新。
     * <p>kbIds 为 null/空 表示清空会话知识库。权限走 SecurityContext
     * ({@code KbAuthorizationService.requireKb})，仅可绑定自己可访问的库。
     *
     * @param sessionId 会话ID
     * @param kbIds     知识库ID列表(会话级多选)
     */
    public void saveSessionKbs(String sessionId, List<Long> kbIds);

    /**
     * 查询某会话选中的知识库ID，按选择顺序返回(装配 RAG 检索工具时用)。
     */
    public List<Long> getSessionKbIds(String sessionId);

    /**
     * 客户端声明能力与工具清单。版本相同不写库。
     */
    ClientDeclareResult declareClient(String sessionId, String clientType, String version,
                                      String toolsJson, Long userId, boolean admin);

    record ClientDeclareResult(boolean applied, String clientType, List<String> skipped) { }
}
