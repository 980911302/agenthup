package com.ruoyi.system.ai.sse;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SSE 事件 JSON 构造测试。解析回对象断言字段,不依赖字符串顺序。
 */
class ChatEventJsonTest {

    @Test
    void text_event() {
        JSONObject o = JSON.parseObject(ChatEventJson.text("你好"));
        assertEquals("text", o.getString("type"));
        assertEquals("你好", o.getString("text"));
        assertEquals("answer", o.getString("stepId"));
    }

    /**
     * 子智能体自己的输出:owner 指的就是它本身那个 step。
     * owner 表「归属」照常带上,但绝不能同时当「父指针」发出去 ——
     * 消费方按父指针建树会让该节点自成环,子智能体与它下面的工具一起从时间线消失。
     */
    @Test
    void subAgentTextCarriesOwnerButNeverSelfParent() {
        JSONObject o = JSON.parseObject(ChatEventJson.text("子回答", "call-7"));
        assertEquals("call-7", o.getString("stepId"));
        assertEquals("call-7", o.getString("owner"));
        assertNull(o.getString("parentStepId"));
    }

    /** 同一条约束对思考 chunk 同样成立(reasoning 的 stepId 带前缀,父指针照常保留)。 */
    @Test
    void subAgentReasoningKeepsRealParentStepId() {
        JSONObject o = JSON.parseObject(ChatEventJson.reasoning("子思考", "call-7"));
        assertEquals("reasoning:call-7", o.getString("stepId"));
        assertEquals("call-7", o.getString("parentStepId"));
    }

    @Test
    void done_event() {
        JSONObject o = JSON.parseObject(ChatEventJson.done());
        assertEquals("done", o.getString("type"));
        assertEquals(1, o.size());
    }

    @Test
    void error_event() {
        JSONObject o = JSON.parseObject(ChatEventJson.error("boom"));
        assertEquals("error", o.getString("type"));
        assertEquals("boom", o.getString("message"));
    }

    @Test
    void reasoning_event() {
        JSONObject o = JSON.parseObject(ChatEventJson.reasoning("思考中"));
        assertEquals("reasoning", o.getString("type"));
        assertEquals("思考中", o.getString("text"));
        assertEquals("reasoning:main", o.getString("stepId"));
    }

    @Test
    void tool_start_event() {
        JSONObject o = JSON.parseObject(ChatEventJson.toolStart("loadKnowledge", "builtin"));
        assertEquals("tool_start", o.getString("type"));
        assertEquals("loadKnowledge", o.getString("name"));
        assertEquals("builtin", o.getString("source"));
        assertNull(o.getString("args"), "无入参时不写该字段,保持向后兼容");
    }

    /**
     * tool_start 必须带入参 —— 前端 ToolStep 的入参/返回都是 v-if,
     * 不带 args 的话执行期间只能显示一个没有内容的空壳。
     */
    @Test
    void tool_start_carriesArgs_forInFlightDisplay() {
        JSONObject o = JSON.parseObject(ChatEventJson.toolStart(
                "readFile", "builtin", null, "{\"path\":\"src/App.vue\"}"));
        assertEquals("tool_start", o.getString("type"));
        assertEquals("readFile", o.getString("name"));
        assertEquals("{\"path\":\"src/App.vue\"}", o.getString("args"));
    }

    @Test
    void tool_end_event() {
        JSONObject o = JSON.parseObject(ChatEventJson.toolEnd("loadKnowledge", "builtin",
                "{\"q\":\"x\"}", "结果", true, 820));
        assertEquals("tool_end", o.getString("type"));
        assertEquals("loadKnowledge", o.getString("name"));
        assertEquals("builtin", o.getString("source"));
        assertEquals("{\"q\":\"x\"}", o.getString("args"));
        assertEquals("结果", o.getString("result"));
        assertTrue(o.getBoolean("ok"));
        assertEquals(820, o.getLong("ms").longValue());
    }

    @Test
    void agent_start_event() {
        JSONObject o = JSON.parseObject(ChatEventJson.agentStart("政策智能体", "policy_agent"));
        assertEquals("agent_start", o.getString("type"));
        assertEquals("政策智能体", o.getString("name"));
        assertEquals("policy_agent", o.getString("agentCode"));
    }

    @Test
    void agent_end_event() {
        JSONObject o = JSON.parseObject(ChatEventJson.agentEnd("政策智能体", "回复内容", true, 37000));
        assertEquals("agent_end", o.getString("type"));
        assertEquals("政策智能体", o.getString("name"));
        assertEquals("回复内容", o.getString("result"));
        assertTrue(o.getBoolean("ok"));
        assertEquals(37000, o.getLong("ms").longValue());
    }

    /**
     * invId(调用实例)是修复「同 agent 一轮被调多次串卡」的关键:
     * 同 code 多次调用时 agentCode 必然撞车,前端只能靠 invId 区分。
     * 缺省(旧调用方)时不写该字段,前端回退到按 name 匹配。
     */
    @Test
    void agent_start_end_carryInvId_whenPresent() {
        JSONObject start = JSON.parseObject(
                ChatEventJson.agentStart("政策智能体", "policy_agent", null, "policy_agent#a1b2c3d4"));
        assertEquals("agent_start", start.getString("type"));
        assertEquals("policy_agent#a1b2c3d4", start.getString("invId"));
        assertEquals("policy_agent#a1b2c3d4", start.getString("stepId"));

        JSONObject end = JSON.parseObject(
                ChatEventJson.agentEnd("政策智能体", "回复内容", true, 37000, null, "policy_agent#a1b2c3d4"));
        assertEquals("agent_end", end.getString("type"));
        assertEquals("policy_agent#a1b2c3d4", end.getString("invId"));
        assertEquals("policy_agent#a1b2c3d4", end.getString("stepId"));
    }

    @Test
    void agent_start_end_omitInvId_whenNull() {
        JSONObject start = JSON.parseObject(ChatEventJson.agentStart("政策智能体", "policy_agent"));
        assertNull(start.getString("invId"), "invId 缺省不写,兼容旧调用方");

        JSONObject end = JSON.parseObject(ChatEventJson.agentEnd("政策智能体", "回复内容", true, 37000));
        assertNull(end.getString("invId"));
    }

    /**
     * UI 产物信封:前端只认 type=ui + name,payload 原样透传,不进 LLM 历史。
     * eventId 是幂等键;parentStepId 指向产出它的工具步骤,stepId 用 eventId 避免覆盖工具行。
     */
    @Test
    void ui_event_carriesNamePayloadAndProducerAnchor() {
        JSONObject payload = new JSONObject();
        payload.put("query", "请假制度");
        JSONObject o = JSON.parseObject(ChatEventJson.ui(
                "kb.references", 1, "call-9:kb.references", payload, "agent#aa",
                "call-9:kb.references", "call-9"));
        assertEquals("ui", o.getString("type"));
        assertEquals("kb.references", o.getString("name"));
        assertEquals(1, o.getIntValue("schemaVersion"));
        assertEquals("call-9:kb.references", o.getString("eventId"));
        assertEquals("call-9:kb.references", o.getString("stepId"));
        assertEquals("call-9", o.getString("parentStepId"));
        assertEquals("agent#aa", o.getString("owner"));
        assertEquals("请假制度", o.getJSONObject("payload").getString("query"));
    }

    @Test
    void ui_event_omitsBlankOwnerAndSelfParent() {
        JSONObject o = JSON.parseObject(ChatEventJson.ui(
                "kb.references", 1, "e1", new JSONObject(), null, "e1", "e1"));
        assertNull(o.getString("owner"));
        assertNull(o.getString("parentStepId"));
        assertEquals("e1", o.getString("stepId"));
    }
}
