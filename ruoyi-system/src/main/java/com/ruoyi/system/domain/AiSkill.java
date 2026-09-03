package com.ruoyi.system.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * 技能表 ai_skill
 *
 * @author ruoyi
 */
public class AiSkill extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 技能ID */
    private Long skillId;

    /** 技能编码(系统引用,唯一) */
    private String skillCode;

    /** 技能名称 */
    private String skillName;

    /** 技能分类(写作/编程/分析等) */
    private String category;

    /** 技能描述 */
    private String description;

    /** 技能提示词模板(支持 {var} 占位符) */
    private String promptTemplate;

    /** 显示顺序 */
    private Integer sort;

    /** 技能状态(0正常 1停用) */
    private String status;

    /** 可见范围：PUBLIC=公共技能，PRIVATE=仅创建用户本人可用 */
    private String visibility;

    /** 私有技能所属用户；公共技能为空 */
    private Long ownerUserId;

    /** 删除标志(0存在 2删除) */
    private String delFlag;

    public Long getSkillId()
    {
        return skillId;
    }

    public void setSkillId(Long skillId)
    {
        this.skillId = skillId;
    }

    @NotBlank(message = "技能编码不能为空")
    @Size(min = 0, max = 100, message = "技能编码不能超过100个字符")
    public String getSkillCode()
    {
        return skillCode;
    }

    public void setSkillCode(String skillCode)
    {
        this.skillCode = skillCode;
    }

    @NotBlank(message = "技能名称不能为空")
    @Size(min = 0, max = 100, message = "技能名称不能超过100个字符")
    public String getSkillName()
    {
        return skillName;
    }

    public void setSkillName(String skillName)
    {
        this.skillName = skillName;
    }

    public String getCategory()
    {
        return category;
    }

    public void setCategory(String category)
    {
        this.category = category;
    }

    public String getDescription()
    {
        return description;
    }

    public void setDescription(String description)
    {
        this.description = description;
    }

    @NotBlank(message = "提示词模板不能为空")
    public String getPromptTemplate()
    {
        return promptTemplate;
    }

    public void setPromptTemplate(String promptTemplate)
    {
        this.promptTemplate = promptTemplate;
    }

    public Integer getSort()
    {
        return sort;
    }

    public void setSort(Integer sort)
    {
        this.sort = sort;
    }

    public String getStatus()
    {
        return status;
    }

    public void setStatus(String status)
    {
        this.status = status;
    }
    public String getVisibility()
    {
        return visibility;
    }
    public void setVisibility(String visibility)
    {
        this.visibility = visibility;
    }
    public Long getOwnerUserId()
    {
        return ownerUserId;
    }
    public void setOwnerUserId(Long ownerUserId)
    {
        this.ownerUserId = ownerUserId;
    }
    public String getDelFlag()
    {
        return delFlag;
    }

    public void setDelFlag(String delFlag)
    {
        this.delFlag = delFlag;
    }


    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("skillId", getSkillId())
            .append("skillCode", getSkillCode())
            .append("skillName", getSkillName())
            .append("category", getCategory())
            .append("description", getDescription())
            .append("promptTemplate", getPromptTemplate())
            .append("sort", getSort())
            .append("status", getStatus())
            .append("visibility", getVisibility())
            .append("ownerUserId", getOwnerUserId())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .append("remark", getRemark())
            .toString();
    }
}
