package com.ruoyi.system.kb.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class QueryRouterTest
{
    @Test
    void globalKeywords()
    {
        QueryRouter.Decision d = QueryRouter.route("请全面总结本知识库的整体内容");
        assertEquals(KbSearchMode.global, d.mode);
        assertTrue(d.confidence >= 0.8);
    }

    @Test
    void entityShortToLocal()
    {
        QueryRouter.Decision d = QueryRouter.route("什么是 GraphRAG");
        assertEquals(KbSearchMode.local, d.mode);
    }

    @Test
    void howtoToHybrid()
    {
        QueryRouter.Decision d = QueryRouter.route("分库分表怎么做有哪些步骤");
        assertEquals(KbSearchMode.hybrid, d.mode);
    }

    @Test
    void shortKeywordToBasic()
    {
        QueryRouter.Decision d = QueryRouter.route("年假");
        assertEquals(KbSearchMode.basic, d.mode);
    }

    @Test
    void emptyIsBasic()
    {
        assertEquals(KbSearchMode.basic, QueryRouter.route("").mode);
        assertEquals(KbSearchMode.basic, QueryRouter.route(null).mode);
    }

    @Test
    void autoIsNeverDefaultModeFromOf()
    {
        // 系统默认永远 basic
        assertEquals(KbSearchMode.basic, KbSearchMode.of(null));
        assertEquals(KbSearchMode.basic, KbSearchMode.of(""));
        // 显式 auto 才是 auto
        assertEquals(KbSearchMode.auto, KbSearchMode.of("auto"));
        assertNotEquals(KbSearchMode.auto, KbSearchMode.of("vector"));
    }
}
