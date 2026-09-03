package com.ruoyi.ai.contract.event;

public final class RunEventTypes
{
    public static final String STARTED = "ai.run.started";
    public static final String STATUS_CHANGED = "ai.run.status.changed";
    public static final String TEXT_DELTA = "ai.run.text.delta";
    public static final String REASONING_DELTA = "ai.run.reasoning.delta";
    public static final String TOOL_STARTED = "ai.run.tool.started";
    public static final String TOOL_CONFIRMATION_REQUIRED = "ai.run.tool.confirmation.required";
    public static final String TOOL_CALL_REQUESTED = "ai.run.tool.call.requested";
    public static final String TOOL_COMPLETED = "ai.run.tool.completed";
    public static final String AGENT_STARTED = "ai.run.agent.started";
    public static final String AGENT_COMPLETED = "ai.run.agent.completed";
    public static final String UI_PUBLISHED = "ai.run.ui.published";
    public static final String CONTEXT_COMPACTED = "ai.run.context.compacted";
    public static final String COMPLETED = "ai.run.completed";
    public static final String FAILED = "ai.run.failed";
    public static final String CANCELLED = "ai.run.cancelled";
    public static final String INTERRUPTED = "ai.run.interrupted";

    private RunEventTypes() { }
}
