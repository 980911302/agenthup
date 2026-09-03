package com.ruoyi.system.service.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.ai.memory.longterm.MemoryTenant;
import com.ruoyi.system.ai.memory.longterm.MemoryVectorStore;
import com.ruoyi.system.domain.AiMemory;
import com.ruoyi.system.mapper.AiMemoryMapper;
import com.ruoyi.system.service.IAiMemoryAdminService;

/** 长期记忆管理服务实现。 */
@Service
public class AiMemoryAdminServiceImpl implements IAiMemoryAdminService
{
    @Autowired
    private AiMemoryMapper memoryMapper;

    @Autowired
    private MemoryVectorStore vectorStore;

    @Override
    public List<AiMemory> selectAiMemoryList(AiMemory query)
    {
        return memoryMapper.selectAdminList(query == null ? new AiMemory() : query);
    }

    /**
     * 先删向量、后软删台账。向量删除失败时不隐藏台账，管理员可直接重试，不会制造
     * “看不到正文但向量仍能召回”的残留。MySQL 与 PostgreSQL 不共享事务，因此该顺序
     * 是以检索安全优先的补偿策略。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteAiMemoryByIds(Long[] memoryIds)
    {
        if (memoryIds == null || memoryIds.length == 0)
        {
            return 0;
        }
        List<Long> ids = new ArrayList<>(new LinkedHashSet<>(Arrays.asList(memoryIds)));
        if (ids.contains(null))
        {
            throw new ServiceException("记忆主键不能为空");
        }
        List<AiMemory> memories = memoryMapper.selectAdminByIds(ids);
        if (memories.size() != ids.size())
        {
            throw new ServiceException("存在已删除或不存在的长期记忆，请刷新后重试");
        }
        for (AiMemory memory : memories)
        {
            vectorStore.delete(MemoryTenant.ofAgent(memory.getUserId(), memory.getAgentId()),
                    List.of(memory.getMemoryId()));
        }
        int rows = memoryMapper.softDeleteByIds(ids);
        if (rows != ids.size())
        {
            throw new ServiceException("长期记忆删除未完整执行，请刷新后重试");
        }
        return rows;
    }
}
