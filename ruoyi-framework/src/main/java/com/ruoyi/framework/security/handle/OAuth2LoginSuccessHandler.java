package com.ruoyi.framework.security.handle;

import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;
import com.ruoyi.common.constant.Constants;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.MessageUtils;
import com.ruoyi.framework.config.properties.OAuthLoginProperties;
import com.ruoyi.framework.manager.AsyncManager;
import com.ruoyi.framework.manager.factory.AsyncFactory;
import com.ruoyi.framework.security.filter.OAuth2LoginTargetFilter;
import com.ruoyi.framework.web.service.OAuth2UserLoginService;
import com.ruoyi.framework.web.service.OAuthLoginTicketService;
import com.ruoyi.framework.web.service.SysLoginService;
import com.ruoyi.framework.web.service.TokenService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * OIDC 回调成功处理：绑定本地用户，签发既有 JWT，再通过一次性票据交给 SPA。
 */
@Component
@ConditionalOnProperty(prefix = "oauth.login", name = "enabled", havingValue = "true")
public class OAuth2LoginSuccessHandler implements AuthenticationSuccessHandler
{
    private static final Logger log = LoggerFactory.getLogger(OAuth2LoginSuccessHandler.class);

    private final OAuth2UserLoginService oauth2UserLoginService;
    private final TokenService tokenService;
    private final OAuthLoginTicketService ticketService;
    private final SysLoginService loginService;
    private final OAuthLoginProperties properties;

    public OAuth2LoginSuccessHandler(OAuth2UserLoginService oauth2UserLoginService, TokenService tokenService,
            OAuthLoginTicketService ticketService, SysLoginService loginService, OAuthLoginProperties properties)
    {
        this.oauth2UserLoginService = oauth2UserLoginService;
        this.tokenService = tokenService;
        this.ticketService = ticketService;
        this.loginService = loginService;
        this.properties = properties;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException, ServletException
    {
        String frontendUrl = frontendUrl(request);
        if (!(authentication instanceof OAuth2AuthenticationToken))
        {
            redirectWithError(request, response, frontendUrl, "invalid_principal");
            return;
        }
        OAuth2AuthenticationToken oauthToken = (OAuth2AuthenticationToken) authentication;
        try
        {
            LoginUser loginUser = oauth2UserLoginService.login(oauthToken.getAuthorizedClientRegistrationId(),
                oauthToken.getPrincipal().getAttributes());
            loginService.recordLoginInfo(loginUser.getUserId());
            AsyncManager.me().execute(AsyncFactory.recordLogininfor(loginUser.getUsername(), Constants.LOGIN_SUCCESS,
                MessageUtils.message("user.login.success")));
            String ticket = ticketService.create(tokenService.createToken(loginUser));
            response.sendRedirect(loginCallbackUrl(request, frontendUrl, "oauthTicket", ticket));
        }
        catch (ServiceException e)
        {
            log.info("OAuth 登录被拒绝: {}", e.getMessage());
            redirectWithError(request, response, frontendUrl, "account_not_available");
        }
        catch (Exception e)
        {
            log.error("OAuth 登录处理失败", e);
            redirectWithError(request, response, frontendUrl, "login_failed");
        }
        finally
        {
            clearLoginTarget(request);
        }
    }

    public String frontendUrl(HttpServletRequest request)
    {
        Object target = request.getSession(false) == null ? null
                : request.getSession(false).getAttribute(OAuth2LoginTargetFilter.SESSION_ATTRIBUTE);
        if (OAuth2LoginTargetFilter.EXTENSION_TARGET.equals(target))
        {
            return properties.getBackendUrl();
        }
        return OAuth2LoginTargetFilter.DESKTOP_TARGET.equals(target) ? properties.getDesktopFrontendUrl()
                : properties.getFrontendUrl();
    }

    public void clearLoginTarget(HttpServletRequest request)
    {
        if (request.getSession(false) != null)
        {
            request.getSession(false).removeAttribute(OAuth2LoginTargetFilter.SESSION_ATTRIBUTE);
        }
    }

    private void redirectWithError(HttpServletRequest request, HttpServletResponse response, String frontendUrl,
            String error) throws IOException
    {
        response.sendRedirect(loginCallbackUrl(request, frontendUrl, "oauthError", error));
    }

    private String loginCallbackUrl(HttpServletRequest request, String frontendUrl, String parameter, String value)
    {
        if (request != null && isExtensionTarget(request))
        {
            // 扩展通过 chrome.tabs.onUpdated 读这个后端页上的 query，不依赖 chrome-extension:// id。
            return frontendUrl + "/oauth2/login/extension/callback?"
                    + parameter + "=" + UriUtils.encodeQueryParam(value, "UTF-8");
        }
        if (request != null && isDesktopTarget(request))
        {
            // Desktop 使用 Hash 路由，票据必须位于 #/login 后才能被 Vue Router 读取。
            return frontendUrl + "/#/login?" + parameter + "=" + UriUtils.encodeQueryParam(value, "UTF-8");
        }
        return UriComponentsBuilder.fromUriString(frontendUrl)
            .path("/login")
            .queryParam(parameter, value)
            .build()
            .toUriString();
    }

    private boolean isDesktopTarget(HttpServletRequest request)
    {
        return request.getSession(false) != null
                && OAuth2LoginTargetFilter.DESKTOP_TARGET.equals(
                    request.getSession(false).getAttribute(OAuth2LoginTargetFilter.SESSION_ATTRIBUTE));
    }

    private boolean isExtensionTarget(HttpServletRequest request)
    {
        return request.getSession(false) != null
                && OAuth2LoginTargetFilter.EXTENSION_TARGET.equals(
                    request.getSession(false).getAttribute(OAuth2LoginTargetFilter.SESSION_ATTRIBUTE));
    }
}
