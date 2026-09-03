package com.ruoyi.system.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * 模型表 ai_model
 * 
 * @author ruoyi
 */
public class AiModel extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 模型ID */
    private Long modelId;

    /** 模型编码(逻辑唯一标识,如gpt-4o) */
    private String modelCode;

    /** 对外展示名称(界面展示) */
    private String displayName;

    /** 模型类型(CHAT/EMBEDDING/RERANK/IMAGE/VIDEO/TTS/STT/MODERATION) */
    private String modelType;

    /** 最大上下文长度(tokens) */
    private Integer contextWindow;

    /** 最大输出tokens(可空) */
    private Integer maxOutputTokens;

    /** 是否开启推理(0关闭 1开启；运行时决定请求参数及思考内容是否记录/展示) */
    private String reasoningEnabled;

    /**
     * 是否支持视觉理解(0否 1是)。
     *
     * @deprecated 已被 {@link #inputModalities} 取代 —— 输入模态不是一个开关,图片/文档/
     *             视频/音频互相独立。保留仅为兼容存量数据与迁移期读取,新代码一律走
     *             {@code ModelInputModalities}。
     */
    @Deprecated
    private String visionEnabled;

    /**
     * 支持的输入模态,逗号分隔,取值 image / file / video / audio(text 是前提,不入库)。
     * <p>空表示纯文本模型。解析与判定统一走 {@code ModelInputModalities}。
     */
    private String inputModalities;

    /** 显示顺序 */
    private Integer sort;

    /** 模型状态(0正常 1停用) */
    private String status;

    /** 可见范围(PUBLIC公共/PRIVATE私人) */
    private String visibility;

    /** 私有模型的归属用户ID(PUBLIC为空) */
    private Long ownerUserId;

    /** 删除标志(0存在 2删除) */
    private String delFlag;

    public Long getModelId()
    {
        return modelId;
    }

    public void setModelId(Long modelId)
    {
        this.modelId = modelId;
    }

    @NotBlank(message = "模型编码不能为空")
    @Size(min = 0, max = 100, message = "模型编码不能超过100个字符")
    public String getModelCode()
    {
        return modelCode;
    }

    public void setModelCode(String modelCode)
    {
        this.modelCode = modelCode;
    }

    @NotBlank(message = "对外展示名称不能为空")
    @Size(min = 0, max = 100, message = "对外展示名称不能超过100个字符")
    public String getDisplayName()
    {
        return displayName;
    }

    public void setDisplayName(String displayName)
    {
        this.displayName = displayName;
    }

    @NotBlank(message = "模型类型不能为空")
    public String getModelType()
    {
        return modelType;
    }

    public void setModelType(String modelType)
    {
        this.modelType = modelType;
    }

    public Integer getContextWindow()
    {
        return contextWindow;
    }

    public void setContextWindow(Integer contextWindow)
    {
        this.contextWindow = contextWindow;
    }

    public Integer getMaxOutputTokens()
    {
        return maxOutputTokens;
    }

    public void setMaxOutputTokens(Integer maxOutputTokens)
    {
        this.maxOutputTokens = maxOutputTokens;
    }

    public String getReasoningEnabled()
    {
        return reasoningEnabled;
    }

    public void setReasoningEnabled(String reasoningEnabled)
    {
        this.reasoningEnabled = reasoningEnabled;
    }

    public String getVisionEnabled()
    {
        return visionEnabled;
    }

    public void setVisionEnabled(String visionEnabled)
    {
        this.visionEnabled = visionEnabled;
    }

    public String getInputModalities()
    {
        return inputModalities;
    }

    public void setInputModalities(String inputModalities)
    {
        this.inputModalities = inputModalities;
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
            .append("modelId", getModelId())
            .append("modelCode", getModelCode())
            .append("displayName", getDisplayName())
            .append("modelType", getModelType())
            .append("contextWindow", getContextWindow())
            .append("maxOutputTokens", getMaxOutputTokens())
            .append("reasoningEnabled", getReasoningEnabled())
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
