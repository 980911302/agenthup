package com.ruoyi.system.tool;

/**
 * 工具产出的附件元数据。
 *
 * <p>由 {@link AttachmentAware} 工具在 call() 后声明,经 {@link RecordingToolCallback}
 * 写入事件流与消息表,前端据此渲染(如生图工具的图片)。
 *
 * @param type 附件类型,如 "image" / "video"
 * @param path 相对会话沙箱根的路径(如 "outputs/xxx.png"),前端用工作区下载接口取文件
 * @param name 文件名(展示用)
 * @param size 文件字节数(可空)
 * @param mime MIME 类型(如 "image/png",可空)
 *
 * @author ruoyi
 */
public record ToolAttachment(
        String type,
        String path,
        String name,
        Long size,
        String mime)
{
}
