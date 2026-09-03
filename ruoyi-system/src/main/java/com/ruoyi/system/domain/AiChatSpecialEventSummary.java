package com.ruoyi.system.domain;

/**
 * 特殊事件摘要:只回答这一回合有几个文件/片段,不把正文给前端。
 */
public class AiChatSpecialEventSummary
{
    private Long messageId;
    private String name;
    private int fileCount;
    private int chunkCount;
    /** 仅查询填充,不进接口 */
    private String payload;

    public Long getMessageId() { return messageId; }
    public void setMessageId(Long messageId) { this.messageId = messageId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getFileCount() { return fileCount; }
    public void setFileCount(int fileCount) { this.fileCount = fileCount; }
    public int getChunkCount() { return chunkCount; }
    public void setChunkCount(int chunkCount) { this.chunkCount = chunkCount; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
}
