package com.ruoyi.system.ai.run;

import com.ruoyi.system.domain.AiChatRunStep;
import com.ruoyi.system.mapper.AiChatRunStepMapper;
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

class AiChatRunStepMapperTest
{
    @Test
    void upsertKeepsStableIdentityAndAdvancesSnapshotFields()
    {
        org.h2.jdbcx.JdbcConnectionPool dataSource = org.h2.jdbcx.JdbcConnectionPool.create(
                "jdbc:h2:mem:run_step_" + System.nanoTime()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        Configuration configuration = new Configuration(new Environment(
                "test", new JdbcTransactionFactory(), dataSource));
        try (InputStream in = getClass().getResourceAsStream("/mapper/system/AiChatRunStepMapper.xml"))
        {
            new XMLMapperBuilder(in, configuration, "AiChatRunStepMapper.xml",
                    configuration.getSqlFragments()).parse();
        }
        catch (Exception e)
        {
            throw new AssertionError(e);
        }

        try (SqlSession session = new SqlSessionFactoryBuilder().build(configuration).openSession(true))
        {
            AiChatRunStepMapper mapper = session.getMapper(AiChatRunStepMapper.class);
            mapper.createTableForTest();
            AiChatRunStep start = step("call-1", "RUNNING", 2);
            start.setName("readFile");
            start.setStartedTime(new Date());
            mapper.upsert(start);

            AiChatRunStep end = step("call-1", "SUCCEEDED", 4);
            end.setOutputData("ok");
            end.setOutputDataPath("session-1/run-steps/1.txt");
            end.setSuccess("0");
            mapper.upsert(end);

            assertThat(mapper.selectByRunId("run-1")).singleElement().satisfies(saved -> {
                assertThat(saved.getName()).isEqualTo("readFile");
                assertThat(saved.getStatus()).isEqualTo("SUCCEEDED");
                assertThat(saved.getOutputData()).isEqualTo("ok");
                assertThat(saved.getOutputDataPath()).isEqualTo("session-1/run-steps/1.txt");
                assertThat(saved.getLastEventSeq()).isEqualTo(4);
            });
        }
        finally
        {
            dataSource.dispose();
        }
    }

    private static AiChatRunStep step(String stepId, String status, long seq)
    {
        AiChatRunStep step = new AiChatRunStep();
        step.setRunId("run-1");
        step.setSessionId("session-1");
        step.setStepId(stepId);
        step.setStepType("tool");
        step.setStatus(status);
        step.setSortNo(2L);
        step.setLastEventSeq(seq);
        return step;
    }
}
