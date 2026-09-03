package com.ruoyi.web.controller.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** desktop 工具人工确认的最小请求体。 */
public record ToolConfirmRequest(
        @NotBlank(message = "确认ID不能为空")
        @Size(max = 100, message = "确认ID不能超过100个字符") String confirmId,
        @NotNull(message = "确认结果不能为空") Boolean approved)
{
}
