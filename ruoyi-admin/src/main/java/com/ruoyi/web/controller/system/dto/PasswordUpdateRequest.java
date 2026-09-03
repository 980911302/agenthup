package com.ruoyi.web.controller.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 修改本人密码的固定请求体。 */
public record PasswordUpdateRequest(
        @NotBlank(message = "旧密码不能为空") String oldPassword,
        @NotBlank(message = "新密码不能为空")
        @Size(min = 5, max = 100, message = "新密码长度必须在5到100个字符之间") String newPassword)
{
}
