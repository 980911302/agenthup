package com.ruoyi.system.ai.run;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.content.Media;
import org.springframework.ai.model.tool.internal.ToolCallReactiveContextHolder;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import com.ruoyi.system.tool.PromptMediaBuffer;

/**
 * 并行工具调用管理器：模型一次返回多个 tool_calls 时并发执行，按原顺序回填结果。
 *
 * <p>对照 Spring AI Alibaba {@code AgentToolNode} 的并行设计：
 * <ul>
 *   <li>{@code Semaphore} 限制最大并发工具数（maxParallelTools）</li>
 *   <li>每个工具独立超时（toolExecutionTimeout），超时填错误 ToolResponse 而非中断整轮</li>
 *   <li>结果按 toolCall 原始顺序归位（模型按 toolCallId 匹配，顺序不能乱）</li>
 *   <li>调用线程的 {@link ToolCallReactiveContextHolder} 上下文复制进每个并行子线程，
 *       工具回调（SubAgentToolCallback / RecordingToolCallback）在子线程也能取到</li>
 * </ul>
 *
 * <p>注意：工具执行异常沿用 {@link ToolExecutionExceptionProcessor} 转成错误文本回填，
 * 让模型看到错误信息自行纠错，与官方 {@code DefaultToolCallingManager} 行为一致。
 *
 * <p>单工具与多工具走同一套超时：{@code orTimeout} 命中后 cancel 原任务，
 * 用 CAS 写入错误响应，避免超时后成功结果盖掉错误。
 */
public class ParallelToolCallingManager implements ToolCallingManager
{
    private static final Logger log = LoggerFactory.getLogger(ParallelToolCallingManager.class);

    private final ToolCallbackResolver toolCallbackResolver;
    private final ToolExecutionExceptionProcessor toolExecutionExceptionProcessor;
    private final Executor executor;
    private final int maxParallelTools;
    private final Duration toolExecutionTimeout;
    /** 同轮仅 1 个 tool_call 时的超时;须大于子 agent 合法总时长。 */
    private final Duration singleToolTimeout;

    public ParallelToolCallingManager(ToolCallbackResolver toolCallbackResolver,
                                      ToolExecutionExceptionProcessor toolExecutionExceptionProcessor,
                                      Executor executor,
                                      int maxParallelTools,
                                      Duration toolExecutionTimeout)
    {
        this(toolCallbackResolver, toolExecutionExceptionProcessor, executor,
                maxParallelTools, toolExecutionTimeout, toolExecutionTimeout);
    }

    public ParallelToolCallingManager(ToolCallbackResolver toolCallbackResolver,
                                      ToolExecutionExceptionProcessor toolExecutionExceptionProcessor,
                                      Executor executor,
                                      int maxParallelTools,
                                      Duration toolExecutionTimeout,
                                      Duration singleToolTimeout)
    {
        this.toolCallbackResolver = toolCallbackResolver;
        this.toolExecutionExceptionProcessor = toolExecutionExceptionProcessor;
        this.executor = executor;
        this.maxParallelTools = Math.max(1, maxParallelTools);
        this.toolExecutionTimeout = toolExecutionTimeout != null && !toolExecutionTimeout.isZero()
                ? toolExecutionTimeout : Duration.ofSeconds(30);
        this.singleToolTimeout = singleToolTimeout != null && !singleToolTimeout.isZero()
                ? singleToolTimeout : this.toolExecutionTimeout;
    }

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions options)
    {
        List<ToolCallback> toolCallbacks = options.getToolCallbacks();
        if (toolCallbacks == null || toolCallbacks.isEmpty())
        {
            return List.of();
        }
        return toolCallbacks.stream().map(ToolCallback::getToolDefinition).toList();
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse response)
    {
        Assert.notNull(prompt, "prompt cannot be null");
        Assert.notNull(response, "chatResponse cannot be null");

        Generation generation = response.getResults().stream()
                .filter(g -> g.getOutput() != null
                        && g.getOutput().getToolCalls() != null
                        && !g.getOutput().getToolCalls().isEmpty())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No tool call requested by the chat model"));
        AssistantMessage assistantMessage = generation.getOutput();
        ToolContext toolContext = buildToolContext(prompt, assistantMessage);
        List<AssistantMessage.ToolCall> toolCalls = assistantMessage.getToolCalls();
        Duration timeout = toolCalls.size() <= 1 ? singleToolTimeout : toolExecutionTimeout;

        // 调用线程的 ReactiveContext，复制进每个并行子线程
        reactor.util.context.ContextView ctxView = ToolCallReactiveContextHolder.getContext();
        AtomicReferenceArray<ToolResponseMessage.ToolResponse> responses =
                new AtomicReferenceArray<>(toolCalls.size());
        // 子线程产出的 prompt media(如 captureScreenshot 的截图)必须收回调用线程。
        // PromptMediaBuffer 是 ThreadLocal：写在子线程、而 AgentToolLoop 在调用线程 drain，
        // 不显式回收的话图片会被静默丢弃 —— 模型看不到截图，等于工具没做。
        // 与 ctxView 的方向相反：那个是往下复制，这个是往上回收。
        AtomicReferenceArray<List<Media>> medias = new AtomicReferenceArray<>(toolCalls.size());
        AtomicBoolean returnDirect = new AtomicBoolean(false);
        Semaphore semaphore = new Semaphore(maxParallelTools);

        List<CompletableFuture<Void>> futures = new ArrayList<>(toolCalls.size());
        for (int i = 0; i < toolCalls.size(); i++)
        {
            final int idx = i;
            AssistantMessage.ToolCall toolCall = toolCalls.get(i);
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                if (!acquireSlot(semaphore, idx, toolCall, responses))
                {
                    return;
                }
                try
                {
                    if (ctxView != null)
                    {
                        ToolCallReactiveContextHolder.setContext(ctxView);
                    }
                    try
                    {
                        responses.compareAndSet(idx, null, executeToolCall(prompt, assistantMessage,
                                toolContext, toolCall, returnDirect));
                    }
                    finally
                    {
                        // 放在 finally：工具抛异常时也要把已产出的图片收走，
                        // 顺带清掉本线程 ThreadLocal，避免线程池复用时串给下一个批次。
                        medias.set(idx, PromptMediaBuffer.drain());
                        ToolCallReactiveContextHolder.clearContext();
                    }
                }
                finally
                {
                    semaphore.release();
                }
            }, executor);
            // 必须等待 orTimeout 之后的 future,否则 allOf 仍卡在原始 runAsync 上。
            // 超时 cancel 原任务,并用 CAS 填错误,禁止稍后成功结果盖掉超时。
            CompletableFuture<Void> timed = future
                    .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                    .exceptionally(ex -> {
                        future.cancel(true);
                        if (responses.compareAndSet(idx, null, errorResponse(toolCall,
                                "Tool execution failed: " + timeoutMessage(ex, timeout))))
                        {
                            log.warn("工具并行执行超时或失败 name={}: {}", toolCall.name(), ex.getMessage());
                        }
                        return null;
                    });
            futures.add(timed);
        }

        // 等全部结束：最坏等待 = 单工具超时 + 少量余量
        try
        {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(timeout.toMillis() + 5000, TimeUnit.MILLISECONDS);
        }
        catch (Exception e)
        {
            log.warn("并行工具整体等待异常，取消未完成工具: {}", e.getMessage());
            futures.forEach(f -> f.cancel(true));
            for (int i = 0; i < toolCalls.size(); i++)
            {
                if (responses.get(i) == null)
                {
                    responses.set(i, errorResponse(toolCalls.get(i), "Tool execution interrupted"));
                }
            }
        }

        // 把各子线程收上来的图片按 toolCall 原顺序合并回调用线程，
        // AgentToolLoop 随后在调用线程 drain，注入下一轮消息。
        for (int i = 0; i < toolCalls.size(); i++)
        {
            PromptMediaBuffer.addAll(medias.get(i));
        }

        // 按原始顺序归位，保证模型按 toolCallId 正确匹配
        List<ToolResponseMessage.ToolResponse> ordered = IntStream.range(0, toolCalls.size())
                .mapToObj(i -> {
                    ToolResponseMessage.ToolResponse filled = responses.get(i);
                    return filled != null ? filled
                            : errorResponse(toolCalls.get(i), "Tool execution interrupted");
                })
                .toList();
        ToolResponseMessage toolResponseMessage = ToolResponseMessage.builder()
                .responses(ordered)
                .build();
        return ToolExecutionResult.builder()
                .conversationHistory(buildConversationHistoryAfterToolExecution(
                        prompt.getInstructions(), assistantMessage, toolResponseMessage))
                .returnDirect(returnDirect.get())
                .build();
    }

    private boolean acquireSlot(Semaphore semaphore, int idx,
                                AssistantMessage.ToolCall toolCall,
                                AtomicReferenceArray<ToolResponseMessage.ToolResponse> responses)
    {
        try
        {
            semaphore.acquire();
            return true;
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            responses.set(idx, errorResponse(toolCall, "Interrupted while waiting for tool slot"));
            return false;
        }
    }

    private String timeoutMessage(Throwable ex, Duration timeout)
    {
        Throwable cause = ex;
        while (cause.getCause() != null && cause.getCause() != cause)
        {
            cause = cause.getCause();
        }
        String msg = cause.getMessage();
        if (!StringUtils.hasText(msg))
        {
            msg = cause.getClass().getSimpleName();
        }
        return msg + " (timeout " + timeout.toMillis() + "ms)";
    }

    private ToolResponseMessage.ToolResponse executeToolCall(Prompt prompt,
                                                             AssistantMessage assistantMessage,
                                                             ToolContext toolContext,
                                                             AssistantMessage.ToolCall toolCall,
                                                             AtomicBoolean returnDirect)
    {
        String toolName = toolCall.name();
        String arguments = toolCall.arguments();
        if (!StringUtils.hasText(arguments))
        {
            log.warn("Tool call arguments are null or empty for tool: {}. Using empty JSON object as default.", toolName);
            arguments = "{}";
        }

        ToolCallback toolCallback = resolveToolCallback(prompt, toolName);
        if (toolCallback == null)
        {
            log.warn("LLM may have adapted the tool name '{}', especially if the name was truncated "
                    + "due to length limits. If this is the case, please retry with a different model.", toolName);
            return new ToolResponseMessage.ToolResponse(toolCall.id(), toolName,
                    "Error: The tool '" + toolName + "' does not exist. Do not try to use this tool again.");
        }
        if (toolCallback.getToolMetadata().returnDirect())
        {
            returnDirect.set(true);
        }
        String result;
        try
        {
            result = toolCallback.call(arguments, toolContext);
        }
        catch (Exception e)
        {
            String err = toolExecutionExceptionProcessor.process(
                    new ToolExecutionException(toolCallback.getToolDefinition(), e));
            log.debug("工具 {} 执行异常: {}", toolName, e.getMessage());
            return new ToolResponseMessage.ToolResponse(toolCall.id(), toolName, err);
        }
        return new ToolResponseMessage.ToolResponse(toolCall.id(), toolName, result == null ? "" : result);
    }

    private ToolCallback resolveToolCallback(Prompt prompt, String toolName)
    {
        ChatOptions options = prompt.getOptions();
        if (options instanceof ToolCallingChatOptions toolCallingOptions)
        {
            List<ToolCallback> callbacks = toolCallingOptions.getToolCallbacks();
            if (callbacks != null)
            {
                for (ToolCallback cb : callbacks)
                {
                    if (toolName.equals(cb.getToolDefinition().name()))
                    {
                        return cb;
                    }
                }
            }
        }
        // 兜底：静态解析器（与官方 DefaultToolCallingManager 的 fallback 一致）
        return toolCallbackResolver.resolve(toolName);
    }

    private ToolResponseMessage.ToolResponse errorResponse(AssistantMessage.ToolCall toolCall, String message)
    {
        return new ToolResponseMessage.ToolResponse(toolCall.id(), toolCall.name(), "Error: " + message);
    }

    private ToolContext buildToolContext(Prompt prompt, AssistantMessage assistantMessage)
    {
        Map<String, Object> context = new HashMap<>();
        ChatOptions options = prompt.getOptions();
        if (options instanceof ToolCallingChatOptions toolCallingOptions
                && toolCallingOptions.getToolContext() != null
                && !toolCallingOptions.getToolContext().isEmpty())
        {
            context.putAll(toolCallingOptions.getToolContext());
        }
        context.put(ToolContext.TOOL_CALL_HISTORY,
                buildConversationHistoryBeforeToolExecution(prompt, assistantMessage));
        return new ToolContext(context);
    }

    private List<Message> buildConversationHistoryBeforeToolExecution(Prompt prompt,
                                                                      AssistantMessage assistantMessage)
    {
        List<Message> history = new ArrayList<>(prompt.copy().getInstructions());
        history.add(AssistantMessage.builder()
                .content(assistantMessage.getText())
                .properties(assistantMessage.getMetadata())
                .toolCalls(assistantMessage.getToolCalls())
                .build());
        return history;
    }

    private List<Message> buildConversationHistoryAfterToolExecution(List<Message> instructions,
                                                                     AssistantMessage assistantMessage,
                                                                     ToolResponseMessage toolResponseMessage)
    {
        List<Message> history = new ArrayList<>(instructions);
        history.add(assistantMessage);
        history.add(toolResponseMessage);
        return history;
    }
}
