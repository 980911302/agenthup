package com.ruoyi.system.ai.run;

import java.io.Serializable;

/** 运行命令中的附件元数据；文件本体仍位于会话工作区。 */
public class ChatRunAttachment implements Serializable
{
    private static final long serialVersionUID = 1L;

    private String name;
    private String path;
    private String mime;
    private Long size;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getMime() { return mime; }
    public void setMime(String mime) { this.mime = mime; }
    public Long getSize() { return size; }
    public void setSize(Long size) { this.size = size; }

    public boolean isImage()
    {
        return mime != null && mime.startsWith("image/");
    }
}

