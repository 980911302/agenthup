package com.ruoyi.system.ai.memory.longterm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.ruoyi.system.ai.memory.TokenEstimator;
import com.ruoyi.system.domain.AiMemory;
import com.ruoyi.system.mapper.AiMemoryMapper;

/**
 * 记忆读侧 {@link MemoryRetriever} 的规则测试(spec §7.2 五条规则)。
 *
 * <p>用 Mockito 桩掉 {@link MemoryService}/{@link AiMemoryMapper}/{@link MemoryQueryVectorizer},
 * 锁纯逻辑:空库短路、短消息跳过、阈值过滤、token 硬顶截断、注入格式、静默降级。
 * token 估算桩成「字符数」,让截断行为可精确断言。
 */
class MemoryRetrieverTest
{
    private MemoryRetriever retriever;
    private MemoryService memoryService;
    private MemoryQueryVectorizer vectorizer;
    private AiMemoryMapper memoryMapper;
    private TokenEstimator tokenEstimator;
    private List<String> vectorizedTexts;
    private List<Long> onHitIds;

    @BeforeEach
    void setUp() throws Exception
    {
        retriever = new MemoryRetriever();
        memoryService = mock(MemoryService.class);
        vectorizer = mock(MemoryQueryVectorizer.class);
        memoryMapper = mock(AiMemoryMapper.class);
        tokenEstimator = mock(TokenEstimator.class);
        MemoryInjectionBudget budget = new MemoryInjectionBudget(tokenEstimator);

        vectorizedTexts = new ArrayList<>();
        when(vectorizer.vectorize(anyString())).thenAnswer(inv -> {
            vectorizedTexts.add(inv.getArgument(0));
            return new float[] {1f, 0f, 0f};
        });
        onHitIds = new ArrayList<>();
        doAnswer(inv -> {
            onHitIds.add(inv.getArgument(0));
            return null;
        }).when(memoryService).onHit(anyLong());

        // token 估算 = 字符数,截断断言可精确计算
        when(tokenEstimator.estimate(anyString())).thenAnswer(
                inv -> ((String) inv.getArgument(0)).length());

        setField(retriever, "memoryService", memoryService);
        setField(retriever, "vectorizer", vectorizer);
        setField(retriever, "tokenEstimator", tokenEstimator);
        setField(retriever, "injectionBudget", budget);
        setField(retriever, "memoryMapper", memoryMapper);
        setField(retriever, "enabled", true);
        setField(retriever, "topK", 5);
        setField(retriever, "minScore", 0.75);
        setField(retriever, "identityMinScore", 0.4);
        setField(retriever, "minQueryLength", 8);
        setField(retriever, "maxInjectTokens", 500);
    }

    // ---- 规则2:空库短路(按 userId 缓存,跳过时不发 embedding)----

    @Test
    void emptyLibrary_shortCircuits_withoutEmbedding()
    {
        when(memoryMapper.selectByUser(1L)).thenReturn(List.of());

        MemoryInjection result = retriever.retrieve(1L, 5L, "今天天气怎么样帮忙看看");

        assertFalse(result.isInjected());
        verify(vectorizer, never()).vectorize(anyString());
        verify(memoryService, never()).search(any(), any(), anyInt(), anyDouble());
    }

    /**
     * 空库短路缓存必须有容量上限,且清理后仍然正确工作。
     *
     * <p>该缓存只收「有记忆」的用户、没有任何被动淘汰,长跑进程里只增不减。
     * 这里把上限压到 3,灌入超量用户,断言:(a) 缓存没有无限增长;
     * (b) 清空只是让用户下次重探一次库,注入结果不受影响 —— 它是纯优化结构。
     */
    @Test
    void hasMemoryCache_isCapped_andStillCorrectAfterEviction() throws Exception
    {
        int original = MemoryRetriever.HAS_MEMORY_MAX_ENTRIES;
        try
        {
            MemoryRetriever.HAS_MEMORY_MAX_ENTRIES = 3;
            // 每个用户都有记忆 → 每次探测都会入缓存
            when(memoryMapper.selectByUser(anyLong()))
                    .thenReturn(List.of(memory(1L, "fact", "用户在北京工作")));
            when(memoryService.search(any(), any(), anyInt(), anyDouble()))
                    .thenReturn(List.of(memory(1L, "fact", "用户在北京工作")));

            for (long uid = 1; uid <= 20; uid++)
            {
                assertTrue(retriever.retrieve(uid, 5L, "今天天气怎么样帮忙看看").isInjected(),
                        "清理不应影响注入结果 userId=" + uid);
            }

            @SuppressWarnings("unchecked")
            java.util.Map<Long, Long> cache = (java.util.Map<Long, Long>)
                    readField(retriever, "hasMemoryCache");
            assertTrue(cache.size() <= MemoryRetriever.HAS_MEMORY_MAX_ENTRIES,
                    "空库短路缓存必须受上限约束,实际 " + cache.size());
        }
        finally
        {
            MemoryRetriever.HAS_MEMORY_MAX_ENTRIES = original;
        }
    }

    // ---- 规则4:短消息不检索("嗯""继续")----

    @Test
    void shortMessage_skipsRetrieval()
    {
        when(memoryMapper.selectByUser(1L)).thenReturn(List.of(memory(1L, "fact", "用户在北京工作")));

        MemoryInjection result = retriever.retrieve(1L, 5L, "好的");

        assertFalse(result.isInjected());
        verify(vectorizer, never()).vectorize(anyString());
        verify(memoryService, never()).search(any(), any(), anyInt(), anyDouble());
    }

    @Test
    void identityQuestion_bypassesShortMessageGuard()
    {
        when(memoryMapper.selectByUser(1L)).thenReturn(List.of(memory(1L, "fact", "用户叫张林林")));
        when(memoryService.search(any(), any(), anyInt(), anyDouble()))
                .thenReturn(List.of(memory(1L, "fact", "用户叫张林林")));

        MemoryInjection result = retriever.retrieve(1L, 5L, "我是谁");

        assertTrue(result.isInjected());
        assertTrue(result.injectedText().orElseThrow().contains("用户叫张林林"));
        assertEquals(List.of("我是谁"), vectorizedTexts);
        verify(memoryService).search(any(), any(), eq(5), eq(0.4));
    }

    // ---- 规则3:相似度阈值优先于 top-k(搜索返回空 → 不注入)----

    @Test
    void belowMinScore_searchEmpty_returnsNoInjection()
    {
        when(memoryMapper.selectByUser(1L)).thenReturn(List.of(memory(1L, "fact", "旧记忆")));
        when(memoryService.search(any(), any(), anyInt(), anyDouble())).thenReturn(List.of());

        MemoryInjection result = retriever.retrieve(1L, 5L, "请帮我推荐一下附近的餐厅怎么样");

        assertFalse(result.isInjected());
        // 通过了空库检查与短消息门槛,确实调了 embedding 与检索,只是结果被阈值过滤为空
        assertEquals(1, vectorizedTexts.size());
        verify(memoryService, times(1)).search(any(), any(), eq(5), eq(0.75));
    }

    // ---- §7.3:注入 token 硬顶,超出按相似度截断 ----

    @Test
    void tokenCap_truncatesToBudget_keepsPrefix()
    {
        when(memoryMapper.selectByUser(1L)).thenReturn(List.of(memory(1L, "fact", "x")));
        // 5 条记忆,每条 content 都是 60 个字符;token 估算 = 字符数
        List<AiMemory> five = new ArrayList<>();
        for (long i = 1; i <= 5; i++)
        {
            five.add(memory(i, "fact", "x".repeat(60)));
        }
        when(memoryService.search(any(), any(), anyInt(), anyDouble())).thenReturn(five);

        // 预算 = 头部 + 恰好 2 条,放不下第 3 条
        int headerLen = MemoryRetriever.INJECTION_HEADER.length();
        int entryCost = "- [fact] ".length() + 60;
        setField(retriever, "maxInjectTokens", headerLen + 2 * entryCost + 5);

        MemoryInjection result = retriever.retrieve(1L, 5L, "这是一个足够长的查询文本用来检索");

        assertTrue(result.isInjected());
        String text = result.injectedText().get();
        assertEquals(2, countEntries(text), "只应注入 2 条,超出预算的截断");
        assertTrue(text.startsWith(MemoryRetriever.INJECTION_HEADER));
        // 命中回写只针对实际注入的前 2 条
        assertEquals(List.of(1L, 2L), result.hitMemoryIds());
        assertEquals(List.of(1L, 2L), onHitIds);
    }

    // ---- 规则5:注入格式(边界 + 来源标注 + [type] content)----

    @Test
    void injectionFormat_hasBoundarySourceAndType()
    {
        when(memoryMapper.selectByUser(1L)).thenReturn(List.of(memory(1L, "fact", "x")));
        when(memoryService.search(any(), any(), anyInt(), anyDouble())).thenReturn(List.of(
                memory(11L, "preference", "回复要简洁"),
                memory(12L, "fact", "用户在北京工作")));

        MemoryInjection result = retriever.retrieve(1L, 5L, "请帮我回复一下这个客户的消息");

        assertTrue(result.isInjected());
        String text = result.injectedText().get();
        assertTrue(text.contains("<user_memory>"), "必须有边界起始标签");
        assertTrue(text.contains("</user_memory>"), "必须有边界结束标签");
        assertTrue(text.contains("系统检索提供,非本轮输入"), "必须声明来源,防止模型当成用户输入");
        assertTrue(text.contains("不相关就忽略"), "必须给使用指令,否则相关与否全靠模型自己悟");
        assertTrue(text.contains("不要主动复述"), "体验红线:不能每轮开口就复述记忆");
        assertTrue(text.contains("- [preference] 回复要简洁"), "条目必须带 type: " + text);
        assertTrue(text.contains("- [fact] 用户在北京工作"), "条目必须带 type: " + text);
        // 注入文本以闭标签结尾,调用方在它后面拼用户原话
        assertTrue(text.endsWith("</user_memory>\n"));
        assertEquals(List.of(11L, 12L), onHitIds);
    }

    // ---- 总开关 ----

    @Test
    void disabled_skipsEverything()
    {
        setField(retriever, "enabled", false);

        MemoryInjection result = retriever.retrieve(1L, 5L, "这是一个足够长的查询文本用来检索");

        assertFalse(result.isInjected());
        verify(vectorizer, never()).vectorize(anyString());
        verify(memoryService, never()).search(any(), any(), anyInt(), anyDouble());
    }

    // ---- 旁路语义:embedding 失败静默降级,不抛异常 ----

    @Test
    void embeddingFailure_silentlyDegrades_noInjection()
    {
        when(memoryMapper.selectByUser(1L)).thenReturn(List.of(memory(1L, "fact", "x")));
        when(vectorizer.vectorize(anyString()))
                .thenThrow(new RuntimeException("embedding 服务不可用"));

        MemoryInjection result = retriever.retrieve(1L, 5L, "这是一个足够长的查询文本用来检索");

        assertFalse(result.isInjected(), "embedding 失败必须静默降级,不抛异常");
    }

    // ---- 无登录身份(userId=null):不注入 ----

    @Test
    void nullUserId_skipsRetrieval()
    {
        MemoryInjection result = retriever.retrieve(null, 5L, "这是一个足够长的查询文本用来检索");

        assertFalse(result.isInjected());
        verify(vectorizer, never()).vectorize(anyString());
    }

    // ---- 组装 helper ----

    private static AiMemory memory(Long id, String type, String content)
    {
        AiMemory m = new AiMemory();
        m.setMemoryId(id);
        m.setUserId(1L);
        m.setAgentId(5L);
        m.setType(type);
        m.setContent(content);
        m.setStatus("active");
        return m;
    }

    private static int countEntries(String text)
    {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf("- [", idx)) >= 0)
        {
            count++;
            idx += 3;
        }
        return count;
    }

    // ---- 硬截止:旁路语义靠 try/catch 是接不全的 ----

    /**
     * 向量渠道挂起(不是抛异常,是<b>迟迟不返回</b>)时,检索必须按截止时间放弃,不能拖住这一轮。
     *
     * <p>护的是这条线上事故:embedding 客户端当时没有任何读超时、又挂着框架默认的
     * 10 次指数退避重试,一次调用能阻塞约 19 分钟。而检索同步跑在 chat-run 线程上
     * (core=4)、且在本轮 user 消息落库之前 —— 四轮就把整个实例的对话能力占光。
     * {@code retrieve} 外层的 catch(Throwable) 挡不住这种情况:它只接得住抛出来的异常。
     */
    @Test
    void slowVectorizer_isAbandonedAtDeadline_ratherThanBlockingTheTurn() throws Exception
    {
        when(memoryMapper.selectByUser(1L)).thenReturn(List.of(memory(1L, "user", "背景")));

        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        when(vectorizer.vectorize(anyString())).thenAnswer(inv -> {
            entered.countDown();
            Thread.sleep(60_000L);   // 模拟没有读超时的 embedding 调用
            return new float[] {1f, 0f, 0f};
        });

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors
                .newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "memory-retrieve-test");
                    t.setDaemon(true);
                    return t;
                });
        try
        {
            setField(retriever, "retrieveExecutor", pool);
            setField(retriever, "retrieveTimeoutSeconds", 1L);

            long start = System.currentTimeMillis();
            MemoryInjection result = retriever.retrieve(1L, 5L, "帮我看看之前记过的那些背景");
            long elapsed = System.currentTimeMillis() - start;

            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS), "应真的发起了检索");
            assertFalse(result.isInjected(), "超时按「没检索到」处理,不注入");
            assertTrue(elapsed < 15_000L,
                    "必须在截止时间附近返回,实际 " + elapsed + "ms;卡死的调用不能拖住这一轮");
        }
        finally
        {
            pool.shutdownNow();
        }
    }

    /** 截止 <=0 或没有执行池时退回内联同步调用 —— 与开这道闸之前完全一致,不会更糟。 */
    @Test
    void deadlineDisabled_fallsBackToInlineCall()
    {
        when(memoryMapper.selectByUser(1L)).thenReturn(List.of(memory(1L, "user", "背景")));
        when(memoryService.search(any(), any(), anyInt(), anyDouble()))
                .thenReturn(List.of(memory(1L, "user", "背景")));

        setField(retriever, "retrieveExecutor", null);
        setField(retriever, "retrieveTimeoutSeconds", 0L);

        MemoryInjection result = retriever.retrieve(1L, 5L, "帮我看看之前记过的那些背景");

        assertTrue(result.isInjected(), "关掉截止后行为不变,照常检索注入");
        assertEquals(1, vectorizedTexts.size());
    }

    /** 字段默认值要与 @Value 兜底一致 —— 直接 new 出来时不能静默退回无截止。 */
    @Test
    void retrieveTimeout_defaultMatchesValueFallback() throws Exception
    {
        assertEquals(5L, readField(new MemoryRetriever(), "retrieveTimeoutSeconds"),
                "字段默认值要与 @Value 的兜底值一致,不能只改 yml");
    }

    private static void setField(Object target, String name, Object value)
    {
        try
        {
            java.lang.reflect.Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        }
        catch (Exception e)
        {
            throw new IllegalStateException("注入字段失败: " + name, e);
        }
    }

    private static Object readField(Object target, String name) throws Exception
    {
        java.lang.reflect.Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }
}
