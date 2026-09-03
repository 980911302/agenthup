package com.ruoyi.system.ai.agent;

import java.util.List;
import com.ruoyi.system.domain.AiModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentContextVisionTest
{
    @Test
    void visionEnabled_trueOnlyWhenFlagIsOne()
    {
        assertTrue(ctx(model("1")).visionEnabled());
        assertTrue(AgentContext.visionEnabled(model("1")));
    }

    @Test
    void visionEnabled_falseWhenZero()
    {
        assertFalse(ctx(model("0")).visionEnabled());
        assertFalse(AgentContext.visionEnabled(model("0")));
    }

    @Test
    void visionEnabled_falseWhenNullFlag()
    {
        assertFalse(ctx(model(null)).visionEnabled());
        assertFalse(AgentContext.visionEnabled(model(null)));
    }

    @Test
    void visionEnabled_falseWhenModelMissing()
    {
        assertFalse(ctx(null).visionEnabled());
        assertFalse(AgentContext.visionEnabled(null));
    }

    @Test
    void reasoningEnabled_trueOnlyWhenFlagIsOne()
    {
        assertTrue(ctx(reasoningModel("1")).reasoningEnabled());
        assertTrue(AgentContext.reasoningEnabled(reasoningModel("1")));
        assertFalse(ctx(reasoningModel("0")).reasoningEnabled());
        assertFalse(ctx(reasoningModel(null)).reasoningEnabled());
        assertFalse(AgentContext.reasoningEnabled(null));
    }

    private static AiModel model(String visionEnabled)
    {
        AiModel m = new AiModel();
        m.setVisionEnabled(visionEnabled);
        return m;
    }

    private static AiModel reasoningModel(String reasoningEnabled)
    {
        AiModel m = new AiModel();
        m.setReasoningEnabled(reasoningEnabled);
        return m;
    }

    private static AgentContext ctx(AiModel model)
    {
        return new AgentContext(1L, "a", null, null, List.of(), "sys", "s:1", 1L, model, 1000);
    }
}
