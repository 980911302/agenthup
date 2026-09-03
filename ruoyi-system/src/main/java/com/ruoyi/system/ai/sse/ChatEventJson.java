package com.ruoyi.system.ai.sse;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.system.tool.ToolAttachment;

import java.util.List;
import java.util.Map;

/**
 * SSE 事件 JSON 构造(§7.1)。每个方法返回可直接 {@code emitter.send(data(json))} 的 JSON 字符串。
 *
 * <p>事件类型(与前端 types/chat.js 的 EVENT_TYPES 对齐):
 * <ul>
 *   <li>{@code text} - 最终回答流式 chunk</li>
 *   <li>{@code reasoning} - 思考流式 chunk</li>
 *   <li>{@code tool_start}/{@code tool_end} - 工具调用开始/结束</li>
 *   <li>{@code tool_confirm_required} - 危险工具等待用户确认</li>
 *   <li>{@code tool_call_request} - 渠道工具请求客户端本地执行</li>
 *   <li>{@code context_cleaned} - 轮内上下文精简</li>
 *   <li>{@code media_gated} - 本轮媒体的门控结果(送入 / 被拦下及原因)</li>
 *   <li>{@code agent_start}/{@code agent_end} - 子智能体调用开始/结束</li>
 *   <li>{@code done} - 本轮全部结束</li>
 *   <li>{@code error} - 出错</li>
 *   <li>{@code ui} - 只给前端的 UI 产物(如知识库引用),不进 LLM 历史</li>
 * </ul>
 *
 * @author ruoyi
 */
public final class ChatEventJson
{
    private ChatEventJson() {}

    public static String text(String text)
    {
        return text(text, null, "answer");
    }

    public static String reasoning(String text)
    {
        return reasoning(text, null);
    }

    /**
     * 思考 chunk。{@code owner} 非空时表示该 chunk 产生于某个子智能体内部,
     * 前端据此把它嵌进对应的 agent step(而非顶层思考区)。
     */
    public static String reasoning(String text, String owner)
    {
        return reasoning(text, owner == null ? "reasoning:main" : "reasoning:" + owner, owner);
    }

    public static String reasoning(String text, String stepId, String parentStepId)
    {
        JSONObject o = new JSONObject();
        o.put("type", "reasoning");
        o.put("text", text);
        putOwner(o, parentStepId);
        putStep(o, stepId, parentStepId);
        return o.toJSONString();
    }

    public static String toolStart(String name, String source)
    {
        return toolStart(name, source, null, null);
    }

    public static String toolStart(String name, String source, String owner)
    {
        return toolStart(name, source, owner, null);
    }

    /**
     * 工具开始事件。
     *
     * <p><b>args 必须在这里就带上</b>:只发工具名的话,前端在执行期间只能显示一个空壳
     * (ToolStep 的入参/返回两个区块都是 {@code v-if},全空时整个 body 不渲染),
     * 用户看得到"在跑",却不知道在跑什么 —— 长耗时工具尤其难受。
     * 带上入参,执行中就能显示「readFile(path=…)」。
     *
     * <p>与 {@link #toolEnd} 一样传<b>脱敏后</b>的 args:同一次调用先后两个事件若一个脱敏
     * 一个不脱敏,前端会在结束瞬间看到入参内容跳变。
     *
     * @param args 脱敏后的工具入参(可空,空时不写该字段)
     */
    public static String toolStart(String name, String source, String owner, String args)
    {
        return toolStart(name, source, owner, args, null);
    }

    public static String toolStart(String name, String source, String owner, String args, String stepId)
    {
        JSONObject o = new JSONObject();
        o.put("type", "tool_start");
        o.put("name", name);
        o.put("source", source);
        putOwner(o, owner);
        putStep(o, stepId, owner);
        if (args != null && !args.isEmpty())
        {
            o.put("args", args);
        }
        return o.toJSONString();
    }

    /**
     * 危险工具需人工确认。前端弹确认框后调 {@code POST /ai/chat/run/{runId}/tool-confirm}。
     */
    public static String toolConfirmRequired(String confirmId, String name, String source,
                                             String args, String owner)
    {
        return toolConfirmRequired(confirmId, name, source, args, owner, null);
    }

    public static String toolConfirmRequired(String confirmId, String name, String source,
                                             String args, String owner, String stepId)
    {
        JSONObject o = new JSONObject();
        o.put("type", "tool_confirm_required");
        o.put("confirmId", confirmId);
        o.put("name", name);
        o.put("source", source);
        o.put("args", args);
        putOwner(o, owner);
        putStep(o, stepId, owner);
        return o.toJSONString();
    }

    /**
     * 渠道工具请求客户端执行。前端按 callId 跑本地 handler 后回 {@code chat.tool.result}。
     */
    public static String toolCallRequest(String callId, String name, String args,
                                         String owner, String stepId)
    {
        JSONObject o = new JSONObject();
        o.put("type", "tool_call_request");
        o.put("callId", callId);
        o.put("name", name);
        o.put("args", args);
        putOwner(o, owner);
        putStep(o, stepId, owner);
        return o.toJSONString();
    }

    /**
     * 轮内上下文清理完成。前端可提示「已精简早期工具记录」。
     */
    public static String contextCleaned(int tokensBefore, int tokensAfter, int pairsCleared)
    {
        JSONObject o = new JSONObject();
        o.put("type", "context_cleaned");
        o.put("tokensBefore", tokensBefore);
        o.put("tokensAfter", tokensAfter);
        o.put("pairsCleared", pairsCleared);
        return o.toJSONString();
    }

    /**
     * 本轮媒体的门控结果。
     *
     * <p>工具返回的 "loaded into your context" 是工具层的乐观陈述 —— 工具不持有模型配置,
     * 判定发生在 {@code AgentToolLoop.appendPromptMedia}。此前那次判定不留任何痕迹:
     * 不落库、不进 trace、前端看不见,想确认一张图到底进没进模型,只能去读模型的回答反推。
     * 本事件把判定结果显式暴露出来。
     *
     * @param accepted 送进模型的媒体份数
     * @param rejected 被拦下的明细,每项含 modality / label / count / reason
     */
    public static String mediaGated(int accepted, List<Map<String, Object>> rejected)
    {
        JSONObject o = new JSONObject();
        o.put("type", "media_gated");
        o.put("accepted", accepted);
        JSONArray arr = new JSONArray();
        if (rejected != null)
        {
            arr.addAll(rejected);
        }
        o.put("rejected", arr);
        return o.toJSONString();
    }

    /** 上下文超预算,已丢弃最早若干轮对话;前端应显著提示用户。 */
    public static String contextOverflowTrimmed(int tokensBefore, int tokensAfter, int turnsDropped)
    {
        JSONObject o = new JSONObject();
        o.put("type", "context_overflow_trimmed");
        o.put("tokensBefore", tokensBefore);
        o.put("tokensAfter", tokensAfter);
        o.put("turnsDropped", turnsDropped);
        return o.toJSONString();
    }

    public static String toolEnd(String name, String source, String args, String result,
                                 boolean ok, long ms)
    {
        return toolEnd(name, source, args, result, ok, ms, null, null);
    }

    public static String toolEnd(String name, String source, String args, String result,
                                 boolean ok, long ms, String owner)
    {
        return toolEnd(name, source, args, result, ok, ms, owner, null);
    }

    /**
     * 工具结束事件。{@code attachments} 非空时附带工具产出的富媒体附件(如生图工具的图片),
     * 前端据此在工具步骤内联渲染;为空则不写该字段,保持向后兼容(与 {@code owner} 同模式)。
     *
     * @param attachments 工具产出附件列表(可空)
     */
    public static String toolEnd(String name, String source, String args, String result,
                                 boolean ok, long ms, String owner, List<ToolAttachment> attachments)
    {
        return toolEnd(name, source, args, result, ok, ms, owner, attachments, null);
    }

    public static String toolEnd(String name, String source, String args, String result,
                                 boolean ok, long ms, String owner, List<ToolAttachment> attachments,
                                 String stepId)
    {
        JSONObject o = new JSONObject();
        o.put("type", "tool_end");
        o.put("name", name);
        o.put("source", source);
        o.put("args", args);
        o.put("result", result);
        o.put("ok", ok);
        o.put("ms", ms);
        putOwner(o, owner);
        putStep(o, stepId, owner);
        if (attachments != null && !attachments.isEmpty())
        {
            o.put("attachments", JSON.toJSONString(attachments));
        }
        return o.toJSONString();
    }

    public static String agentStart(String name, String agentCode)
    {
        return agentStart(name, agentCode, null);
    }

    public static String agentStart(String name, String agentCode, String owner)
    {
        return agentStart(name, agentCode, owner, null);
    }

    /**
     * 子智能体开始。{@code agentCode} 是 agent 的身份;{@code invId} 是<b>本次调用实例</b>的身份 ——
     * 同一轮里同一个子 agent 被调多次时,code 必然撞车,嵌套事件(工具/思考/文本)的
     * {@code owner} 会带调用实例的 invId,前端优先按 invId 归位。
     * {@code owner} 是包裹它的上一层调用实例(非空时这个 agent step 本身也要嵌进 owner)。
     */
    public static String agentStart(String name, String agentCode, String owner, String invId)
    {
        JSONObject o = new JSONObject();
        o.put("type", "agent_start");
        o.put("name", name);
        o.put("agentCode", agentCode);
        putOwner(o, owner);
        putInvId(o, invId);
        putStep(o, invId, owner);
        return o.toJSONString();
    }

    public static String agentEnd(String name, String result, boolean ok, long ms)
    {
        return agentEnd(name, result, ok, ms, null);
    }

    public static String agentEnd(String name, String result, boolean ok, long ms, String owner)
    {
        return agentEnd(name, result, ok, ms, owner, null);
    }

    /**
     * 子智能体结束。{@code invId} 标识要关闭的是哪一次调用实例 ——
     * 没有它时前端只能按 name 关「最后一个 streaming 的同名 step」,同 agent
     * 多次调用时会关错卡片。
     */
    public static String agentEnd(String name, String result, boolean ok, long ms,
                                  String owner, String invId)
    {
        JSONObject o = new JSONObject();
        o.put("type", "agent_end");
        o.put("name", name);
        o.put("result", result);
        o.put("ok", ok);
        o.put("ms", ms);
        putOwner(o, owner);
        putInvId(o, invId);
        putStep(o, invId, owner);
        return o.toJSONString();
    }

    /** 最终回答 chunk。{@code owner} 非空时表示这是子智能体的回答,前端追加进对应 agent step。 */
    public static String text(String text, String owner)
    {
        return text(text, owner, owner == null ? "answer" : owner);
    }

    public static String text(String text, String parentStepId, String stepId)
    {
        JSONObject o = new JSONObject();
        o.put("type", "text");
        o.put("text", text);
        putOwner(o, parentStepId);
        putStep(o, stepId, parentStepId);
        return o.toJSONString();
    }

    /** owner 非空才写入,顶层事件不带该字段(前端以字段缺省判定顶层)。 */
    private static void putOwner(JSONObject o, String owner)
    {
        if (owner != null && !owner.isEmpty())
        {
            o.put("owner", owner);
        }
    }

    /** invId 非空才写入;缺省时前端回退到按 agentCode/name 匹配(兼容旧事件)。 */
    private static void putInvId(JSONObject o, String invId)
    {
        if (invId != null && !invId.isEmpty())
        {
            o.put("invId", invId);
        }
    }

    /**
     * 写入步骤标识。
     *
     * <p><b>parentStepId 等于 stepId 时只写 stepId</b>:「归属」与「父子」是两件事。
     * 子智能体自己的输出 chunk 带的 owner 就是它本身那个 step
     * (见 {@link #text(String, String)} / {@link #reasoning(String, String)}),
     * 原样当父指针发出去,消费方按父指针建树时该节点会成为自己的父 —— 自成环、进不了根集合,
     * 它连同下面挂的工具一起从时间线消失。
     *
     * <p>归属信息由 {@code owner} 字段单独承载,消费方读不到 parentStepId 时回退到它,语义不变。
     */
    private static void putStep(JSONObject o, String stepId, String parentStepId)
    {
        if (stepId != null && !stepId.isEmpty()) o.put("stepId", stepId);
        if (parentStepId != null && !parentStepId.isEmpty() && !parentStepId.equals(stepId))
        {
            o.put("parentStepId", parentStepId);
        }
    }

    public static String done()
    {
        return done(null, null);
    }

    /**
     * 本轮结束。可选附带本轮用量与上下文占用，前端刻度条直接更新、省一次请求。
     *
     * @param usage   {promptTokens, completionTokens, totalTokens, callCount}，可空
     * @param context {used, percent, budget, ...}，可空
     */
    public static String done(JSONObject usage, JSONObject context)
    {
        JSONObject o = new JSONObject();
        o.put("type", "done");
        if (usage != null)
        {
            o.put("usage", usage);
        }
        if (context != null)
        {
            o.put("context", context);
        }
        return o.toJSONString();
    }

    public static String error(String message)
    {
        JSONObject o = new JSONObject();
        o.put("type", "error");
        o.put("message", message);
        return o.toJSONString();
    }

    /**
     * 只给前端的 UI 产物。不进 {@code DbChatMemory},不改工具返回给模型的文本。
     *
     * <p>{@code name} 是登记过的产物名(如 {@code kb.references});{@code eventId}
     * 是幂等键,前端回放按它去重。{@code stepId} 必须是事件自己的 id,不能复用工具
     * stepId,否则投影会把工具行覆盖成 ui 行。{@code producerStepId} 才是产出它的工具步骤。
     */
    public static String ui(String name, int schemaVersion, String eventId, Object payload,
                            String owner, String stepId, String producerStepId)
    {
        JSONObject o = new JSONObject();
        o.put("type", "ui");
        o.put("name", name);
        o.put("schemaVersion", schemaVersion);
        if (eventId != null && !eventId.isEmpty())
        {
            o.put("eventId", eventId);
        }
        if (payload != null)
        {
            o.put("payload", payload);
        }
        putOwner(o, owner);
        putStep(o, stepId, producerStepId);
        return o.toJSONString();
    }
}
