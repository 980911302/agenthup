package com.ruoyi.system.ai.run;

import com.ruoyi.system.domain.AiChatRun;
import com.ruoyi.system.mapper.AiChatRunMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

class AiChatRunStaleMapperTest
{
    @Test
    void markStaleInterrupted_writesFinishedTimeAsNowNotCutoff()
    {
        org.h2.jdbcx.JdbcConnectionPool dataSource = org.h2.jdbcx.JdbcConnectionPool.create(
                "jdbc:h2:mem:run_stale_" + System.nanoTime()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        Configuration configuration = new Configuration(new Environment(
                "test", new JdbcTransactionFactory(), dataSource));
        try (InputStream in = getClass().getResourceAsStream("/mapper/system/AiChatRunMapper.xml"))
        {
            new XMLMapperBuilder(in, configuration, "AiChatRunMapper.xml",
                    configuration.getSqlFragments()).parse();
        }
        catch (Exception e)
        {
            throw new AssertionError(e);
        }

        try (SqlSession session = new SqlSessionFactoryBuilder().build(configuration).openSession(true))
        {
            AiChatRunMapper mapper = session.getMapper(AiChatRunMapper.class);
            mapper.createTableForTest();

            Date created = new Date(System.currentTimeMillis() - 10 * 60 * 1000L);
            Date staleBefore = new Date(System.currentTimeMillis() - 2 * 60 * 1000L);
            Date now = new Date();

            AiChatRun run = new AiChatRun();
            run.setRunId("run-stale");
            run.setSessionId("sess-1");
            run.setAgentId(1L);
            run.setUserId(8L);
            run.setClientRequestId("c1");
            run.setActiveKey("sess-1");
            run.setStatus(ChatRunStatus.RUNNING);
            run.setInputText("hi");
            run.setCreateTime(created);
            run.setUpdateTime(created);
            mapper.insertAiChatRun(run);

            int n = mapper.markStaleInterrupted(staleBefore, now);
            assertThat(n).isEqualTo(1);
            AiChatRun saved = mapper.selectAiChatRunById("run-stale");
            assertThat(saved.getStatus()).isEqualTo(ChatRunStatus.INTERRUPTED);
            assertThat(saved.getActiveKey()).isNull();
            assertThat(Math.abs(saved.getFinishedTime().getTime() - now.getTime())).isLessThan(2000);
            assertThat(Math.abs(saved.getFinishedTime().getTime() - staleBefore.getTime()))
                    .isGreaterThan(60_000);
        }
        finally
        {
            dataSource.dispose();
        }
    }
}
