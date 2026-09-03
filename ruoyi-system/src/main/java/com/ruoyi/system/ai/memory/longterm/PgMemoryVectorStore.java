package com.ruoyi.system.ai.memory.longterm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import com.ruoyi.common.datasource.DataSourceScope;
import com.ruoyi.common.enums.DataSourceType;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.mapper.MemVectorMapper;

/**
 * PostgreSQL + pgvector 记忆向量存储。
 * <p>按维度路由到预建表 mem_vector_{dim}(768/1024/1536/3072),同
 * {@code PgVectorKbVectorStore} 的分表范式。维度不配置,运行时从
 * {@code embedding.length} 取,不在预建维度直接抛。
 * <p>向量表只存在于 PostgreSQL 从库。这里自行切换数据源，不能把这个约束留给调用方：
 * 记忆提炼通常从 MySQL 主库事务进入，若不切换会把 PostgreSQL SQL 发到 MySQL，且写侧的
 * 容错逻辑会吞掉异常，最终表现为「台账有记忆、向量表为空」。
 */
@Component
@Primary
public class PgMemoryVectorStore implements MemoryVectorStore
{
    private static final Logger log = LoggerFactory.getLogger(PgMemoryVectorStore.class);

    /** 预建维度;未覆盖的报错,不动态建表 */
    private static final Set<Integer> SUPPORTED_DIMS = Set.of(768, 1024, 1536, 3072);

    @Autowired
    private MemVectorMapper memVectorMapper;

    @Override
    public void upsert(MemoryTenant tenant, Long memoryId, float[] embedding)
    {
        if (tenant == null || memoryId == null || embedding == null || embedding.length == 0)
        {
            return;
        }
        int dim = embedding.length;
        requireDim(dim);
        DataSourceScope.runOn(DataSourceType.SLAVE, () ->
                memVectorMapper.upsert(dim, memoryId, tenant.userId(), tenant.agentId(),
                        toVectorLiteral(embedding)));
    }

    @Override
    public List<MemoryVectorHit> searchLayered(Long userId, Long agentId,
                                               float[] query, int topK, double minScore)
    {
        if (userId == null || query == null || query.length == 0 || topK <= 0)
        {
            return List.of();
        }
        int dim = query.length;
        requireDim(dim);
        List<Map<String, Object>> rows = DataSourceScope.runOn(DataSourceType.SLAVE,
                () -> memVectorMapper.searchLayered(dim, userId, agentId,
                        toVectorLiteral(query), topK));
        if (rows == null || rows.isEmpty())
        {
            return List.of();
        }
        List<MemoryVectorHit> hits = new ArrayList<>();
        for (Map<String, Object> row : rows)
        {
            Long memoryId = toLong(row.get("memoryId"));
            if (memoryId == null)
            {
                memoryId = toLong(row.get("memoryid"));
            }
            double score = toDouble(row.get("score"));
            if (memoryId == null || score < minScore)
            {
                continue;
            }
            hits.add(new MemoryVectorHit(memoryId, score));
        }
        return hits;
    }

    @Override
    public void delete(MemoryTenant tenant, List<Long> memoryIds)
    {
        if (tenant == null || memoryIds == null || memoryIds.isEmpty())
        {
            return;
        }
        // 维度可能变更,各维表都清一遍(同 deleteByKb);SQL 强制 user_id 过滤,跨租户删除不可能发生
        for (int dim : SUPPORTED_DIMS)
        {
            DataSourceScope.runOn(DataSourceType.SLAVE,
                    () -> memVectorMapper.deleteByMemoryIds(dim, tenant.userId(), memoryIds));
        }
    }

    @Override
    public void deleteByUser(Long userId)
    {
        if (userId == null)
        {
            return;
        }
        for (int dim : SUPPORTED_DIMS)
        {
            DataSourceScope.runOn(DataSourceType.SLAVE,
                    () -> memVectorMapper.deleteByUser(dim, userId));
        }
    }

    /** 预建维度白名单;未覆盖的报错,不动态建表。包级可见便于单测(同 ContextBudget 的静态纯计算)。 */
    static void requireDim(int dim)
    {
        if (!SUPPORTED_DIMS.contains(dim))
        {
            throw new ServiceException(
                "不支持的向量维度 " + dim + ",预建维度: " + SUPPORTED_DIMS
                    + "。请换模型或扩展 sql/init/11_mem_pg.sql 预建表。");
        }
    }

    /** float[] → pgvector 字面量 '[0.1,0.2,...]' */
    static String toVectorLiteral(float[] v)
    {
        StringBuilder sb = new StringBuilder(v.length * 8);
        sb.append('[');
        for (int i = 0; i < v.length; i++)
        {
            if (i > 0)
            {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    private static Long toLong(Object o)
    {
        if (o == null)
        {
            return null;
        }
        if (o instanceof Number n)
        {
            return n.longValue();
        }
        try
        {
            return Long.parseLong(String.valueOf(o));
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    private static double toDouble(Object o)
    {
        if (o == null)
        {
            return 0;
        }
        if (o instanceof Number n)
        {
            return n.doubleValue();
        }
        try
        {
            return Double.parseDouble(String.valueOf(o));
        }
        catch (NumberFormatException e)
        {
            return 0;
        }
    }
}
