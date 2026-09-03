package com.ruoyi.system.ai.memory.longterm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import com.ruoyi.common.datasource.DataSourceScope;
import com.ruoyi.common.enums.DataSourceType;

/**
 * 空闲会话兜底提炼的默认位点存储(ai_memory_extract_progress 表)。
 *
 * <p>用 {@link JdbcTemplate} 直查,不改动任何共享 mapper XML/domain:
 * 提炼位点与 {@code ai_chat_session} 主表解耦,避免影响其它子代理正在改的会话链路。
 *
 * <p><b>候选扫描 SQL</b>:找「N 分钟无新消息(update_time 未刷新)、提炼位点 &lt; 最新消息、
 * 活跃未删除、普通对话、有主 agent」的会话。位点不存在(首次提炼)视为 0。
 * 会话在 {@code ChatMessageRecorder.persist} 里每落一条消息都会刷 {@code update_time}
 * (addSessionMessageCount),所以「空闲」直接用 session.update_time 判 —— 消息落库即刷新,
 * 不需要额外维护 last_message_time。
 *
 * <p>只读候选查询走 MASTER(提炼位点推进是写,整链路都在 MASTER 上做最省心;
 * 该扫描是低频基础设施,不占读侧)。
 */
@Component
public class IdleSessionExtractProgressStore implements IdleSessionExtractScheduler.ProgressStore
{
    private static final Logger log = LoggerFactory.getLogger(IdleSessionExtractProgressStore.class);

    /** 单次扫描最多处理多少个会话(护栏,防止一次拖太久)。 */
    @Value("${ai.memory.extract.sweep-max-sessions:20}")
    private int sweepMaxSessions;

    /** 空闲多久触发兜底提炼(分钟)。 */
    @Value("${ai.memory.extract.idle-sweep-minutes:30}")
    private int idleSweepMinutes;

    /** 连续失败多少次后放弃该会话(不再进候选,腾出名额给别人)。 */
    @Value("${ai.memory.extract.max-failures:5}")
    private int maxFailures;

    /** 退避基数(分钟):第 n 次失败等 base * 2^(n-1),封顶 max-backoff-minutes。 */
    @Value("${ai.memory.extract.retry-backoff-minutes:10}")
    private int retryBackoffMinutes;

    /** 退避上限(分钟)。 */
    @Value("${ai.memory.extract.max-backoff-minutes:240}")
    private int maxBackoffMinutes;

    private final JdbcTemplate jdbcTemplate;

    public IdleSessionExtractProgressStore(JdbcTemplate jdbcTemplate)
    {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<IdleSessionExtractScheduler.ExtractTarget> candidates()
    {
        int idleMinutes = Math.max(idleSweepMinutes, 1);
        int limit = Math.max(sweepMaxSessions, 1);
        String sql = """
                select s.session_id,
                       s.user_id,
                       (select sa.agent_id from ai_chat_session_agent sa
                         where sa.session_id = s.session_id and sa.role = 'supervisor'
                         order by sa.id asc limit 1) as agent_id,
                       ifnull(p.extract_to_message_id, 0) as from_id,
                       (select max(m.message_id) from ai_chat_message m
                         where m.session_id = s.session_id) as latest_id
                  from ai_chat_session s
                  left join ai_memory_extract_progress p on p.session_id = s.session_id
                 where s.session_type = 'chat'
                   and s.status = '0'
                   and s.del_flag = '0'
                   and s.user_id is not null
                   and s.update_time < date_sub(now(), interval ? minute)
                   and (p.next_retry_time is null or p.next_retry_time <= now())
                   and ifnull(p.fail_count, 0) < ?
                   and (select max(m.message_id) from ai_chat_message m
                         where m.session_id = s.session_id) > ifnull(p.extract_to_message_id, 0)
                   and exists (select 1 from ai_chat_session_agent sa
                         where sa.session_id = s.session_id and sa.role = 'supervisor')
                 order by s.update_time asc
                 limit ?
                """;
        try
        {
            List<IdleSessionExtractScheduler.ExtractTarget> out = new ArrayList<>();
            List<Map<String, Object>> rows = DataSourceScope.runOn(DataSourceType.MASTER,
                    () -> jdbcTemplate.queryForList(sql, idleMinutes, maxFailures, limit));
            for (Map<String, Object> row : rows)
            {
                String sessionId = asString(row.get("session_id"));
                Long userId = asLong(row.get("user_id"));
                Long agentId = asLong(row.get("agent_id"));
                long fromId = asLong(row.get("from_id"));
                long latestId = asLong(row.get("latest_id"));
                if (sessionId == null || userId == null || agentId == null || latestId <= 0)
                {
                    continue;
                }
                out.add(new IdleSessionExtractScheduler.ExtractTarget(
                        sessionId, agentId, userId, fromId, latestId));
            }
            return out;
        }
        catch (Exception e)
        {
            // 候选查询失败不算提炼失败:本次扫描跳过,下轮重试
            log.warn("空闲会话提炼候选扫描失败: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public void advance(String sessionId, Long agentId, Long userId, long extractToMessageId)
    {
        if (sessionId == null || agentId == null || userId == null)
        {
            return;
        }
        try
        {
            // greatest():位点只进不退。压缩搭车与空闲扫描都会推进,搭车的 boundaryId
            // 可能小于扫描已推到的 latest;直接覆盖会让位点回退,导致重复提炼。
            String sql = """
                    insert into ai_memory_extract_progress
                      (session_id, agent_id, user_id, extract_to_message_id,
                       fail_count, next_retry_time, update_time)
                    values (?, ?, ?, ?, 0, null, now())
                    on duplicate key update
                      agent_id = values(agent_id),
                      user_id = values(user_id),
                      extract_to_message_id =
                          greatest(ifnull(extract_to_message_id, 0), values(extract_to_message_id)),
                      fail_count = 0,
                      next_retry_time = null,
                      update_time = now()
                    """;
            DataSourceScope.runOn(DataSourceType.MASTER, () -> jdbcTemplate.update(
                    sql, sessionId, agentId, userId, extractToMessageId));
        }
        catch (Exception e)
        {
            // 位点推进失败最坏就是下次重提炼一遍,由提炼器 hash/相似度去重兜底
            log.warn("空闲会话提炼位点推进失败 session={}: {}", sessionId, e.getMessage());
        }
    }

    @Override
    public void markFailure(String sessionId, Long agentId, Long userId)
    {
        if (sessionId == null || agentId == null || userId == null)
        {
            return;
        }
        try
        {
            // 指数退避:第 n 次失败等 base * 2^(n-1) 分钟,封顶 maxBackoffMinutes。
            // fail_count 达到 maxFailures 后候选 SQL 直接把它排除,不再重试(也就不再占名额)。
            String sql = """
                    insert into ai_memory_extract_progress
                      (session_id, agent_id, user_id, extract_to_message_id,
                       fail_count, next_retry_time, update_time)
                    values (?, ?, ?, null, 1,
                            date_add(now(), interval ? minute), now())
                    on duplicate key update
                      -- 顺序要紧:MySQL 的 ON DUPLICATE 赋值自左向右求值,
                      -- next_retry_time 必须排在自增之前才读得到「本次之前」的 fail_count。
                      -- 于是第 n 次失败退避 base * 2^(n-1):10/20/40/80…,封顶 max。
                      next_retry_time = date_add(now(), interval
                          least(? * pow(2, ifnull(fail_count, 0)), ?) minute),
                      fail_count = ifnull(fail_count, 0) + 1,
                      update_time = now()
                    """;
            DataSourceScope.runOn(DataSourceType.MASTER, () -> jdbcTemplate.update(
                    sql, sessionId, agentId, userId,
                    retryBackoffMinutes, retryBackoffMinutes, maxBackoffMinutes));
        }
        catch (Exception e)
        {
            // 记失败本身失败了:最坏退化成旧行为(下轮再试),不影响正确性
            log.warn("记忆提炼失败计数写入失败 session={}: {}", sessionId, e.getMessage());
        }
    }

    private static String asString(Object v)
    {
        return v == null ? null : String.valueOf(v);
    }

    private static Long asLong(Object v)
    {
        if (v == null)
        {
            return 0L;
        }
        if (v instanceof Number n)
        {
            return n.longValue();
        }
        try
        {
            return Long.valueOf(String.valueOf(v));
        }
        catch (NumberFormatException e)
        {
            return 0L;
        }
    }
}
