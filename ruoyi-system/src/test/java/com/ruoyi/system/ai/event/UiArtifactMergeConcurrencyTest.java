package com.ruoyi.system.ai.event;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.system.domain.AiChatSpecialEvent;
import com.ruoyi.system.kb.search.KbReferencesUiPayload;
import com.ruoyi.system.kb.vector.KbSearchHit;
import com.ruoyi.system.mapper.AiChatSpecialEventMapper;
import com.ruoyi.system.tool.UiArtifact;
import com.ruoyi.system.tool.UiArtifactNames;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UiArtifactMergeConcurrencyTest
{
    @Test
    void concurrentMergesKeepBothFiles() throws Exception
    {
        org.h2.jdbcx.JdbcConnectionPool dataSource = org.h2.jdbcx.JdbcConnectionPool.create(
                "jdbc:h2:mem:merge_conc_" + System.nanoTime()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        Configuration configuration = new Configuration(new Environment(
                "test", new JdbcTransactionFactory(), dataSource));
        try (InputStream in = getClass().getResourceAsStream("/mapper/system/AiChatSpecialEventMapper.xml"))
        {
            new XMLMapperBuilder(in, configuration, "AiChatSpecialEventMapper.xml",
                    configuration.getSqlFragments()).parse();
        }
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
        try (SqlSession setup = factory.openSession(true))
        {
            setup.getMapper(AiChatSpecialEventMapper.class).createTableForTest();
        }

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicReference<Throwable> error = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        pool.execute(() -> emitOne(factory, start, done, error, "手册.md", 11L));
        pool.execute(() -> emitOne(factory, start, done, error, "制度.md", 12L));
        start.countDown();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();
        assertThat(error.get()).isNull();

        try (SqlSession session = factory.openSession(true))
        {
            AiChatSpecialEventMapper mapper = session.getMapper(AiChatSpecialEventMapper.class);
            List<AiChatSpecialEvent> rows = mapper.selectBySessionId("s1");
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).getEventId()).isEqualTo("9:" + UiArtifactNames.KB_REFERENCES);
            JSONObject payload = JSON.parseObject(rows.get(0).getPayload());
            assertThat(payload.getIntValue("fileCount")).isEqualTo(2);
            assertThat(payload.getJSONArray("files")).hasSize(2);
        }
        dataSource.dispose();
    }

    @Test
    void retryExhaustedLogsAndDoesNotThrow()
    {
        AiChatSpecialEventMapper mapper = mock(AiChatSpecialEventMapper.class);
        AiChatSpecialEvent existing = new AiChatSpecialEvent();
        existing.setId(1L);
        existing.setVersion(3);
        existing.setPayload("{\"schemaVersion\":2,\"queries\":[\"旧\"],\"fileCount\":1,\"chunkCount\":0,\"files\":[]}");
        when(mapper.selectBySessionEvent("s1", "9:" + UiArtifactNames.KB_REFERENCES)).thenReturn(existing);
        when(mapper.updateIfVersion(any())).thenReturn(0);
        UiArtifactEmitter emitter = new UiArtifactEmitter(mapper);

        assertThatCode(() -> emitter.emit(
                new UiArtifactContext(json -> { }, "s1", "run-1", 9L, 2L, "owner", "call-1"),
                UiArtifact.kbReferences(KbReferencesUiPayload.from("新", List.of()))))
                .doesNotThrowAnyException();
    }

    private static void emitOne(SqlSessionFactory factory, CountDownLatch start, CountDownLatch done,
                                AtomicReference<Throwable> error, String docName, long chunkId)
    {
        try (SqlSession session = factory.openSession(true))
        {
            start.await(5, TimeUnit.SECONDS);
            AiChatSpecialEventMapper mapper = session.getMapper(AiChatSpecialEventMapper.class);
            UiArtifactEmitter emitter = new UiArtifactEmitter(mapper);
            KbSearchHit hit = new KbSearchHit();
            hit.setChunkId(chunkId);
            hit.setDocName(docName);
            hit.setContent("正文 " + docName);
            emitter.emit(new UiArtifactContext(json -> { }, "s1", "run-1", 9L, 2L, "owner", "call-" + chunkId),
                    UiArtifact.kbReferences(KbReferencesUiPayload.from(docName, List.of(hit))));
        }
        catch (Throwable ex)
        {
            error.compareAndSet(null, ex);
        }
        finally
        {
            done.countDown();
        }
    }
}
