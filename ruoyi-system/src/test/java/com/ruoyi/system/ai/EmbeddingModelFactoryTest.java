package com.ruoyi.system.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;

/**
 * 嵌入调用的防线测试:重试次数有界、超时字段默认值与 @Value 兜底一致。
 *
 * <p>护的是这条线上事故:Spring AI 三参构造的 {@code OpenAiEmbeddingModel} 默认挂
 * {@code RetryUtils.DEFAULT_RETRY_TEMPLATE}(10 次 + 指数退避 2s×5 封顶 180s,
 * 且 retryOn 带着 {@link ResourceAccessException}),连"连接被拒"这种毫秒级失败也照满退避
 * 重来 —— 一次 embed 阻塞约 19 分钟。而 embed 跑在对话主链路上(每轮记忆检索,
 * 在用户消息落库之前),同步占着 core=4 的 chat-run 线程池,向量渠道一挂就把实例堵死。
 */
class EmbeddingModelFactoryTest
{
    /**
     * 重试次数必须是配置的那个数,不是框架默认的 10 次。
     *
     * <p>直接跑一遍模板:回调恒抛 {@link ResourceAccessException}(渠道不通的真实异常类型,
     * 也正是框架默认模板会重试的那一类),数调用次数。
     */
    @Test
    void retryTemplate_isBounded_notFrameworkDefaultTen()
    {
        EmbeddingModelFactory factory = new EmbeddingModelFactory();

        ReflectionTestUtils.setField(factory, "maxAttempts", 2);
        assertEquals(2, attempts(factory.retryTemplate()), "重试次数应按配置封顶");

        ReflectionTestUtils.setField(factory, "maxAttempts", 1);
        assertEquals(1, attempts(factory.retryTemplate()), "maxAttempts=1 即不重试");

        // 非法值不能退化成"无限重试":框架默认那 10 次指数退避正是本次事故的放大器
        ReflectionTestUtils.setField(factory, "maxAttempts", 0);
        assertEquals(1, attempts(factory.retryTemplate()), "<=0 收敛为只试一次");
    }

    /** 跑一遍模板,返回回调实际被调用的次数。 */
    private int attempts(RetryTemplate template)
    {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(ResourceAccessException.class, () -> template.execute(ctx -> {
            calls.incrementAndGet();
            throw new ResourceAccessException("渠道不通");
        }));
        return calls.get();
    }

    /**
     * 字段默认值要与 @Value 兜底一致 —— 直接 new 出来时不能静默退回"无超时"。
     * (沿用 ContextCleaner 那条既有约定,不能只改 yml。)
     */
    @Test
    void timeoutFields_defaultsMatchValueFallback()
    {
        EmbeddingModelFactory fresh = new EmbeddingModelFactory();
        assertEquals(5, ReflectionTestUtils.getField(fresh, "connectTimeoutSeconds"));
        assertEquals(60, ReflectionTestUtils.getField(fresh, "readTimeoutSeconds"));
        assertEquals(2, ReflectionTestUtils.getField(fresh, "maxAttempts"));
    }

    /**
     * 退避是固定的小间隔,不是指数退避 —— 三次尝试的总耗时必须远小于框架默认的分钟级。
     * 断言留足余量(只卡"秒级以内"),不锁具体毫秒数,避免慢机上假红。
     */
    @Test
    void retryBackoff_staysWithinSeconds()
    {
        EmbeddingModelFactory factory = new EmbeddingModelFactory();
        ReflectionTestUtils.setField(factory, "maxAttempts", 3);
        long start = System.currentTimeMillis();
        attempts(factory.retryTemplate());
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed < 5_000L,
                "3 次尝试应在秒级内退完,实际 " + elapsed + "ms;框架默认模板同样次数要 62 秒");
    }
}
