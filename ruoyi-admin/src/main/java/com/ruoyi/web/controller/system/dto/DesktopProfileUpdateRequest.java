package com.ruoyi.web.controller.system.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** desktop 个人资料允许编辑的字段白名单。 */
public record DesktopProfileUpdateRequest(
        @NotBlank(message = "用户昵称不能为空")
        @Size(max = 30, message = "用户昵称不能超过30个字符") String nickName,
        @Size(max = 11, message = "手机号码不能超过11个字符") String phonenumber,
        @Email(message = "邮箱格式不正确")
        @Size(max = 50, message = "邮箱长度不能超过50个字符") String email)
{
}
