package com.ruoyi.system.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.system.domain.AiSkill;

/**
 * 技能表 数据层
 *
 * @author ruoyi
 */
public interface AiSkillMapper
{
    public AiSkill selectAiSkillById(Long skillId);

    /** 按编码取未删除技能 */
    public AiSkill selectAiSkillByCode(String skillCode);

    public List<AiSkill> selectAiSkillList(AiSkill aiSkill);

    /** 当前用户可使用的公共技能与本人私有技能。skillIds 非空时用于运行时授权校验。 */
    public List<AiSkill> selectAvailableSkillsForUser(@Param("userId") Long userId,
                                                       @Param("skillIds") List<Long> skillIds);

    /** 含软删,删除后重加时复活避免撞 uk_skill_code */
    public AiSkill selectAiSkillByCodeIncludeDeleted(String skillCode);

    public int insertAiSkill(AiSkill aiSkill);

    public int reactivateAiSkill(AiSkill aiSkill);

    public int updateAiSkill(AiSkill aiSkill);

    public int deleteAiSkillById(Long skillId);

    public int deleteAiSkillByIds(Long[] skillIds);
}
