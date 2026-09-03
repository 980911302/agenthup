package com.ruoyi.system.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * 知识库表 kb_knowledge
 *
 * @author ruoyi
 */
public class KbKnowledge extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 知识库ID */
    private Long kbId;

    /** 知识库名称 */
    private String kbName;

    /** 知识库描述 */
    private String description;

    /** 嵌入模型 code(换模型需重建全部向量) */
    private String embeddingModelCode;

    /** 是否启用图谱(0否 1是),默认关闭 */
    private String graphEnabled;

    /** 图谱抽取模型 code(建议便宜模型,与对话模型分离) */
    private String extractModelCode;

    /** 分块策略(F/P) */
    private String chunkStrategy;

    /** 分块目标 token 数 */
    private Integer chunkSize;

    /** 重叠 token 数 */
    private Integer chunkOverlap;

    /** 知识库状态(0正常 1停用) */
    private String status;

    /** 创建者用户ID */
    private Long createUserId;

    /** 负责人用户ID（可转移，默认等于创建者） */
    private Long ownerUserId;

    /**
     * 可见范围：PRIVATE / MEMBERS / DEPT / ORG
     */
    private String visibility;

    /** 当前生效策略版本（KB-PV2-10） */
    private Long activePolicyVersionId;
    /** 目标策略版本（升级中） */
    private Long desiredPolicyVersionId;
    /** 上一成功版本（回滚点） */
    private Long previousPolicyVersionId;
    /** READY / UPGRADING / FAILED / STALE */
    private String indexState;

    /** 归属部门ID(创建时冗余写入,部门过滤用) */
    private Long deptId;

    /** 删除标志(0存在 2删除) */
    private String delFlag;

    /** 列表访问范围：是否超管（查询条件，非表字段） */
    private String accessAdmin;
    /** 列表访问范围：当前用户（查询条件，非表字段） */
    private Long accessUserId;
    /** 列表访问范围：当前部门（查询条件，非表字段） */
    private Long accessDeptId;

    public Long getKbId()
    {
        return kbId;
    }

    public void setKbId(Long kbId)
    {
        this.kbId = kbId;
    }

    @NotBlank(message = "知识库名称不能为空")
    @Size(min = 0, max = 100, message = "知识库名称不能超过100个字符")
    public String getKbName()
    {
        return kbName;
    }

    public void setKbName(String kbName)
    {
        this.kbName = kbName;
    }

    public String getDescription()
    {
        return description;
    }

    public void setDescription(String description)
    {
        this.description = description;
    }

    public String getEmbeddingModelCode()
    {
        return embeddingModelCode;
    }

    public void setEmbeddingModelCode(String embeddingModelCode)
    {
        this.embeddingModelCode = embeddingModelCode;
    }

    public String getGraphEnabled()
    {
        return graphEnabled;
    }

    public void setGraphEnabled(String graphEnabled)
    {
        this.graphEnabled = graphEnabled;
    }

    public String getExtractModelCode()
    {
        return extractModelCode;
    }

    public void setExtractModelCode(String extractModelCode)
    {
        this.extractModelCode = extractModelCode;
    }

    public String getChunkStrategy()
    {
        return chunkStrategy;
    }

    public void setChunkStrategy(String chunkStrategy)
    {
        this.chunkStrategy = chunkStrategy;
    }

    public Integer getChunkSize()
    {
        return chunkSize;
    }

    public void setChunkSize(Integer chunkSize)
    {
        this.chunkSize = chunkSize;
    }

    public Integer getChunkOverlap()
    {
        return chunkOverlap;
    }

    public void setChunkOverlap(Integer chunkOverlap)
    {
        this.chunkOverlap = chunkOverlap;
    }

    public String getStatus()
    {
        return status;
    }

    public void setStatus(String status)
    {
        this.status = status;
    }

    public Long getCreateUserId()
    {
        return createUserId;
    }

    public void setCreateUserId(Long createUserId)
    {
        this.createUserId = createUserId;
    }

    public Long getOwnerUserId()
    {
        return ownerUserId;
    }

    public void setOwnerUserId(Long ownerUserId)
    {
        this.ownerUserId = ownerUserId;
    }

    public String getVisibility()
    {
        return visibility;
    }

    public void setVisibility(String visibility)
    {
        this.visibility = visibility;
    }

    public Long getActivePolicyVersionId()
    {
        return activePolicyVersionId;
    }

    public void setActivePolicyVersionId(Long activePolicyVersionId)
    {
        this.activePolicyVersionId = activePolicyVersionId;
    }

    public Long getDesiredPolicyVersionId()
    {
        return desiredPolicyVersionId;
    }

    public void setDesiredPolicyVersionId(Long desiredPolicyVersionId)
    {
        this.desiredPolicyVersionId = desiredPolicyVersionId;
    }

    public Long getPreviousPolicyVersionId()
    {
        return previousPolicyVersionId;
    }

    public void setPreviousPolicyVersionId(Long previousPolicyVersionId)
    {
        this.previousPolicyVersionId = previousPolicyVersionId;
    }

    public String getIndexState()
    {
        return indexState;
    }

    public void setIndexState(String indexState)
    {
        this.indexState = indexState;
    }

    public Long getDeptId()
    {
        return deptId;
    }

    public void setDeptId(Long deptId)
    {
        this.deptId = deptId;
    }

    public String getDelFlag()
    {
        return delFlag;
    }

    public void setDelFlag(String delFlag)
    {
        this.delFlag = delFlag;
    }

    public String getAccessAdmin()
    {
        return accessAdmin;
    }

    public void setAccessAdmin(String accessAdmin)
    {
        this.accessAdmin = accessAdmin;
    }

    public Long getAccessUserId()
    {
        return accessUserId;
    }

    public void setAccessUserId(Long accessUserId)
    {
        this.accessUserId = accessUserId;
    }

    public Long getAccessDeptId()
    {
        return accessDeptId;
    }

    public void setAccessDeptId(Long accessDeptId)
    {
        this.accessDeptId = accessDeptId;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("kbId", getKbId())
            .append("kbName", getKbName())
            .append("description", getDescription())
            .append("embeddingModelCode", getEmbeddingModelCode())
            .append("graphEnabled", getGraphEnabled())
            .append("extractModelCode", getExtractModelCode())
            .append("chunkStrategy", getChunkStrategy())
            .append("chunkSize", getChunkSize())
            .append("chunkOverlap", getChunkOverlap())
            .append("status", getStatus())
            .append("createUserId", getCreateUserId())
            .append("deptId", getDeptId())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .append("remark", getRemark())
            .toString();
    }
}