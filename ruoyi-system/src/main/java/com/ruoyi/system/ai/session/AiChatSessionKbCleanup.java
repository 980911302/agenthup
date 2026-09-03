package com.ruoyi.system.ai.session;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.annotation.DataSource;
import com.ruoyi.common.enums.DataSourceType;
import com.ruoyi.system.mapper.AiChatSessionKbMapper;

/**
 * 清理 {@code ai_chat_session_kb}(MySQL)。
 * <p>
 * 知识库删除跑在 SLAVE/PG 事务里。动态数据源在事务开启后绑死连接,
 * 同线程再 {@code DataSourceScope.runOn(MASTER)} 仍走 PG。
 * {@code delete from ai_chat_session_kb} 在 PG 上失败,异常若被吞,
 * 后续 {@code update kb_knowledge} 会变成 25P02。
 * 这里新开 MASTER 事务,拿到真正的 MySQL 连接。
 */
@Service
@DataSource(DataSourceType.MASTER)
public class AiChatSessionKbCleanup
{
    @Autowired
    private AiChatSessionKbMapper aiChatSessionKbMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deleteByKbId(Long kbId)
    {
        if (kbId == null)
        {
            return;
        }
        aiChatSessionKbMapper.deleteByKbId(kbId);
    }
}
