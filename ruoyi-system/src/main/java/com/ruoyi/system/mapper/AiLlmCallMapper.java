package com.ruoyi.system.mapper;

import java.util.Date;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.system.domain.AiLlmCall;

/**
 * LLM 调用明细 Mapper
 *
 * @author ruoyi
 */
public interface AiLlmCallMapper
{
    int insertLlmCall(AiLlmCall call);

    /**
     * 把指定的调用明细行绑定到最终 ASSISTANT 消息。
     * <p>按 call_id 精确绑定（调用方保证 callIds 非空）。
     */
    int bindMessageIdByIds(@Param("messageId") Long messageId,
                           @Param("callIds") List<Long> callIds);

    /**
     * 解除对指定消息的绑定(重新生成回滚时用)。
     * <p>只清 message_id，不删行：token 已经真实消耗，统计口径必须保留。
     */
    int unbindMessageIds(@Param("messageIds") List<Long> messageIds);

    /** 清空会话消息前解除该会话全部计量行的消息引用，保留真实消耗统计。 */
    int unbindMessageIdsBySession(@Param("sessionId") String sessionId);

    /**
     * 物理删除某会话的全部调用明细。
     * <p>仅供数据治理或合规清理使用；普通会话删除不得调用，因为 Token 已真实消耗。
     */
    int deleteBySessionId(@Param("sessionId") String sessionId);

    /**
     * 物理删除某模型(depth 无关)产生的全部调用明细。
     * <p>删除模型时调用，保证首页 by-model 统计不再计入已删除模型。
     */
    int deleteByModelIds(@Param("modelIds") List<Long> modelIds);

    /** 会话是否存在估算来源的调用 */
    int countEstimatedBySession(@Param("sessionId") String sessionId);

    /**
     * 最近一次顶层(depth=0)调用的 prompt_tokens —— 是上次请求的真实观测，
     * 不等同于新消息落库后此刻可重建的当前上下文。
     *
     * <p><b>为什么是「最近一次」而不是「求和」</b>:差值记账减掉的是 Spring AI 的累计包装,
     * 减完之后每一行就是<b>那次调用自己的完整 prompt</b>,不是相对上一次的增量。
     * 工具循环里逐次增长(实测 14,624 → 24,896 → 33,774),末次即当时的上下文大小。
     * 求和得到的是本轮累计消耗量(计费口径),拿它当上下文占用会虚高数倍 ——
     * 曾因此让面板显示 146%(175.2K/119.8K),实际只有 33,774。
     *
     * @return 无数据时为 0
     */
    long selectLatestPromptTokens(@Param("sessionId") String sessionId,
                                  @Param("conversationId") String conversationId);

    /**
     * 会话内顶层调用的 prompt_tokens 峰值(历史最大上下文)。
     * <p>与 {@link #selectLatestPromptTokens} 不同:后者是「现在」,本方法是「曾经最挤」。
     * 面板用 peak 纠正「现在很空、但本会话曾经逼近上限」的认知偏差。
     *
     * @return 无数据时为 0
     */
    long selectPeakPromptTokens(@Param("sessionId") String sessionId,
                                @Param("conversationId") String conversationId);

    /**
     * 会话级缓存命中聚合,同时按主/子智能体分列。
     *
     * <p>主子分开看才有意义:子智能体轮次多、上下文长,命中率通常和主智能体差一截,
     * 混在一起的加权值会被调用量大的那一方主导。
     *
     * @return map keys: hit, miss, calls(全会话)+ mainHit, mainMiss, mainCalls(depth=0)
     *         + subHit, subMiss, subCalls(depth&gt;0),均为 Number
     */
    Map<String, Object> sumCacheTokensBySession(@Param("sessionId") String sessionId);

    /** 概览统计 */
    Map<String, Object> selectOverview(@Param("since") Date since);

    /** 按模型 TOP */
    List<Map<String, Object>> selectByModel(@Param("since") Date since, @Param("limit") int limit);

    /** 按智能体 TOP */
    List<Map<String, Object>> selectByAgent(@Param("since") Date since, @Param("limit") int limit);

    /** 按天趋势 */
    List<Map<String, Object>> selectTrend(@Param("since") Date since);

    /**
     * 缓存命中聚合(首页健康指标)。
     * 仅统计 usage_source='0'(上游真实返回) — 估算行无 cache 字段。
     * @return map keys: hitTokens / missTokens / promptTokens
     */
    Map<String, Object> selectCacheStats(@Param("since") Date since);

    /** 当前用户的 Token 概览(逻辑删除会话的已发生调用也应保留)。 */
    Map<String, Object> selectUserOverview(@Param("since") Date since, @Param("userId") Long userId);

    /** 当前用户按日 Token 消耗趋势。 */
    List<Map<String, Object>> selectUserTrend(@Param("since") Date since, @Param("userId") Long userId);

    /** 当前用户缓存命中聚合，仅统计有真实 usage 的调用。 */
    Map<String, Object> selectUserCacheStats(@Param("since") Date since, @Param("userId") Long userId);
}
