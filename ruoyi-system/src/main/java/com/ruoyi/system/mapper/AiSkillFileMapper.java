package com.ruoyi.system.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.system.domain.AiSkillFile;

/**
 * 技能附件 Mapper。
 *
 * @author ruoyi
 */
public interface AiSkillFileMapper
{
    /** 某技能的全部有效附件,按相对路径排序(顺序稳定,loadSkill 的清单才不会逐轮抖动)。 */
    List<AiSkillFile> selectBySkillId(Long skillId);

    AiSkillFile selectById(Long fileId);

    /** 按技能+相对路径取,用于覆盖上传时判重。 */
    AiSkillFile selectBySkillAndPath(@Param("skillId") Long skillId, @Param("relPath") String relPath);

    int insertAiSkillFile(AiSkillFile file);

    int updateAiSkillFile(AiSkillFile file);

    /** 逻辑删除单个附件。 */
    int deleteById(Long fileId);

    /** 技能被删除时一并逻辑删除其附件。 */
    int deleteBySkillId(Long skillId);
}
