package com.ruoyi.system.ai.agent;

import com.ruoyi.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 智能体递归深度与环路保护测试(§5.4)。深度上限 3,到顶不再挂子 agent 工具,异常作兜底。
 */
class AgentCallDepthTest {

    @Test
    void root_chainEmpty() {
        assertTrue(AgentCallDepth.root().chain().isEmpty());
    }

    @Test
    void enter_appendsAgent() {
        AgentCallDepth d = AgentCallDepth.root().enter(10L);
        assertEquals(List.of(10L), d.chain());
    }

    @Test
    void enter_atMaxDepth_throws() {
        AgentCallDepth d = AgentCallDepth.root().enter(1L).enter(2L).enter(3L);
        assertThrows(ServiceException.class, () -> d.enter(4L));
    }

    @Test
    void enter_cycle_throws() {
        AgentCallDepth d = AgentCallDepth.root().enter(10L).enter(20L);
        assertThrows(ServiceException.class, () -> d.enter(10L));
    }

    @Test
    void isMaxed_trueAtMax() {
        AgentCallDepth d = AgentCallDepth.root().enter(1L).enter(2L).enter(3L);
        assertTrue(d.isMaxed());
    }
}
