package com.ruoyi.system.ai.context;

import com.ruoyi.system.ai.memory.TokenEstimator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 发给上游之前的最后一道防线:按整轮丢弃最旧历史,直到不超输入预算。
 *
 * <p>压缩可能失败或无法收敛,轮内清理也只处理工具往返。此时宁可丢掉最早对话,
 * 也不能持续发送超长 prompt 导致会话永久 400。每轮从 {@link UserMessage} 开始,
 * 因而 assistant(tool_calls) 与其 tool 结果不会被劈开。
 */
@Component
public class ContextOverflowGuard
{
    private static final Logger log = LoggerFactory.getLogger(ContextOverflowGuard.class);

    @Autowired
    private TokenEstimator tokenEstimator;

    /**
     * 只为共用「永不清理」的工具名单({@code ai.chat.context.clean.exclude-tools},默认 loadSkill)。
     *
     * <p>三层上下文控制必须认同一份名单:清理层跳过它、压缩层原文保留它,
     * 兜底层却整轮丢掉,等于前两层白做 —— 模型仍会落进「以为自己加载过技能、规则却没了」。
     *
     * <p>required=false:裸 new 的单测实例退化成「没有豁免工具」,即旧行为。
     */
    @Autowired(required = false)
    private ContextCleaner contextCleaner;

    /** 最近一次裁剪快照;ThreadLocal 保证并发会话互不覆盖。 */
    private final ThreadLocal<TrimStats> lastStats = new ThreadLocal<>();

    public TrimStats lastStats()
    {
        return lastStats.get();
    }

    /**
     * 不超预算时返回原列表引用;超预算时最多丢到只剩最后一轮用户输入。
     */
    public List<Message> enforce(List<Message> messages, int inputBudget)
    {
        lastStats.remove();
        if (messages == null || messages.isEmpty() || inputBudget <= 0)
        {
            return messages;
        }
        int before = estimateTokens(messages);
        if (before <= inputBudget)
        {
            return messages;
        }

        List<Integer> turnStarts = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++)
        {
            if (messages.get(i) instanceof UserMessage)
            {
                turnStarts.add(i);
            }
        }
        if (turnStarts.size() <= 1)
        {
            log.warn("上下文超预算但只剩一轮,无法裁剪: tokens={} budget={}", before, inputBudget);
            return messages;
        }

        int headEnd = turnStarts.get(0);
        Set<String> preserved = preservedToolNames();

        // 第一遍:只丢「不含豁免工具结果」的轮次,含技能规则的整轮原地留下。
        // 保留整轮而不是只捞出 assistant+tool 那一对,是为了不改变消息序列的形状 ——
        // 单捞一对会让序列变成 [system][assistant][tool][user...],部分上游对首条非 system
        // 消息的角色有要求,得不偿失。
        for (int dropped = 1; dropped < turnStarts.size(); dropped++)
        {
            List<Message> candidate = buildCandidate(messages, turnStarts, headEnd, dropped, preserved);
            int after = estimateTokens(candidate);
            if (after <= inputBudget)
            {
                log.warn("上下文超预算,已丢弃最早 {} 轮对话(含技能规则的轮次已保留): {} -> {} tokens (预算 {})",
                        dropped, before, after, inputBudget);
                lastStats.set(new TrimStats(before, after, dropped));
                return candidate;
            }
        }

        // 第二遍:保留豁免轮之后仍然超预算 —— 兜底层的第一职责是让请求发得出去,
        // 此时连技能规则一起丢,退回原来的「按整轮丢到只剩最后一轮」。
        for (int dropped = 1; dropped < turnStarts.size(); dropped++)
        {
            List<Message> candidate = new ArrayList<>(messages.size());
            candidate.addAll(messages.subList(0, headEnd));
            candidate.addAll(messages.subList(turnStarts.get(dropped), messages.size()));
            int after = estimateTokens(candidate);
            if (after <= inputBudget || dropped == turnStarts.size() - 1)
            {
                log.warn("上下文超预算且保留技能规则后仍超,已丢弃最早 {} 轮(含技能规则): {} -> {} tokens (预算 {})",
                        dropped, before, after, inputBudget);
                lastStats.set(new TrimStats(before, after, dropped));
                return candidate;
            }
        }
        return messages;
    }

    /**
     * 丢弃 {@code [1, dropped)} 这些轮次,但其中含豁免工具结果的轮次原地保留。
     *
     * <p>保留的轮次仍按原顺序排在前面,序列形状不变({@code [system][user...][user...]}),
     * assistant(tool_calls) 与其 tool 结果也始终成对 —— 劈开会让上游直接 400。
     */
    private List<Message> buildCandidate(List<Message> messages, List<Integer> turnStarts,
                                         int headEnd, int dropped, Set<String> preserved)
    {
        List<Message> candidate = new ArrayList<>(messages.size());
        candidate.addAll(messages.subList(0, headEnd));
        for (int turn = 0; turn < dropped; turn++)
        {
            int from = turnStarts.get(turn);
            int to = turn + 1 < turnStarts.size() ? turnStarts.get(turn + 1) : messages.size();
            List<Message> slice = messages.subList(from, to);
            if (containsPreservedTool(slice, preserved))
            {
                candidate.addAll(slice);
            }
        }
        candidate.addAll(messages.subList(turnStarts.get(dropped), messages.size()));
        return candidate;
    }

    private static boolean containsPreservedTool(List<Message> slice, Set<String> preserved)
    {
        if (preserved.isEmpty())
        {
            return false;
        }
        for (Message m : slice)
        {
            if (m instanceof ToolResponseMessage toolResponse)
            {
                for (ToolResponseMessage.ToolResponse r : toolResponse.getResponses())
                {
                    if (r.name() != null && preserved.contains(r.name()))
                    {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 与清理层共用的「永不清理」工具名单;未注入 cleaner 时退化成空集(旧行为)。 */
    private Set<String> preservedToolNames()
    {
        return contextCleaner == null ? Set.of() : contextCleaner.excludedToolNames();
    }

    private int estimateTokens(List<Message> messages)
    {
        int sum = 0;
        for (Message message : messages)
        {
            sum += estimateMessage(message);
        }
        return sum;
    }

    /** 与 ContextCleaner 采用相同口径:文本、tool_calls 名称与参数、工具响应。 */
    private int estimateMessage(Message message)
    {
        int tokens = 0;
        if (message.getText() != null)
        {
            tokens += tokenEstimator.estimate(message.getText());
        }
        if (message instanceof AssistantMessage assistant && assistant.hasToolCalls())
        {
            for (AssistantMessage.ToolCall toolCall : assistant.getToolCalls())
            {
                if (toolCall.name() != null)
                {
                    tokens += tokenEstimator.estimate(toolCall.name());
                }
                if (toolCall.arguments() != null)
                {
                    tokens += tokenEstimator.estimate(toolCall.arguments());
                }
            }
        }
        if (message instanceof ToolResponseMessage toolResponse)
        {
            for (ToolResponseMessage.ToolResponse response : toolResponse.getResponses())
            {
                if (response.responseData() != null)
                {
                    tokens += tokenEstimator.estimate(response.responseData());
                }
            }
        }
        return tokens;
    }

    /** @param turnsDropped 丢弃的轮数,以用户消息计一轮。 */
    public record TrimStats(int tokensBefore, int tokensAfter, int turnsDropped)
    {
    }
}
