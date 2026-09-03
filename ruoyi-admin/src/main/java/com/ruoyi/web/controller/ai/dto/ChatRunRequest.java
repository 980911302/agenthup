package com.ruoyi.web.controller.ai.dto;

import java.util.List;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 创建持久化对话运行的请求。 */
public class ChatRunRequest
{
    @NotBlank(message = "会话ID不能为空")
    @Size(max = 64, message = "会话ID不能超过64个字符")
    private String sessionId;

    @NotNull(message = "智能体ID不能为空")
    private Long agentId;
    /** 客户端选择的聊天模型；为空时沿用默认智能体模型。 */
    private Long modelId;
    /** 输入框通过 @ 选择的临时技能；服务端会和默认智能体技能合并后快照。 */
    private List<Long> skillIds;
    private String message;
    private List<ChatAttachment> attachments;
    @Size(max = 100, message = "请求ID不能超过100个字符")
    private String clientRequestId;
    /** 会话选中的知识库ID(会话级多选；null 表示未传不修改，空列表表示清空) */
    private List<Long> kbIds;
    /** 所属项目ID(新建会话时可选；null=普通会话不归属) */
    private Long projectId;

    // 首轮补声明:新会话的 declare 要等落库后才发得出去,而首轮 run 就在落库的同一次
    // 调用里装配。客户端把清单捎在这里,服务端在装配前写进会话行。
    private String clientType;
    private String capabilitiesVersion;
    private Object clientTools;

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public Long getAgentId() { return agentId; }
    public void setAgentId(Long agentId) { this.agentId = agentId; }
    public Long getModelId() { return modelId; }
    public void setModelId(Long modelId) { this.modelId = modelId; }
    public List<Long> getSkillIds() { return skillIds; }
    public void setSkillIds(List<Long> skillIds) { this.skillIds = skillIds; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public List<ChatAttachment> getAttachments() { return attachments; }
    public void setAttachments(List<ChatAttachment> attachments) { this.attachments = attachments; }
    public String getClientRequestId() { return clientRequestId; }
    public void setClientRequestId(String clientRequestId) { this.clientRequestId = clientRequestId; }
    public List<Long> getKbIds() { return kbIds; }
    public void setKbIds(List<Long> kbIds) { this.kbIds = kbIds; }
    public Long getProjectId() { return projectId; }

    public String getClientType() { return clientType; }
    public void setClientType(String clientType) { this.clientType = clientType; }

    public String getCapabilitiesVersion() { return capabilitiesVersion; }
    public void setCapabilitiesVersion(String v) { this.capabilitiesVersion = v; }

    public Object getClientTools() { return clientTools; }
    public void setClientTools(Object clientTools) { this.clientTools = clientTools; }

    /** 清单原样转回 JSON 文本；解析与校验都在 ChannelToolSchemas 里做。 */
    public String getClientToolsJson()
    {
        return clientTools == null ? null : com.alibaba.fastjson2.JSON.toJSONString(clientTools);
    }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
}
