package com.ruoyi.system.ai.run;

import java.io.Serializable;

/**
 * 一轮对话的附件元数据（文件本体在会话工作区）。
 * <p>独立于 A 轨 {@code ChatAttachment} 与持久化轨 {@code ChatRunAttachment}，
 * 供 {@link ChatTurnRunner} 使用，避免 ruoyi-system 依赖 ruoyi-admin。
 */
public class ChatTurnAttachment implements Serializable
{
    private static final long serialVersionUID = 1L;

    private String name;
    private String path;
    private String mime;
    private Long size;

    public ChatTurnAttachment()
    {
    }

    public ChatTurnAttachment(String name, String path, String mime, Long size)
    {
        this.name = name;
        this.path = path;
        this.mime = mime;
        this.size = size;
    }

    public boolean isImage()
    {
        return mime != null && mime.startsWith("image/");
    }

    public String getName()
    {
        return name;
    }

    public void setName(String name)
    {
        this.name = name;
    }

    public String getPath()
    {
        return path;
    }

    public void setPath(String path)
    {
        this.path = path;
    }

    public String getMime()
    {
        return mime;
    }

    public void setMime(String mime)
    {
        this.mime = mime;
    }

    public Long getSize()
    {
        return size;
    }

    public void setSize(Long size)
    {
        this.size = size;
    }
}
