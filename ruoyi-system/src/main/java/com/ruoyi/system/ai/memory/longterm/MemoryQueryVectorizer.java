package com.ruoyi.system.ai.memory.longterm;

/**
 * 记忆检索查询向量化抽象(读侧 spec §7)。
 *
 * <p>把「把一段用户文本变成查询向量」收成一条窄接口,便于两点:
 * <ul>
 *   <li>生产用 {@link EmbeddingMemoryQueryVectorizer}(走 {@code EmbeddingModelFactory} 的远程 embedding);</li>
 *   <li>单测注入假实现 —— 不启动 Spring 上下文、不发真实 embedding 请求,
 *       {@code MemoryRetrieverTest} 才能锁住阈值/截断/短路这些纯逻辑。</li>
 * </ul>
 *
 * <p>向量化失败由实现自行抛异常,由 {@link MemoryRetriever} 统一捕获并静默降级
 * (spec §10:embedding 失败本轮不注入,记忆对主对话是旁路)。
 */
@FunctionalInterface
public interface MemoryQueryVectorizer
{
    /**
     * 把查询文本向量化。
     *
     * @param text 非空查询文本
     * @return 查询向量;实现方保证非空(失败走异常而非返回 null)
     */
    float[] vectorize(String text);
}
