package com.ruoyi.system.ai.memory.longterm;

import com.ruoyi.system.ai.memory.ChatMessageMapperTestSupport;
import com.ruoyi.system.domain.AiChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 空闲会话兜底提炼扫描测试(spec §8.2 的推进逻辑)。
 *
 * <p>覆盖:候选命中 → 读消息 → 调提炼器 → 位点推进;提炼失败(返回 0)位点照常推进
 * (防止同一段历史反复重试);无候选/无消息时什么都不做。
 *
 * <p>用 {@link ChatMessageMapperTestSupport}(H2 建 ai_chat_message)装配真实
 * {@link ChatMessageRecorder};ProgressStore 用内存 fake(Mock 而非 Spring 上下文),
 * MemoryExtractor 用记录调用次数的桩。
 */
class IdleSessionExtractSchedulerTest extends ChatMessageMapperTestSupport
{
    /** 内存版 ProgressStore:记录已推进的位点。 */
    private static final class FakeProgressStore implements IdleSessionExtractScheduler.ProgressStore
    {
        final List<IdleSessionExtractScheduler.ExtractTarget> targets;
        Long advancedTo;

        FakeProgressStore(List<IdleSessionExtractScheduler.ExtractTarget> targets)
        {
            this.targets = targets;
        }

        @Override
        public List<IdleSessionExtractScheduler.ExtractTarget> candidates()
        {
            return targets;
        }

        @Override
        public void advance(String sessionId, Long agentId, Long userId, long extractToMessageId)
        {
            this.advancedTo = extractToMessageId;
        }

        final java.util.List<String> failures = new java.util.ArrayList<>();

        @Override
        public void markFailure(String sessionId, Long agentId, Long userId)
        {
            failures.add(sessionId);
        }
    }

    /** 记录调用次数并返回固定结果的假提炼器。 */
    private static class StubExtractor extends MemoryExtractor
    {
        final AtomicInteger calls = new AtomicInteger();
        final MemoryExtractor.ExtractResult result;

        StubExtractor(MemoryExtractor.ExtractResult result)
        {
            this.result = result;
        }

        @Override
        public MemoryExtractor.ExtractResult extract(Long userId, Long agentId, String sourceSessionId,
                                                     List<AiChatMessage> messages, Long latestMessageId)
        {
            calls.incrementAndGet();
            return result;
        }
    }

    private IdleSessionExtractScheduler newScheduler(FakeProgressStore store, StubExtractor extractor)
    {
        IdleSessionExtractScheduler s = new IdleSessionExtractScheduler();
        try
        {
            ChatMessageMapperTestSupport.setField(s, "progressStore", store);
            ChatMessageMapperTestSupport.setField(s, "recorder", recorder);
            ChatMessageMapperTestSupport.setField(s, "extractor", extractor);
            ChatMessageMapperTestSupport.setField(s, "idleSweepMinutes", 30);
            ChatMessageMapperTestSupport.setField(s, "sweepIntervalSeconds", 300L);
        }
        catch (Exception e)
        {
            throw new IllegalStateException(e);
        }
        return s;
    }

    @Test
    void sweep_extractsAndAdvancesProgress()
    {
        String conv = "s1:60";
        recorder.insert(conv, "s1", 60L, "USER", "我在北京工作", "0", 5);
        recorder.insert(conv, "s1", 60L, "ASSISTANT", "好的", "0", 5);
        session.commit();

        FakeProgressStore store = new FakeProgressStore(List.of(
                new IdleSessionExtractScheduler.ExtractTarget("s1", 60L, 1L, 0L, 2L)));
        StubExtractor extractor = new StubExtractor(MemoryExtractor.ExtractResult.done(1));
        IdleSessionExtractScheduler s = newScheduler(store, extractor);

        s.sweep();

        assertEquals(1, extractor.calls.get(), "应调用提炼器一次");
        assertEquals(2L, store.advancedTo, "应把位点推进到最新 messageId");
    }

    @Test
    void sweep_noTargets_doesNothing()
    {
        FakeProgressStore store = new FakeProgressStore(List.of());
        StubExtractor extractor = new StubExtractor(MemoryExtractor.ExtractResult.done(1));
        IdleSessionExtractScheduler s = newScheduler(store, extractor);

        s.sweep();

        assertEquals(0, extractor.calls.get());
    }

    @Test
    void sweep_noNewMessagesSinceFrom_doesNotAdvance()
    {
        String conv = "s1:61";
        recorder.insert(conv, "s1", 61L, "USER", "旧消息", "0", 5);
        session.commit();
        // 位点已推进到最新(2L),没有新消息可提炼
        FakeProgressStore store = new FakeProgressStore(List.of(
                new IdleSessionExtractScheduler.ExtractTarget("s1", 61L, 1L, 2L, 2L)));
        StubExtractor extractor = new StubExtractor(MemoryExtractor.ExtractResult.done(1));
        IdleSessionExtractScheduler s = newScheduler(store, extractor);

        s.sweep();

        assertEquals(0, extractor.calls.get(), "位点已到最新,不该再提炼");
        assertEquals(null, store.advancedTo);
    }

    @Test
    void sweep_extractorDoneZero_stillAdvancesProgress()
    {
        String conv = "s1:62";
        recorder.insert(conv, "s1", 62L, "USER", "提炼不出什么", "0", 5);
        session.commit();

        FakeProgressStore store = new FakeProgressStore(List.of(
                new IdleSessionExtractScheduler.ExtractTarget("s1", 62L, 1L, 0L, 1L)));
        // 提炼器执行了但没提炼出事实(done(0))→ 位点照常推进,防止同一段历史反复重试
        StubExtractor extractor = new StubExtractor(MemoryExtractor.ExtractResult.done(0));
        IdleSessionExtractScheduler s = newScheduler(store, extractor);

        s.sweep();

        assertEquals(1, extractor.calls.get());
        assertEquals(1L, store.advancedTo, "提炼 0 条也应推进位点,否则同一段历史永远重试");
    }

    @Test
    void sweep_extractorSkipped_doesNotAdvance()
    {
        String conv = "s1:64";
        recorder.insert(conv, "s1", 64L, "USER", "触发跳过", "0", 5);
        session.commit();

        FakeProgressStore store = new FakeProgressStore(List.of(
                new IdleSessionExtractScheduler.ExtractTarget("s1", 64L, 1L, 0L, 1L)));
        // LLM 失败/超时 → skipped(attempted=false)→ 位点不推进,下次重试
        StubExtractor extractor = new StubExtractor(MemoryExtractor.ExtractResult.skipped());
        IdleSessionExtractScheduler s = newScheduler(store, extractor);

        s.sweep();

        assertEquals(1, extractor.calls.get());
        assertEquals(null, store.advancedTo, "提炼被跳过时位点不推进,下轮重试");

        // 不推位点还不够:必须同时记失败并退避,否则稳定失败的会话每轮都被捞出来
        // 重试到永远,还会靠 order by update_time asc 永久霸占候选名额(队头阻塞)
        assertEquals(1, store.failures.size(), "跳过时必须记一次失败以触发退避");
    }

    @Test
    void sweep_extractorThrows_safeAndNoAdvance()
    {
        String conv = "s1:63";
        recorder.insert(conv, "s1", 63L, "USER", "触发异常", "0", 5);
        session.commit();

        FakeProgressStore store = new FakeProgressStore(List.of(
                new IdleSessionExtractScheduler.ExtractTarget("s1", 63L, 1L, 0L, 1L)));
        StubExtractor extractor = new StubExtractor(MemoryExtractor.ExtractResult.done(0))
        {
            @Override
            public MemoryExtractor.ExtractResult extract(Long userId, Long agentId, String sourceSessionId,
                                                         List<AiChatMessage> messages, Long latestMessageId)
            {
                calls.incrementAndGet();
                throw new IllegalStateException("提炼器挂了");
            }
        };
        IdleSessionExtractScheduler s = newScheduler(store, extractor);

        s.sweep(); // 不能抛

        assertEquals(1, extractor.calls.get());
        assertEquals(null, store.advancedTo, "提炼器抛异常时位点不推进,下轮重试");
    }
}
