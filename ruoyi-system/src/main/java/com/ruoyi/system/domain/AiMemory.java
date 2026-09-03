package com.ruoyi.system.domain;

import java.util.Date;

/**
 * 跨会话长期记忆台账 ai_memory
 *
 * <p>唯一事实源:向量表(mem_vector_*)与未来的图谱都从本表派生。
 * 分层:user_id 永远强制;agent_id=0 表示用户层(跨 agent 共享),&gt;0 为该 agent 专属层。
 * 只增不删:覆盖走 status=superseded,物理删除只留给合规清理(del_flag=2)。
 *
 * @author ruoyi
 */
public class AiMemory
{
    private static final long serialVersionUID = 1L;

    /** 记忆主键 */
    private Long memoryId;

    /** 隔离维度(永远强制) */
    private Long userId;

    /** 0=用户层;>0=该agent专属层 */
    private Long agentId;

    /** 记忆类型:fact|preference|event|goal|rule */
    private String type;

    /** 记忆正文 */
    private String content;

    /** active|superseded */
    private String status;

    /** 被哪条覆盖(仅同层内) */
    private Long supersededBy;

    /** 提炼来源(当前恒为auto,预留) */
    private String source;

    /** 来源会话(可溯源) */
    private String sourceSessionId;

    /** 提炼覆盖到的消息位点 */
    private Long sourceMessageId;

    /** 正文归一化后哈希,精确去重 */
    private String contentHash;

    /** 落在哪张向量表(删除/重建用),空=待补向量 */
    private Integer embeddingDim;

    /** 用了哪个 embedding 模型 */
    private String embeddingModel;

    /** 被检索命中次数 */
    private Integer hitCount;

    /** 最近命中时间 */
    private Date lastHitTime;

    /** 时间线语义基准 */
    private Date createTime;

    private Date updateTime;

    /** 0存在 2删除(合规清理用) */
    private String delFlag;

    public Long getMemoryId() { return memoryId; }
    public void setMemoryId(Long memoryId) { this.memoryId = memoryId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getAgentId() { return agentId; }
    public void setAgentId(Long agentId) { this.agentId = agentId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getSupersededBy() { return supersededBy; }
    public void setSupersededBy(Long supersededBy) { this.supersededBy = supersededBy; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getSourceSessionId() { return sourceSessionId; }
    public void setSourceSessionId(String sourceSessionId) { this.sourceSessionId = sourceSessionId; }
    public Long getSourceMessageId() { return sourceMessageId; }
    public void setSourceMessageId(Long sourceMessageId) { this.sourceMessageId = sourceMessageId; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public Integer getEmbeddingDim() { return embeddingDim; }
    public void setEmbeddingDim(Integer embeddingDim) { this.embeddingDim = embeddingDim; }
    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }
    public Integer getHitCount() { return hitCount; }
    public void setHitCount(Integer hitCount) { this.hitCount = hitCount; }
    public Date getLastHitTime() { return lastHitTime; }
    public void setLastHitTime(Date lastHitTime) { this.lastHitTime = lastHitTime; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
    public String getDelFlag() { return delFlag; }
    public void setDelFlag(String delFlag) { this.delFlag = delFlag; }
}
