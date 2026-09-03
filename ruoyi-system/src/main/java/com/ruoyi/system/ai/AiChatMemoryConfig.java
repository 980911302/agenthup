package com.ruoyi.system.ai;

import com.ruoyi.system.ai.memory.DbChatMemory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI 对话记忆配置
 *
 * <p>本方案直接实现 {@link ChatMemory}(见 {@link DbChatMemory}),不实现
 * {@code ChatMemoryRepository}。原因:{@code MessageWindowChatMemory.saveAll} 是覆盖语义,
 * 会把超出窗口的历史物理删掉,审计和前端时间线就没了 -- 那样就必须拆两张表。
 * 直接实现 {@code ChatMemory},{@code add} 就是真追加,一张 {@code ai_chat_message} 够用。
 * 详见 {@code docs/ai/ai-agent-graph.md} §4.5。
 *
 * @author ruoyi
 */
@Configuration
public class AiChatMemoryConfig
{
    /**
     * 对话记忆 Bean:直接返回 {@link DbChatMemory}。
     * <p>不再用 {@code MessageWindowChatMemory} + {@code RedisChatMemoryRepository}
     * (按条数滑动窗口 + 覆盖语义正是要替换掉的东西)。
     */
    @Bean
    public ChatMemory chatMemory(DbChatMemory dbChatMemory)
    {
        return dbChatMemory;
    }
}
