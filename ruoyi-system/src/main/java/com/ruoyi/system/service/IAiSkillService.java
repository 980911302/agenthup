package com.ruoyi.system.service;

import java.util.List;
import com.ruoyi.system.domain.AiSkill;

/**
 * 技能 服务层
 *
 * @author ruoyi
 */
public interface IAiSkillService
{
    public AiSkill selectAiSkillById(Long skillId);

    public AiSkill selectAiSkillByCode(String skillCode);

    public List<AiSkill> selectAiSkillList(AiSkill aiSkill);

    /** 返回当前用户可在客户端 @ 使用的公共技能和本人私有技能。 */
    public List<AiSkill> selectAvailableSkillsForUser(Long userId, List<Long> skillIds);

    public int insertAiSkill(AiSkill aiSkill);

    public int updateAiSkill(AiSkill aiSkill);

    public int deleteAiSkillById(Long skillId);

    public int deleteAiSkillByIds(Long[] skillIds);
}
