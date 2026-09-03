package com.ruoyi.system.ai.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.annotation.DataSource;
import com.ruoyi.common.enums.DataSourceType;

/**
 * 知识库删在 SLAVE/PG 事务里。ai_chat_session_kb 在 MySQL，
 * 同连接执行会让 PG 报 relation does not exist，吞掉后后续 SQL 变 25P02。
 * 清理必须新开 MASTER 事务，拿到真正的 MySQL 连接。
 */
class AiChatSessionKbCleanupTest
{
    @Test
    void cleanupUsesNewMasterTransaction() throws Exception
    {
        DataSource ds = AiChatSessionKbCleanup.class.getAnnotation(DataSource.class);
        assertNotNull(ds);
        assertEquals(DataSourceType.MASTER, ds.value());

        Method method = AiChatSessionKbCleanup.class.getMethod("deleteByKbId", Long.class);
        Transactional tx = method.getAnnotation(Transactional.class);
        assertNotNull(tx);
        assertEquals(Propagation.REQUIRES_NEW, tx.propagation());
    }
}
