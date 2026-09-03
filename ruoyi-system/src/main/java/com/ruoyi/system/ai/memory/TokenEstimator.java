package com.ruoyi.system.ai.memory;

import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.stereotype.Component;

/**
 * token 数估算(§4.7),包装 Spring AI 的 {@link JTokkitTokenCountEstimator}。
 *
 * <p>jtokkit({@code com.knuddels:jtokkit:1.1.0})由 spring-ai-commons 传递进来,无需加依赖。
 * 用 cl100k_base(OpenAI 的 tokenizer),对通义/DeepSeek 不是精确值。但中文在 cl100k 下
 * token 数<b>偏多</b>,所以估算偏保守 -- 这个方向的误差是安全的,比字符数/3 准得多。
 *
 * <p>目的:写入消息时就算好 token,让上下文预算检查退化成一条 {@code sum(tokens)} SQL,
 * 不必每轮对全部历史重跑 tokenizer。
 *
 * @author ruoyi
 */
@Component
public class TokenEstimator
{
    private final JTokkitTokenCountEstimator delegate = new JTokkitTokenCountEstimator();

    public int estimate(String text)
    {
        return text == null ? 0 : delegate.estimate(text);
    }
}
