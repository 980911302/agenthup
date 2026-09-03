package com.ruoyi.system.ai.event;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ruoyi.system.domain.AiChatSpecialEvent;
import com.ruoyi.system.mapper.AiChatSpecialEventMapper;
import com.ruoyi.system.tool.UiArtifact;
import com.ruoyi.system.tool.UiArtifactNames;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class UiArtifactPersistenceTest
{
    @Test
    void sessionPersistenceWritesRowAndNoneDoesNot()
    {
        AiChatSpecialEventMapper mapper = mock(AiChatSpecialEventMapper.class);
        UiArtifactEmitter emitter = new UiArtifactEmitter(mapper);
        List<String> events = new ArrayList<>();
        UiArtifactContext ctx = new UiArtifactContext(
                events::add, "s1", "run-1", 9L, 2L, "owner", "call-1");

        Map<String, Object> refs = new LinkedHashMap<>();
        refs.put("query", "请假");
        emitter.emit(ctx, UiArtifact.kbReferences(refs));

        Map<String, Object> tokens = new LinkedHashMap<>();
        tokens.put("totalTokens", 12);
        emitter.emit(ctx, new UiArtifact(UiArtifactNames.RUN_TOKEN_USAGE, 1, tokens));

        verify(mapper, times(1)).insert(any(AiChatSpecialEvent.class));
        verify(mapper, never()).deleteBySessionId(any());
        assertThat(events).hasSize(2);
    }

    @Test
    void sameEventIdUpsertsSingleRow()
    {
        withMapper((session, mapper) -> {
            UiArtifactEmitter emitter = new UiArtifactEmitter(mapper);
            UiArtifactContext ctx = new UiArtifactContext(
                    json -> { }, "s1", "run-1", 9L, 2L, "owner", "call-1");
            emitter.emit(ctx, UiArtifact.kbReferences(com.ruoyi.system.kb.search.KbReferencesUiPayload.from(
                    "一", List.of(hit("手册.md", 1L)))));
            emitter.emit(ctx, UiArtifact.kbReferences(com.ruoyi.system.kb.search.KbReferencesUiPayload.from(
                    "二", List.of(hit("制度.md", 2L)))));

            List<AiChatSpecialEvent> rows = mapper.selectBySessionId("s1");
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).getEventId()).isEqualTo("9:" + UiArtifactNames.KB_REFERENCES);
            assertThat(rows.get(0).getPayload()).contains("手册.md");
            assertThat(rows.get(0).getPayload()).contains("制度.md");
            assertThat(rows.get(0).getMessageId()).isEqualTo(9L);
            com.alibaba.fastjson2.JSONObject payload = com.alibaba.fastjson2.JSON.parseObject(rows.get(0).getPayload());
            assertThat(payload.getIntValue("fileCount")).isEqualTo(payload.getJSONArray("files").size());
            List<com.ruoyi.system.domain.AiChatSpecialEventSummary> summaries =
                    mapper.selectSummariesBySessionId("s1");
            assertThat(summaries).singleElement().satisfies(sum -> {
                SpecialEventService.fillCounts(sum);
                assertThat(sum.getFileCount()).isEqualTo(payload.getJSONArray("files").size());
            });
        });
    }

    @Test
    void boundTurnMessageIdIsPersistedWhenContextOmitsIt()
    {
        withMapper((session, mapper) -> {
            UiArtifactEmitter emitter = new UiArtifactEmitter(mapper);
            emitter.bindTurn("run-9", 42L);
            UiArtifactContext ctx = new UiArtifactContext(
                    json -> { }, "s1", "run-9", null, 2L, "owner", "call-2");
            emitter.emit(ctx, UiArtifact.kbReferences(Map.of("query", "锚点")));
            List<AiChatSpecialEvent> rows = mapper.selectBySessionId("s1");
            assertThat(rows).singleElement().satisfies(row -> {
                assertThat(row.getMessageId()).isEqualTo(42L);
                assertThat(row.getRunId()).isEqualTo("run-9");
            });
        });
    }

    @Test
    void deleteBySessionIdClearsRowsAndMapperHasNoDeleteByRunId()
    {
        assertThat(Arrays.stream(AiChatSpecialEventMapper.class.getMethods())
                .map(Method::getName))
                .doesNotContain("deleteByRunId");

        withMapper((session, mapper) -> {
            UiArtifactEmitter emitter = new UiArtifactEmitter(mapper);
            UiArtifactContext ctx = new UiArtifactContext(
                    json -> { }, "s1", "run-1", 9L, 2L, "owner", "call-1");
            emitter.emit(ctx, UiArtifact.kbReferences(Map.of("query", "留着")));
            assertThat(mapper.selectBySessionId("s1")).hasSize(1);
            mapper.deleteBySessionId("s1");
            assertThat(mapper.selectBySessionId("s1")).isEmpty();
        });
    }

    private static com.ruoyi.system.kb.vector.KbSearchHit hit(String docName, long chunkId)
    {
        com.ruoyi.system.kb.vector.KbSearchHit hit = new com.ruoyi.system.kb.vector.KbSearchHit();
        hit.setDocName(docName);
        hit.setChunkId(chunkId);
        hit.setContent("c-" + chunkId);
        return hit;
    }

    @FunctionalInterface
    private interface MapperWork
    {
        void run(SqlSession session, AiChatSpecialEventMapper mapper);
    }

    private static void withMapper(MapperWork work)
    {
        org.h2.jdbcx.JdbcConnectionPool dataSource = org.h2.jdbcx.JdbcConnectionPool.create(
                "jdbc:h2:mem:special_event_" + System.nanoTime()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        Configuration configuration = new Configuration(new Environment(
                "test", new JdbcTransactionFactory(), dataSource));
        try (InputStream in = UiArtifactPersistenceTest.class.getResourceAsStream(
                "/mapper/system/AiChatSpecialEventMapper.xml"))
        {
            assertThat(in).isNotNull();
            new XMLMapperBuilder(in, configuration, "AiChatSpecialEventMapper.xml",
                    configuration.getSqlFragments()).parse();
        }
        catch (Exception e)
        {
            throw new AssertionError(e);
        }
        try (SqlSession session = new SqlSessionFactoryBuilder().build(configuration).openSession(true))
        {
            AiChatSpecialEventMapper mapper = session.getMapper(AiChatSpecialEventMapper.class);
            mapper.createTableForTest();
            work.run(session, mapper);
        }
        finally
        {
            dataSource.dispose();
        }
    }
}
