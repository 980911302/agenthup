package com.ruoyi.system.ai.memory.longterm;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.ruoyi.system.domain.AiMemory;
import com.ruoyi.system.mapper.AiMemoryMapper;

/**
 * 记忆业务门面实现。
 *
 * <p>所有跨 store 的一致性与业务规则在此收口:新增(时间线并存)、supersede(覆盖、
 * 仅同层内)、分层检索(向量命中 → 回查台账正文)、命中回写、合规清理。
 */
@Component
public class MemoryServiceImpl implements MemoryService
{
    private static final Logger log = LoggerFactory.getLogger(MemoryServiceImpl.class);

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_SUPERSEDED = "superseded";
    public static final String SOURCE_AUTO = "auto";

    @Autowired
    private AiMemoryMapper memoryMapper;

    @Autowired
    private MemoryVectorStore vectorStore;

    @Override
    public Long add(MemoryTenant tenant, String type, String content,
                    String sourceSessionId, Long sourceMessageId)
    {
        if (tenant == null || content == null || content.isBlank())
        {
            return null;
        }
        AiMemory m = new AiMemory();
        m.setUserId(tenant.userId());
        m.setAgentId(tenant.agentId());
        m.setType(normalizeType(type));
        m.setContent(content);
        m.setStatus(STATUS_ACTIVE);
        m.setSource(SOURCE_AUTO);
        m.setSourceSessionId(sourceSessionId);
        m.setSourceMessageId(sourceMessageId);
        m.setCreateTime(new Date());
        memoryMapper.insert(m);
        return m.getMemoryId();
    }

    @Override
    public Long supersede(MemoryTenant tenant, Long oldMemoryId, String type, String newContent,
                          String sourceSessionId, Long sourceMessageId)
    {
        if (tenant == null || oldMemoryId == null || newContent == null || newContent.isBlank())
        {
            return null;
        }
        // 归属校验 + 同层校验:旧记忆必须存在且属于该租户层
        AiMemory old = memoryMapper.selectById(tenant.userId(), tenant.agentId(), oldMemoryId);
        if (old == null || !STATUS_ACTIVE.equals(old.getStatus()))
        {
            return null;
        }
        // 新增新记忆(active)
        Long newId = add(tenant, type, newContent, sourceSessionId, sourceMessageId);
        if (newId == null)
        {
            return null;
        }
        // 旧记忆标记 superseded(仅同层内)
        int updated = memoryMapper.markSuperseded(tenant.userId(), tenant.agentId(), oldMemoryId, newId);
        if (updated <= 0)
        {
            log.warn("supersede 标记失败 memoryId={} (可能已非 active)", oldMemoryId);
        }
        // 删旧向量行:向量表无 status 列,检索天然只见 active,历史留在台账(spec §4.2)
        try
        {
            vectorStore.delete(tenant, List.of(oldMemoryId));
        }
        catch (Exception e)
        {
            // 向量删除失败不阻塞 supersede —— 台账已定稿;检索可能短暂看到旧条目,由下次清理兜底
            log.warn("supersede 删旧向量失败 memoryId={}: {}", oldMemoryId, e.getMessage());
        }
        return newId;
    }

    @Override
    public AiMemory getById(MemoryTenant tenant, Long memoryId)
    {
        if (tenant == null || memoryId == null)
        {
            return null;
        }
        return memoryMapper.selectById(tenant.userId(), tenant.agentId(), memoryId);
    }

    @Override
    public List<AiMemory> search(MemoryTenant tenant, float[] query, int topK, double minScore)
    {
        if (tenant == null || query == null || query.length == 0 || topK <= 0)
        {
            return List.of();
        }
        List<MemoryVectorHit> hits = vectorStore.searchLayered(
                tenant.userId(), tenant.agentId(), query, topK, minScore);
        if (hits.isEmpty())
        {
            return List.of();
        }
        // 向量命中 → 回查台账,保持检索得分序;去重(一次查询可能两层各命中同一条不会,
        // 但 memoryId 是主键,天然唯一)
        List<Long> ids = new ArrayList<>();
        for (MemoryVectorHit h : hits)
        {
            ids.add(h.memoryId());
        }
        Map<Long, AiMemory> byId = new HashMap<>();
        for (AiMemory m : memoryMapper.selectByIds(tenant.userId(), ids))
        {
            byId.put(m.getMemoryId(), m);
        }
        List<AiMemory> out = new ArrayList<>();
        for (MemoryVectorHit h : hits)
        {
            AiMemory m = byId.get(h.memoryId());
            if (m != null && STATUS_ACTIVE.equals(m.getStatus()))
            {
                out.add(m);
            }
        }
        return out;
    }

    @Override
    public void onHit(Long memoryId)
    {
        if (memoryId == null)
        {
            return;
        }
        try
        {
            memoryMapper.bumpHit(memoryId);
        }
        catch (Exception e)
        {
            log.debug("记忆命中回写失败 memoryId={}: {}", memoryId, e.getMessage());
        }
    }

    @Override
    public void deleteByUser(Long userId)
    {
        if (userId == null)
        {
            return;
        }
        try
        {
            memoryMapper.deleteByUser(userId);
            vectorStore.deleteByUser(userId);
        }
        catch (Exception e)
        {
            log.warn("合规清理失败 userId={}: {}", userId, e.getMessage());
        }
    }

    /** 类型白名单,未知类型收敛到 fact(避免脏值进入 type 列)。 */
    private static String normalizeType(String type)
    {
        if (type == null)
        {
            return "fact";
        }
        return switch (type)
        {
            case "fact", "preference", "event", "goal", "rule" -> type;
            default -> "fact";
        };
    }
}
