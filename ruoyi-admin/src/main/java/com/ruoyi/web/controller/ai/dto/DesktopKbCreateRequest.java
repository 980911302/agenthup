package com.ruoyi.web.controller.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** desktop 创建知识库的最小请求体，禁止客户端提交归属、权限及引擎配置。 */
public record DesktopKbCreateRequest(
        @NotBlank(message = "知识库名称不能为空")
        @Size(max = 100, message = "知识库名称不能超过100个字符") String kbName,
        @Size(max = 500, message = "知识库描述不能超过500个字符") String description)
{
}
