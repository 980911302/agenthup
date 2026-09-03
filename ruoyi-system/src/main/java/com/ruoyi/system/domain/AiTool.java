package com.ruoyi.system.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * 工具表 ai_tool
 * 统一管理内置工具(Java @Tool)和 MCP 工具
 *
 * @author ruoyi
 */
public class AiTool extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 工具ID */
    private Long toolId;

    /** 工具编码(系统引用,唯一) */
    private String toolCode;

    /** 工具名称 */
    private String toolName;

    /** 工具描述(给LLM看的功能说明) */
    private String description;

    /** 工具类型(1内置 2MCP) */
    @NotBlank(message = "工具类型不能为空")
    private String toolType;

    /** 工具分类(如:搜索/计算/数据库) */
    private String category;

    // ---- 内置工具字段(tool_type=1) ----
    /** Spring Bean名称(tool_type=1时填写) */
    private String beanName;

    /** 方法名(tool_type=1时填写) */
    private String methodName;

    // ---- MCP 工具字段(tool_type=2) ----
    /** 所属MCP服务ID */
    private Long mcpServerId;

    /** MCP远端工具名(tool_type=2时填写) */
    private String remoteToolName;

    // ---- 通用 ----
    /** JSON Schema,工具入参定义 */
    private String inputSchema;

    /** 返回值说明(给LLM看的) */
    private String returnDesc;

    /** 显示顺序 */
    private Integer sort;

    /** 单次运行该工具最多调用次数(空=不单独限制) */
    private Integer maxCallsPerRun;

    /** 危险操作需人工确认(0否 1是) */
    private String requireConfirm;

    /** 状态(0正常 1停用) */
    private String status;

    /** 删除标志(0存在 2删除) */
    private String delFlag;

    // ---- 关联字段(列表展示) ----
    /** MCP服务名称(关联查询) */
    private String mcpServerName;

    public Long getToolId() { return toolId; }
    public void setToolId(Long toolId) { this.toolId = toolId; }

    @NotBlank(message = "工具编码不能为空")
    @Size(min = 0, max = 100, message = "工具编码不能超过100个字符")
    public String getToolCode() { return toolCode; }
    public void setToolCode(String toolCode) { this.toolCode = toolCode; }

    @NotBlank(message = "工具名称不能为空")
    @Size(min = 0, max = 100, message = "工具名称不能超过100个字符")
    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getToolType() { return toolType; }
    public void setToolType(String toolType) { this.toolType = toolType; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getBeanName() { return beanName; }
    public void setBeanName(String beanName) { this.beanName = beanName; }

    public String getMethodName() { return methodName; }
    public void setMethodName(String methodName) { this.methodName = methodName; }

    public Long getMcpServerId() { return mcpServerId; }
    public void setMcpServerId(Long mcpServerId) { this.mcpServerId = mcpServerId; }

    public String getRemoteToolName() { return remoteToolName; }
    public void setRemoteToolName(String remoteToolName) { this.remoteToolName = remoteToolName; }

    public String getInputSchema() { return inputSchema; }
    public void setInputSchema(String inputSchema) { this.inputSchema = inputSchema; }

    public String getReturnDesc() { return returnDesc; }
    public void setReturnDesc(String returnDesc) { this.returnDesc = returnDesc; }

    public Integer getSort() { return sort; }
    public void setSort(Integer sort) { this.sort = sort; }

    public Integer getMaxCallsPerRun() { return maxCallsPerRun; }
    public void setMaxCallsPerRun(Integer maxCallsPerRun) { this.maxCallsPerRun = maxCallsPerRun; }

    public String getRequireConfirm() { return requireConfirm; }
    public void setRequireConfirm(String requireConfirm) { this.requireConfirm = requireConfirm; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getDelFlag() { return delFlag; }
    public void setDelFlag(String delFlag) { this.delFlag = delFlag; }

    public String getMcpServerName() { return mcpServerName; }
    public void setMcpServerName(String mcpServerName) { this.mcpServerName = mcpServerName; }

    @Override
    public String toString() {
        return new ToStringBuilder(this, ToStringStyle.MULTI_LINE_STYLE)
            .append("toolId", getToolId())
            .append("toolCode", getToolCode())
            .append("toolName", getToolName())
            .append("description", getDescription())
            .append("toolType", getToolType())
            .append("category", getCategory())
            .append("beanName", getBeanName())
            .append("methodName", getMethodName())
            .append("mcpServerId", getMcpServerId())
            .append("remoteToolName", getRemoteToolName())
            .append("inputSchema", getInputSchema())
            .append("returnDesc", getReturnDesc())
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
