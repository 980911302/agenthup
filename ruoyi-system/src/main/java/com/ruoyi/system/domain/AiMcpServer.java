package com.ruoyi.system.domain;

import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * MCP服务器表 ai_mcp_server
 *
 * 字段设计说明:
 *  - transport:  STDIO / SSE / HTTP
 *  - STDIO 模式: 必填 command + args,env 可选(密钥/token 放 env JSON)
 *  - SSE/HTTP:   必填 endpoint(URL),env 可选
 *  - env 字段加密存储,展示时整体解密
 *
 * @author ruoyi
 */
public class AiMcpServer extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** MCP服务ID */
    private Long mcpServerId;

    /** MCP服务名称 */
    private String serverName;

    /** MCP服务编码(系统引用,唯一) */
    private String serverCode;

    /** 传输方式(STDIO/SSE/HTTP) */
    private String transport;

    /** 启动命令(STDIO模式,如 node/uv/python) */
    private String command;

    /** 命令参数 JSON 数组,如 ["mcp-server-fs","--root","/data"] */
    private String args;

    /** 连接端点 URL(SSE/HTTP 模式填写) */
    private String endpoint;

    /** 环境变量 JSON 对象(加密存储),密钥/token 放这里 */
    private String env;

    /** 健康状态(0未知 1正常 2异常) */
    private String healthStatus;

    /** 最近健康检查时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date healthCheckTime;

    /** 状态(0正常 1停用) */
    private String status;

    /** 删除标志(0存在 2删除) */
    private String delFlag;

    public Long getMcpServerId() { return mcpServerId; }
    public void setMcpServerId(Long mcpServerId) { this.mcpServerId = mcpServerId; }

    @NotBlank(message = "MCP服务名称不能为空")
    @Size(min = 0, max = 100, message = "MCP服务名称不能超过100个字符")
    public String getServerName() { return serverName; }
    public void setServerName(String serverName) { this.serverName = serverName; }

    @NotBlank(message = "MCP服务编码不能为空")
    @Size(min = 0, max = 50, message = "MCP服务编码不能超过50个字符")
    public String getServerCode() { return serverCode; }
    public void setServerCode(String serverCode) { this.serverCode = serverCode; }

    @NotBlank(message = "传输方式不能为空")
    public String getTransport() { return transport; }
    public void setTransport(String transport) { this.transport = transport; }

    public String getCommand() { return command; }
    public void setCommand(String command) { this.command = command; }

    public String getArgs() { return args; }
    public void setArgs(String args) { this.args = args; }

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    public String getEnv() { return env; }
    public void setEnv(String env) { this.env = env; }

    public String getHealthStatus() { return healthStatus; }
    public void setHealthStatus(String healthStatus) { this.healthStatus = healthStatus; }

    public Date getHealthCheckTime() { return healthCheckTime; }
    public void setHealthCheckTime(Date healthCheckTime) { this.healthCheckTime = healthCheckTime; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
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
        return new ToStringBuilder(this, ToStringStyle.MULTI_LINE_STYLE)
            .append("mcpServerId", getMcpServerId())
            .append("serverName", getServerName())
            .append("serverCode", getServerCode())
            .append("transport", getTransport())
            .append("command", getCommand())
            .append("args", getArgs())
            .append("endpoint", getEndpoint())
            .append("env", getEnv())
            .append("healthStatus", getHealthStatus())
            .append("healthCheckTime", getHealthCheckTime())
            .append("status", getStatus())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .append("remark", getRemark())
            .toString();
    }
}
