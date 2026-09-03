package com.ruoyi.web.controller.common;

import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.constant.Constants;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.framework.config.properties.OAuthLoginProperties;
import com.ruoyi.framework.web.service.OAuthLoginTicketService;

/**
 * SPA 使用的 OAuth 登录入口与一次性票据兑换接口。
 */
@RestController
public class OAuth2LoginController
{
    @Autowired
    private OAuthLoginProperties properties;

    @Autowired
    private OAuthLoginTicketService ticketService;

    @GetMapping("/oauth2/login/config")
    public AjaxResult config()
    {
        AjaxResult result = AjaxResult.success();
        result.put("enabled", properties.isEnabled());
        if (properties.isEnabled())
        {
            result.put("authorizationUrl", properties.getBackendUrl() + "/oauth2/authorization/keycloak");
        }
        return result;
    }

    /** Desktop 使用独立入口，授权完成后回到 /desktop/login。 */
    @GetMapping("/oauth2/login/desktop/config")
    public AjaxResult desktopConfig()
    {
        AjaxResult result = AjaxResult.success();
        result.put("enabled", properties.isEnabled());
        if (properties.isEnabled())
        {
            result.put("authorizationUrl", properties.getBackendUrl()
                    + "/oauth2/authorization/keycloak?target=desktop");
        }
        return result;
    }

    /** 浏览器插件入口，授权完成后回到扩展可监听的后端回调页。 */
    @GetMapping("/oauth2/login/extension/config")
    public AjaxResult extensionConfig()
    {
        AjaxResult result = AjaxResult.success();
        result.put("enabled", properties.isEnabled());
        if (properties.isEnabled())
        {
            result.put("authorizationUrl", properties.getBackendUrl()
                    + "/oauth2/authorization/keycloak?target=extension");
        }
        return result;
    }

    /**
     * OAuth 成功后的落点。扩展 background 监听此 URL 取 ticket，再关掉标签页。
     */
    @GetMapping(value = "/oauth2/login/extension/callback", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> extensionCallback()
    {
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>AgentHub</title></head>"
                        + "<body style=\"font-family:sans-serif;padding:40px;color:#334\">"
                        + "<p>登录完成，可以关闭此页。</p></body></html>");
    }

    @PostMapping("/oauth2/login/exchange")
    public AjaxResult exchange(@RequestBody Map<String, String> body)
    {
        String ticket = body == null ? null : body.get("ticket");
        if (StringUtils.isEmpty(ticket))
        {
            throw new ServiceException("登录票据不能为空");
        }
        AjaxResult result = AjaxResult.success();
        result.put(Constants.TOKEN, ticketService.exchange(ticket));
        return result;
    }
}
