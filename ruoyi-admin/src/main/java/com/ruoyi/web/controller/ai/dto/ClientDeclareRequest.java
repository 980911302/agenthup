package com.ruoyi.web.controller.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/** 客户端能力声明。 */
public record ClientDeclareRequest(
        @Size(max = 32) String clientType,
        @NotBlank @Size(max = 64) String capabilitiesVersion,
        List<Map<String, Object>> tools)
{
}
