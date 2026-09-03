package com.ruoyi.system.tool;

import java.util.List;

/**
 * 工具回调可选实现的附件声明接口。
 *
 * <p>当工具本次 {@code call()} 产出了富媒体附件(如生图工具产出的图片),
 * 实现本接口让外层 {@link RecordingToolCallback} 把附件元数据一并写进
 * {@code tool_end} 事件和 {@code ai_chat_message.attachments} 列,
 * 供前端内联渲染。
 *
 * <p>不强制所有工具实现 -- 普通文本工具不实现,外层按 instanceof 判定,无附件则走原流程。
 *
 * @author ruoyi
 */
public interface AttachmentAware
{
    /**
     * 最近一次 call() 产出的附件列表。
     *
     * @return 附件列表;无附件返回 null 或空列表
     */
    List<ToolAttachment> lastAttachments();
}
