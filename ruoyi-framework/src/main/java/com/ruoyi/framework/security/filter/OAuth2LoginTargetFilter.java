package com.ruoyi.framework.security.filter;

import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * 记录 OAuth 授权从哪个前端发起。授权码回调仍共用同一个 Keycloak Client，
 * 但成功后需要回到对应的 SPA。
 */
public class OAuth2LoginTargetFilter extends OncePerRequestFilter
{
    public static final String DESKTOP_TARGET = "desktop";

    public static final String EXTENSION_TARGET = "extension";

    public static final String SESSION_ATTRIBUTE = OAuth2LoginTargetFilter.class.getName() + ".target";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException
    {
        if (request.getRequestURI().endsWith("/oauth2/authorization/keycloak"))
        {
            HttpSession session = request.getSession(false);
            String target = request.getParameter("target");
            if (DESKTOP_TARGET.equals(target) || EXTENSION_TARGET.equals(target))
            {
                request.getSession(true).setAttribute(SESSION_ATTRIBUTE, target);
            }
            else if (session != null)
            {
                session.removeAttribute(SESSION_ATTRIBUTE);
            }
        }
        filterChain.doFilter(request, response);
    }
}
