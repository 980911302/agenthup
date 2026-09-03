package com.ruoyi.system.ai.agent;

import com.ruoyi.system.domain.AiModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * per-agent 装配结果(§3.1),承载 {@link AgentContextFactory} 装配出的全部产物。
 *
 * <p>{@code systemPrompt} 一次会话内静态;工具列表每次请求实时解析(MCP 掉线时可能变)。
 * {@link #chatModel}/{@link #chatOptions} 供 {@code AgentToolLoop} 使用
 * ({@code internalToolExecutionEnabled=false})。
 *
 * @author ruoyi
 */
public record AgentContext(
        Long agentId,
        String agentCode,
        ChatModel chatModel,
        /** 含 toolCallbacks + internalToolExecutionEnabled(false) */
        ChatOptions chatOptions,
        List<ToolCallback> tools,
        String systemPrompt,
        /** conversationId = sessionId:agentId,子 agent 无状态时为 null(§5.3) */
        String conversationId,
        /** 配置表模型 ID(ai_model)，计量归因用 */
        Long modelId,
        /** 完整模型配置(含 context_window / max_output_tokens)，上下文预算用；可为 null */
        AiModel model,
        /**
         * 该 agent 自己模型的输入预算(token) = contextWindow − maxOutputTokens 系列规则
         * (见 {@code ContextBudget.inputBudget})。按 agent 算是因为子智能体可以用与父
         * 完全不同的模型,窗口上限天然 per-agent;{@code ToolBudget} 注册后按 agent 判定。
         */
        int inputBudget
)
{
    /**
     * 当前模型是否开启推理/思考模式。
     *
     * <p>该值直接来自 {@code ai_model.reasoning_enabled}；它既决定请求侧是否带
     * {@code reasoning_effort}，也决定响应侧是否记录、推送 reasoning 内容。</p>
     */
    public boolean reasoningEnabled()
    {
        return reasoningEnabled(model);
    }

    /** 供装配和单测复用，避免各调用点自行解释 0/1/null。 */
    public static boolean reasoningEnabled(AiModel model)
    {
        return model != null && "1".equals(model.getReasoningEnabled());
    }

    /**
     * 当前 agent 的模型支持哪些输入模态。
     *
     * <p>取代原先的单一 {@code visionEnabled} 布尔 —— 图片/文档/视频/音频是四个互相
     * 独立的能力,不存在包含关系(详见 {@link ModelInputModalities})。模型配置缺失时
     * 返回空集:宁可少发一份媒体,也不能让整轮请求被上游 400 打回。
     */
    public ModelInputModalities inputModalities()
    {
        return inputModalities(model);
    }

    /** 统一口径。历史重建等拿得到 {@link AiModel} 的地方也走这里。 */
    public static ModelInputModalities inputModalities(AiModel model)
    {
        if (model == null)
        {
            return ModelInputModalities.of(null);
        }
        String declared = model.getInputModalities();
        if (declared != null && !declared.isBlank())
        {
            return ModelInputModalities.parse(declared);
        }
        // 迁移期兼容:input_modalities 尚未回填时回退读旧的 vision_enabled,
        // 避免漏跑迁移脚本的库悄悄退化成"所有模型都不认图"。回填后此分支不再命中。
        return "1".equals(model.getVisionEnabled())
            ? ModelInputModalities.parse(ModelInputModalities.IMAGE)
            : ModelInputModalities.of(null);
    }

    /**
     * 当前 agent 的模型是否支持图片。
     *
     * @deprecated 语义已收窄为「支持图片」。判断具体某份媒体能否发送请用
     *             {@link ModelInputModalities#accepts},它还会一并检查传输层是否送得出去。
     */
    @Deprecated
    public boolean visionEnabled()
    {
        return inputModalities().imageEnabled();
    }

    /** @deprecated 见 {@link #visionEnabled()}。 */
    @Deprecated
    public static boolean visionEnabled(AiModel model)
    {
        return inputModalities(model).imageEnabled();
    }
}
