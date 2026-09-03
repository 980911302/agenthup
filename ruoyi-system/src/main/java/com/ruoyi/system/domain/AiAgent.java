package com.ruoyi.system.domain;

import java.util.List;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * 智能体表 ai_agent
 *
 * @author ruoyi
 */
public class AiAgent extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 智能体ID */
    private Long agentId;

    /** 智能体编码(系统引用,唯一) */
    private String agentCode;

    /** 智能体名称 */
    private String agentName;

    /** 智能体描述 */
    private String agentDesc;

    /** 智能体角色(系统提示词) */
    private String agentRole;

    /** 是否加载本地文档agents.md(0否 1是) */
    private String loadLocalDoc;

    /** 是否公共智能体(0否 1是) */
    private String isPublic;

    /** 绑定模型编码(关联ai_model.model_code) */
    private String modelCode;

    /** 绑定生图模型编码(关联ai_model.model_code,modelType=IMAGE);装配期自动生成drawImage工具 */
    private String imageModelCode;

    /** 绑定视频模型编码(关联ai_model.model_code,modelType=VIDEO);装配期自动生成drawVideo工具 */
    private String videoModelCode;

    /** 绑定语音合成模型编码(关联ai_model.model_code,modelType=TTS);装配期自动生成speak工具 */
    private String ttsModelCode;

    /** 显示顺序 */
    private Integer sort;

    /** 状态(0正常 1停用) */
    private String status;

    /** 删除标志(0存在 2删除) */
    private String delFlag;

    /** 列表访问范围：当前登录用户名（查询条件，非表字段） */
    private String accessUserName;

    // ---- 关联字段(非数据库列,用于新增/修改/详情) ----
    /** 关联技能ID列表 */
    private Long[] skillIds;

    /** 关联工具ID列表 */
    private Long[] toolIds;

    /** 子智能体配置列表 */
    private List<AiAgentChild> childAgents;

    /** 智能体图标(emoji) */
    private String icon;

    /** 主题色索引(0-7,空则按编码自动取色) */
    private String theme;

    // ---- 展示字段(关联查询) ----
    /** 模型展示名称(关联ai_model) */
    private String modelDisplayName;

    public String getIcon()
    {
        return icon;
    }

    public void setIcon(String icon)
    {
        this.icon = icon;
    }

    public String getTheme()
    {
        return theme;
    }

    public void setTheme(String theme)
    {
        this.theme = theme;
    }

    /** 关联技能数量(列表页展示用) */
    private Integer skillCount;

    /** 关联工具数量(列表页展示用) */
    private Integer toolCount;

    /** 子智能体数量(列表页展示用) */
    private Integer childCount;

    public Integer getSkillCount()
    {
        return skillCount;
    }

    public void setSkillCount(Integer skillCount)
    {
        this.skillCount = skillCount;
    }

    public Integer getToolCount()
    {
        return toolCount;
    }

    public void setToolCount(Integer toolCount)
    {
        this.toolCount = toolCount;
    }

    public Integer getChildCount()
    {
        return childCount;
    }

    public void setChildCount(Integer childCount)
    {
        this.childCount = childCount;
    }

    public Long getAgentId()
    {
        return agentId;
    }

    public void setAgentId(Long agentId)
    {
        this.agentId = agentId;
    }

    @NotBlank(message = "智能体编码不能为空")
    @Size(min = 0, max = 100, message = "智能体编码不能超过100个字符")
    public String getAgentCode()
    {
        return agentCode;
    }

    public void setAgentCode(String agentCode)
    {
        this.agentCode = agentCode;
    }

    @NotBlank(message = "智能体名称不能为空")
    @Size(min = 0, max = 100, message = "智能体名称不能超过100个字符")
    public String getAgentName()
    {
        return agentName;
    }

    public void setAgentName(String agentName)
    {
        this.agentName = agentName;
    }

    public String getAgentDesc()
    {
        return agentDesc;
    }

    public void setAgentDesc(String agentDesc)
    {
        this.agentDesc = agentDesc;
    }

    public String getAgentRole()
    {
        return agentRole;
    }

    public void setAgentRole(String agentRole)
    {
        this.agentRole = agentRole;
    }

    public String getLoadLocalDoc()
    {
        return loadLocalDoc;
    }

    public void setLoadLocalDoc(String loadLocalDoc)
    {
        this.loadLocalDoc = loadLocalDoc;
    }

    public String getIsPublic()
    {
        return isPublic;
    }

    public void setIsPublic(String isPublic)
    {
        this.isPublic = isPublic;
    }

    public String getModelCode()
    {
        return modelCode;
    }

    public void setModelCode(String modelCode)
    {
        this.modelCode = modelCode;
    }

    public String getImageModelCode()
    {
        return imageModelCode;
    }

    public void setImageModelCode(String imageModelCode)
    {
        this.imageModelCode = imageModelCode;
    }

    public String getVideoModelCode()
    {
        return videoModelCode;
    }

    public void setVideoModelCode(String videoModelCode)
    {
        this.videoModelCode = videoModelCode;
    }

    public String getTtsModelCode()
    {
        return ttsModelCode;
    }

    public void setTtsModelCode(String ttsModelCode)
    {
        this.ttsModelCode = ttsModelCode;
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
    public String getDelFlag()
    {
        return delFlag;
    }

    public void setDelFlag(String delFlag)
    {
        this.delFlag = delFlag;
    }

    public String getAccessUserName()
    {
        return accessUserName;
    }

    public void setAccessUserName(String accessUserName)
    {
        this.accessUserName = accessUserName;
    }


    public Long[] getSkillIds()
    {
        return skillIds;
    }

    public void setSkillIds(Long[] skillIds)
    {
        this.skillIds = skillIds;
    }

    public Long[] getToolIds()
    {
        return toolIds;
    }

    public void setToolIds(Long[] toolIds)
    {
        this.toolIds = toolIds;
    }

    public List<AiAgentChild> getChildAgents()
    {
        return childAgents;
    }

    public void setChildAgents(List<AiAgentChild> childAgents)
    {
        this.childAgents = childAgents;
    }

    public String getModelDisplayName()
    {
        return modelDisplayName;
    }

    public void setModelDisplayName(String modelDisplayName)
    {
        this.modelDisplayName = modelDisplayName;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this, ToStringStyle.MULTI_LINE_STYLE)
            .append("agentId", getAgentId())
            .append("agentCode", getAgentCode())
            .append("agentName", getAgentName())
            .append("agentDesc", getAgentDesc())
            .append("agentRole", getAgentRole())
            .append("loadLocalDoc", getLoadLocalDoc())
            .append("isPublic", getIsPublic())
            .append("modelCode", getModelCode())
            .append("imageModelCode", getImageModelCode())
            .append("videoModelCode", getVideoModelCode())
            .append("ttsModelCode", getTtsModelCode())
            .append("sort", getSort())
            .append("status", getStatus())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .append("remark", getRemark())
            .toString();
    }
}
