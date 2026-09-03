package com.ruoyi.system.ai.context;

import com.ruoyi.system.ai.memory.TokenEstimator;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 轮内上下文清理:把最旧的工具往返替换为占位摘要,腾出空间让长任务能继续。
 *
 * <p>设计对标 Anthropic clear_tool_uses_20250919;以「assistant(tool_calls)+全部 tool 结果」
 * 为最小清理单位,绝不能从中间切断配对(OpenAI 协议 400)。
 *
 * <p>纯函数语义(无会话状态),便于单测。配置见 {@code ai.chat.context.clean.*}。
 *
 * @author ruoyi
 */
@Component
public class ContextCleaner
{
    /** 超过预算×此比例才开始清 */
    @Value("${ai.chat.context.clean.trigger-ratio:0.6}")
    private double triggerRatio;

    /** 保留最近几对工具往返 */
    @Value("${ai.chat.context.clean.keep:3}")
    private int keep;

    /**
     * 清不到「当前上下文×此比例」就不清(腾出的空间太小,不值得打破缓存前缀)。
     *
     * <p><b>基准是当前上下文,不是 inputBudget</b>:这道闸门问的是「这次清理值不值」,
     * 与窗口多大无关。挂在 inputBudget 上时,100 万窗口算出的门槛(99,180)比整个
     * 上下文(96,387)还高 —— 把所有可清的往返清光也过不去,闸门等于焊死。
     */
    @Value("${ai.chat.context.clean.clear-at-least-ratio:0.1}")
    private double clearAtLeastRatio;

    /**
     * 触发清理的 token 绝对上限,与 trigger-ratio 算出的线取小。&lt;=0 关闭。
     *
     * <p>纯比例制在长窗口模型上等于不设防:100 万窗口 × 0.6 = 59.5 万,
     * 而一次浏览器自动化任务的上下文峰值才 9.6 万 —— 清理一次都不会触发,
     * 51 轮工具往返原样堆着,被完整重发 51 遍(累计 prompt 339 万)。
     * 窗口是「能装多少」不是「该装多少」;计费按每次调用的 prompt 走,与窗口无关。
     */
    @Value("${ai.chat.context.clean.trigger-max-tokens:60000}")
    private int triggerMaxTokens = 60000;

    /**
     * true=同时清工具入参(本项目 writeFile 入参是大头,与 Anthropic 默认相反)。
     */
    @Value("${ai.chat.context.clean.clear-tool-inputs:true}")
    private boolean clearToolInputs;

    /**
     * 永不清理的工具名(逗号分隔)。
     *
     * <p><b>默认排除 {@code loadSkill}</b>:它返回的是技能的操作指令正文,不是可再取的数据。
     * 清成占位后模型不知道该怎么做事,却仍以为自己加载过技能 —— 不是精度损失,是行为损坏。
     * 对标 OpenCode 硬编码的「skill 类工具永不 prune」。
     *
     * <p>其它工具不默认排除:{@code readFile} / 知识库检索这类结果清掉后模型能重新取,
     * 占位里也写明了怎么取回。
     */
    @Value("${ai.chat.context.clean.exclude-tools:loadSkill}")
    private String excludeToolsConfig = "loadSkill";

    @Autowired
    private TokenEstimator tokenEstimator;

    /**
     * 最近一次 clean 的观测快照(ThreadLocal:并发会话互不覆盖)。
     * 无清理时为 null。
     */
    private final ThreadLocal<CleanStats> lastStats = new ThreadLocal<>();

    public CleanStats lastStats()
    {
        return lastStats.get();
    }

    /**
     * @param messages    当前完整消息列表(含 system/历史/本轮)
     * @param inputBudget 输入 token 预算;≤0 时不做清理
     * @return 新列表(可能与入参是同一引用,若无需清理)
     */
    public List<Message> clean(List<Message> messages, int inputBudget)
    {
        lastStats.remove();
        if (messages == null || messages.isEmpty() || inputBudget <= 0)
        {
            return messages;
        }
        int before = estimateTokens(messages);
        int trigger = triggerThreshold(inputBudget);
        if (before <= trigger)
        {
            return messages;
        }

        List<ToolPair> pairs = findToolPairs(messages);
        int keepN = Math.max(keep, 0);
        if (pairs.size() <= keepN)
        {
            return messages;
        }

        Set<String> excluded = parseExclude();
        // 从最旧开始选可清的对,保留最近 keepN 对不动
        //
        // 已清过的对必须跳过:占位后的 assistant 仍带 tool_calls,会被 findToolPairs 再次认成一对。
        // 重清它腾不出任何空间,却会重写它的占位文本 —— 缓存前缀的断点因此被推回最早的那一对,
        // 其后的全部历史一起失配(本该只失配最新清理的那对之后)。
        // 它还会让 wouldFree 把这些"清了也是零"的对算进去,架空下面 clearAtLeast 那道闸门。
        List<ToolPair> candidates = new ArrayList<>();
        int protectedTail = pairs.size() - keepN;
        for (int i = 0; i < protectedTail; i++)
        {
            ToolPair p = pairs.get(i);
            if (!isExcluded(p, excluded) && !isAlreadyCleared(p, messages))
            {
                candidates.add(p);
            }
        }
        if (candidates.isEmpty())
        {
            return messages;
        }

        // 预估清掉 candidates 能腾出多少;不足 clearAtLeast 则放弃。
        // 基准取 before(当前上下文)而非 inputBudget:这道闸门问的是「这次清理值不值」,
        // 与窗口多大无关 —— 详见 clearAtLeastRatio 字段注释。
        int clearAtLeast = (int) Math.floor(before * clampRatio(clearAtLeastRatio, 0.1));
        int wouldFree = 0;
        for (ToolPair p : candidates)
        {
            wouldFree += estimatePairTokens(p, messages);
        }
        if (wouldFree < clearAtLeast)
        {
            return messages;
        }

        // 构建新列表:被清的对换成占位,其余原样
        Set<Integer> clearIndexes = new HashSet<>();
        List<String> clearedIds = new ArrayList<>();
        for (ToolPair p : candidates)
        {
            clearIndexes.add(p.assistantIndex);
            clearIndexes.addAll(p.toolIndexes);
            for (AssistantMessage.ToolCall tc : p.assistant.getToolCalls())
            {
                if (tc.id() != null)
                {
                    clearedIds.add(tc.id());
                }
            }
        }

        List<Message> out = new ArrayList<>(messages.size());
        for (int i = 0; i < messages.size(); i++)
        {
            if (!clearIndexes.contains(i))
            {
                out.add(messages.get(i));
                continue;
            }
            // 只在 assistant 位置写一对占位(assistant 摘要 + 合成 tool 结果),跳过原 tool 行
            Message m = messages.get(i);
            if (m instanceof AssistantMessage am && am.hasToolCalls())
            {
                ToolPair pair = findPairByAssistant(candidates, i);
                out.add(toPlaceholderAssistant(am));
                out.add(toPlaceholderTools(am, pair, messages));
            }
            // tool 行:若已随 assistant 输出占位则跳过
        }

        int after = estimateTokens(out);
        lastStats.set(new CleanStats(before, after, candidates.size(), wouldFree, clearedIds));
        return out;
    }

    // ---- 配对发现 ----

    /**
     * 从前往后扫描:每个带 tool_calls 的 assistant,吞掉紧随其后的连续 ToolResponseMessage。
     */
    /**
     * 触发线:比例线与绝对上限取小,绝对上限 &lt;=0 视为不限。
     * 包级可见,便于单测直接断言这条线。
     */
    int triggerThreshold(int inputBudget)
    {
        int byRatio = (int) Math.floor(inputBudget * clampRatio(triggerRatio, 0.6));
        return triggerMaxTokens > 0 ? Math.min(byRatio, triggerMaxTokens) : byRatio;
    }

    static List<ToolPair> findToolPairs(List<Message> messages)
    {
        List<ToolPair> pairs = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++)
        {
            Message m = messages.get(i);
            if (!(m instanceof AssistantMessage am) || !am.hasToolCalls())
            {
                continue;
            }
            List<Integer> toolIdx = new ArrayList<>();
            int j = i + 1;
            while (j < messages.size() && messages.get(j) instanceof ToolResponseMessage)
            {
                toolIdx.add(j);
                j++;
            }
            pairs.add(new ToolPair(i, toolIdx, am));
            // 不把 i 跳到 j-1:下一轮 for 会 +1;tool 行不会被当成新 pair 起点
        }
        return pairs;
    }

    private static ToolPair findPairByAssistant(List<ToolPair> pairs, int assistantIndex)
    {
        for (ToolPair p : pairs)
        {
            if (p.assistantIndex == assistantIndex)
            {
                return p;
            }
        }
        return null;
    }

    /**
     * 该对是否已经被清理过 —— 全部工具结果都是占位文本。
     *
     * <p>没有工具结果行时返回 false,保持原有行为(此时占位构造会补出结果行)。
     */
    private static boolean isAlreadyCleared(ToolPair p, List<Message> messages)
    {
        boolean seen = false;
        for (int ti : p.toolIndexes)
        {
            if (!(messages.get(ti) instanceof ToolResponseMessage trm))
            {
                return false;
            }
            for (ToolResponseMessage.ToolResponse r : trm.getResponses())
            {
                String data = r.responseData();
                if (!ToolPlaceholders.isPlaceholder(data))
                {
                    return false;
                }
                seen = true;
            }
        }
        return seen;
    }

    private boolean isExcluded(ToolPair p, Set<String> excluded)
    {
        if (excluded.isEmpty())
        {
            return false;
        }
        for (AssistantMessage.ToolCall tc : p.assistant.getToolCalls())
        {
            if (tc.name() != null && excluded.contains(tc.name()))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * 「永不清理」的工具名集合,供 {@link ContextCompactor} 共用同一份配置。
     *
     * <p>清理层与压缩层必须认同一份名单:清理层特意保住的技能规则,若在压缩层被摘要成
     * 一句话,模型照样落进「以为自己加载过技能、其实规则没了」的行为损坏 —— 只是换了个入口。
     */
    public Set<String> excludedToolNames()
    {
        return parseExclude();
    }

    private Set<String> parseExclude()
    {
        Set<String> set = new HashSet<>();
        if (excludeToolsConfig == null || excludeToolsConfig.isBlank())
        {
            return set;
        }
        for (String p : excludeToolsConfig.split(","))
        {
            String t = p.trim();
            if (!t.isEmpty())
            {
                set.add(t);
            }
        }
        return set;
    }

    // ---- 占位构造 ----

    private AssistantMessage toPlaceholderAssistant(AssistantMessage original)
    {
        if (!clearToolInputs)
        {
            // 只清结果时保留原 tool_calls(含完整入参)
            return original;
        }
        List<AssistantMessage.ToolCall> slim = new ArrayList<>();
        for (AssistantMessage.ToolCall tc : original.getToolCalls())
        {
            String summaryArgs = summarizeArgs(tc.name(), tc.arguments());
            slim.add(new AssistantMessage.ToolCall(tc.id(), tc.type(), tc.name(), summaryArgs));
        }
        String text = original.getText() != null ? original.getText() : "";
        return AssistantMessage.builder()
                .content(text)
                .toolCalls(slim)
                .build();
    }

    private ToolResponseMessage toPlaceholderTools(AssistantMessage am, ToolPair pair,
                                                   List<Message> messages)
    {
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        List<AssistantMessage.ToolCall> calls = am.getToolCalls();
        for (int c = 0; c < calls.size(); c++)
        {
            AssistantMessage.ToolCall tc = calls.get(c);
            String originalResult = "";
            if (pair != null && c < pair.toolIndexes.size())
            {
                Message tm = messages.get(pair.toolIndexes.get(c));
                if (tm instanceof ToolResponseMessage trm && !trm.getResponses().isEmpty())
                {
                    // 尽量按 id 匹配
                    originalResult = matchResponse(trm, tc.id());
                }
            }
            // 也可能一对多 tool 结果在同一 ToolResponseMessage 里
            if (originalResult.isEmpty() && pair != null)
            {
                for (int ti : pair.toolIndexes)
                {
                    if (messages.get(ti) instanceof ToolResponseMessage trm)
                    {
                        String hit = matchResponse(trm, tc.id());
                        if (!hit.isEmpty())
                        {
                            originalResult = hit;
                            break;
                        }
                    }
                }
            }
            responses.add(new ToolResponseMessage.ToolResponse(
                    tc.id(), tc.name(), buildPlaceholder(tc.name(), tc.arguments(), originalResult)));
        }
        return ToolResponseMessage.builder().responses(responses).build();
    }

    private static String matchResponse(ToolResponseMessage trm, String id)
    {
        for (ToolResponseMessage.ToolResponse r : trm.getResponses())
        {
            if (id != null && id.equals(r.id()))
            {
                return r.responseData() != null ? r.responseData() : "";
            }
        }
        if (trm.getResponses().size() == 1)
        {
            String d = trm.getResponses().get(0).responseData();
            return d != null ? d : "";
        }
        return "";
    }

    /** 委托 {@link ToolPlaceholders},保留本类静态入口以免调用方四处改。 */
    static String buildPlaceholder(String toolName, String args, String originalResult)
    {
        return ToolPlaceholders.buildPlaceholder(toolName, args, originalResult);
    }

    static String summarizeArgs(String toolName, String args)
    {
        return ToolPlaceholders.summarizeArgs(toolName, args);
    }

    // ---- 估算 ----

    int estimateTokens(List<Message> messages)
    {
        int sum = 0;
        for (Message m : messages)
        {
            sum += estimateMessage(m);
        }
        return sum;
    }

    private int estimatePairTokens(ToolPair p, List<Message> messages)
    {
        int sum = estimateMessage(messages.get(p.assistantIndex));
        for (int ti : p.toolIndexes)
        {
            sum += estimateMessage(messages.get(ti));
        }
        return sum;
    }

    private int estimateMessage(Message m)
    {
        int n = 0;
        if (m.getText() != null)
        {
            n += tokenEstimator.estimate(m.getText());
        }
        if (m instanceof AssistantMessage am && am.hasToolCalls())
        {
            for (AssistantMessage.ToolCall tc : am.getToolCalls())
            {
                if (tc.name() != null)
                {
                    n += tokenEstimator.estimate(tc.name());
                }
                if (tc.arguments() != null)
                {
                    n += tokenEstimator.estimate(tc.arguments());
                }
            }
        }
        if (m instanceof ToolResponseMessage tr)
        {
            for (ToolResponseMessage.ToolResponse r : tr.getResponses())
            {
                if (r.responseData() != null)
                {
                    n += tokenEstimator.estimate(r.responseData());
                }
            }
        }
        // system/user 永不进清理候选,但仍计入总量
        if (m instanceof SystemMessage || m instanceof UserMessage)
        {
            // already counted text
        }
        return n;
    }

    private static double clampRatio(double ratio, double fallback)
    {
        if (ratio <= 0 || ratio >= 1)
        {
            return fallback;
        }
        return ratio;
    }

    /** 可测:一对工具往返在列表中的位置。 */
    static final class ToolPair
    {
        final int assistantIndex;
        final List<Integer> toolIndexes;
        final AssistantMessage assistant;

        ToolPair(int assistantIndex, List<Integer> toolIndexes, AssistantMessage assistant)
        {
            this.assistantIndex = assistantIndex;
            this.toolIndexes = toolIndexes;
            this.assistant = assistant;
        }
    }

    /**
     * @param clearedToolCallIds 本次被清掉的 tool_call_id,供调用方把 pruned 标记落库。
     *                           不落库的话下一轮从库里重建又是原文,占位落点每轮漂移,
     *                           缓存前缀在不同位置反复断裂 —— 清理反而变成缓存杀手。
     */
    public record CleanStats(int tokensBefore, int tokensAfter, int pairsCleared,
                             int estimatedFreed, List<String> clearedToolCallIds)
    {
    }
}
