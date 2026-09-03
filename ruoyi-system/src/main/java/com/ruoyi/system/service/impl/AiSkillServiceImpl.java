package com.ruoyi.system.service.impl;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.bizcode.BizCodeGenerator;
import com.ruoyi.system.ai.AiConfigChangedEvent;
import com.ruoyi.system.domain.AiSkill;
import com.ruoyi.system.mapper.AiSkillMapper;
import com.ruoyi.system.service.IAiSkillService;

/**
 * 技能 服务层实现
 *
 * @author ruoyi
 */
@Service
public class AiSkillServiceImpl implements IAiSkillService
{
    @Autowired
    private AiSkillMapper aiSkillMapper;

    @Autowired
    private BizCodeGenerator bizCodeGenerator;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Override
    public AiSkill selectAiSkillById(Long skillId)
    {
        return aiSkillMapper.selectAiSkillById(skillId);
    }

    @Override
    public AiSkill selectAiSkillByCode(String skillCode)
    {
        return aiSkillMapper.selectAiSkillByCode(skillCode);
    }

    @Override
    public List<AiSkill> selectAiSkillList(AiSkill aiSkill)
    {
        return aiSkillMapper.selectAiSkillList(aiSkill);
    }

    @Override
    public List<AiSkill> selectAvailableSkillsForUser(Long userId, List<Long> skillIds)
    {
        if (userId == null)
        {
            return List.of();
        }
        return aiSkillMapper.selectAvailableSkillsForUser(userId, skillIds);
    }

    @Override
    public int insertAiSkill(AiSkill aiSkill)
    {
        if (aiSkill.getSkillCode() == null || aiSkill.getSkillCode().isEmpty())
        {
            aiSkill.setSkillCode(bizCodeGenerator.next("SKL"));
        }
        // 软删后同 code 重建:复活旧行,避免撞 uk_skill_code
        AiSkill existing = aiSkillMapper.selectAiSkillByCodeIncludeDeleted(aiSkill.getSkillCode());
        if (existing != null)
        {
            if (!"2".equals(existing.getDelFlag()))
            {
                throw new ServiceException("技能编码已存在: " + aiSkill.getSkillCode());
            }
            aiSkill.setSkillId(existing.getSkillId());
            int rows = aiSkillMapper.reactivateAiSkill(aiSkill);
            publishSkillChanged(aiSkill.getSkillId());
            return rows;
        }
        int rows = aiSkillMapper.insertAiSkill(aiSkill);
        publishSkillChanged(aiSkill.getSkillId());
        return rows;
    }

    @Override
    public int updateAiSkill(AiSkill aiSkill)
    {
        int rows = aiSkillMapper.updateAiSkill(aiSkill);
        publishSkillChanged(aiSkill.getSkillId());
        return rows;
    }

    @Override
    public int deleteAiSkillById(Long skillId)
    {
        int rows = aiSkillMapper.deleteAiSkillById(skillId);
        publishSkillChanged(skillId);
        return rows;
    }

    @Override
    public int deleteAiSkillByIds(Long[] skillIds)
    {
        int rows = aiSkillMapper.deleteAiSkillByIds(skillIds);
        if (skillIds != null)
        {
            for (Long skillId : skillIds)
            {
                publishSkillChanged(skillId);
            }
        }
        return rows;
    }

    /** 缓存失效属尽力而为的旁路,广播失败不能让配置修改回滚 */
    private void publishSkillChanged(Long skillId)
    {
        if (skillId == null)
        {
            return;
        }
        try
        {
            eventPublisher.publishEvent(new AiConfigChangedEvent(AiConfigChangedEvent.Kind.SKILL, skillId));
        }
        catch (RuntimeException e)
        {
            org.slf4j.LoggerFactory.getLogger(AiSkillServiceImpl.class)
                    .warn("广播技能变更事件失败,装配缓存可能仍持旧配置: skillId={}", skillId, e);
        }
    }
}
