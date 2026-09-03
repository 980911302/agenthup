package com.ruoyi.web.controller.ai.dto;

/**
 * 随消息带上的会话附件。
 *
 * <p>文件本体在会话工作区 {@code {workspaceRoot}/{sessionId}/uploads/} 下,
 * 这里只传元数据 —— 内容不进请求体,也不预先塞进上下文。
 *
 * <p>模型怎么用:
 * <ul>
 *   <li>图片 + 模型支持视觉 -> 走 Spring AI 的 Media 通道直接给模型看</li>
 *   <li>其余(含模型不支持视觉时的图片)-> 只把路径写进消息清单,
 *       模型按需调 {@code readFile} 工具自己读。大文件因此不会撑爆上下文。</li>
 * </ul>
 *
 * @author ruoyi
 */
public class ChatAttachment
{
    /** 文件名(已 sanitize) */
    private String name;

    /** 相对沙箱根的路径,如 uploads/report.csv;可直接作为 readFile 的入参 */
    private String path;

    /** MIME 类型,如 image/png、text/csv */
    private String mime;

    /** 字节数 */
    private Long size;

    /** 是否为图片(由 mime 判断,给模板与多模态分支用) */
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
