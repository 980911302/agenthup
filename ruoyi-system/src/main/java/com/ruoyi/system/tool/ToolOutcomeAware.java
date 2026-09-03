package com.ruoyi.system.tool;

/**
 * 工具回调可选实现的逻辑成败声明接口。
 *
 * <p>当工具本次 {@code call()} 以返回文本表达失败(非零退出、超时、安全拒绝等)
 * 而不抛异常时,实现本接口让外层 {@link RecordingToolCallback} 把失败写进
 * {@code tool_end.ok} 与 {@code ai_chat_message.tool_success},供前端红勾与统计使用。
 *
 * <p>不强制所有工具实现 -- 普通工具不实现,外层按 instanceof 判定,
 * 返回 {@code null} 或未实现时按「未抛异常即成功」。
 *
 * <p>失败仍必须把原文交给模型,不要改抛异常(会走 finishError 把正文换成 ERROR:)。
 *
 * @author ruoyi
 */
public interface ToolOutcomeAware
{
    /**
     * 最近一次 call() 是否逻辑成功。
     *
     * @return true 成功;false 失败;null 表示不表态,由外层按「未抛异常即成功」判定
     */
    Boolean lastCallOk();
}
