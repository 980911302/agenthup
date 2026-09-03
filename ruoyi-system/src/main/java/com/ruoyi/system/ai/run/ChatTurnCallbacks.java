package com.ruoyi.system.ai.run;

import java.util.Map;
import com.ruoyi.system.ai.metering.LlmCallCollector;

/**
 * 一轮对话执行过程中入口需要介入的时机。
 * <p>终态语义（emitter.complete / run 状态机）完全不同，故不塞进 {@link ChatTurnRunner}。
 */
public interface ChatTurnCallbacks
{
    /** 每个 ChatResponse 到达时。持久化轨用它检查是否已终态。 */
    default boolean shouldContinue()
    {
        return true;
    }

    /**
     * 流正常 complete 时是否落 ASSISTANT_FINAL。
     * 取消/总时长兜底已终态后 loop 会 empty-complete,不能再写成成功回复。
     * 暂停不是终态,仍要走 onSucceeded。
     */
    default boolean shouldPersistFinalAnswer()
    {
        return true;
    }

    /** USER 消息已精确落库，运行中状态可立即绑定。 */
    default void onRequestMessageRecorded(Long requestMessageId) { }

    /**
     * 成功结束。
     *
     * @param reply        完整回复文本
     * @param usage        本轮用量汇总（落库失败时可能为 null）
     * @param contextUsage 上下文用量快照（供 done 事件）
     */
    void onSucceeded(String reply, LlmCallCollector.TurnUsage usage, Map<String, Object> contextUsage,
                     Long requestMessageId, Long responseMessageId);

    /** 失败。 */
    void onFailed(Throwable error);

    /** 不关心终态的调用方(子智能体)用。 */
    static ChatTurnCallbacks noop()
    {
        return new ChatTurnCallbacks()
        {
            @Override
            public void onSucceeded(String reply, LlmCallCollector.TurnUsage usage,
                                    Map<String, Object> contextUsage,
                                    Long requestMessageId, Long responseMessageId) { }

            @Override
            public void onFailed(Throwable error) { }
        };
    }
}
