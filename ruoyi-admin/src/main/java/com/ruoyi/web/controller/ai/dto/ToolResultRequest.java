package com.ruoyi.web.controller.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 渠道工具执行结果回传。 */
public record ToolResultRequest(
        @NotBlank @Size(max = 100) String callId,
        @NotNull Boolean ok,
        String result,
        String error,
        /** 客户端产出的图片在个人文件里的 id;只传引用,图片本体不过通道。 */
        Long mediaFileId,
        /** 客户端产出的图片在当前工作区里的相对路径；新版本优先使用。 */
        @Size(max = 500) String workspacePath)
{
}
