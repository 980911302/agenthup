package com.ruoyi.framework.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpMethod;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.logout.LogoutFilter;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.web.filter.CorsFilter;
import com.ruoyi.framework.config.properties.PermitAllUrlProperties;
import com.ruoyi.framework.config.properties.OAuthLoginProperties;
import com.ruoyi.framework.security.filter.JwtAuthenticationTokenFilter;
import com.ruoyi.framework.security.filter.OAuth2LoginTargetFilter;
import com.ruoyi.framework.security.handle.AuthenticationEntryPointImpl;
import com.ruoyi.framework.security.handle.LogoutSuccessHandlerImpl;
import com.ruoyi.framework.security.handle.OAuth2LoginSuccessHandler;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * spring security配置
 * 
 * @author ruoyi
 */
@EnableMethodSecurity(prePostEnabled = true, securedEnabled = true)
@Configuration
@EnableConfigurationProperties(OAuthLoginProperties.class)
public class SecurityConfig
{
    /**
     * 认证失败处理类
     */
    @Autowired
    private AuthenticationEntryPointImpl unauthorizedHandler;

    /**
     * 退出处理类
     */
    @Autowired
    private LogoutSuccessHandlerImpl logoutSuccessHandler;

    /**
     * token认证过滤器
     */
    @Autowired
    private JwtAuthenticationTokenFilter authenticationTokenFilter;
    
    /**
     * 跨域过滤器
     */
    @Autowired
    private CorsFilter corsFilter;

    /**
     * 允许匿名访问的地址
     */
    @Autowired
    private PermitAllUrlProperties permitAllUrl;

    @Autowired
    private OAuthLoginProperties oauthLoginProperties;

    @Autowired
    private ObjectProvider<OAuth2LoginSuccessHandler> oauth2LoginSuccessHandler;

	/**
	 * 身份验证实现
	 */
	@Bean
	public AuthenticationManager authenticationManager(AuthenticationConfiguration authenticationConfiguration) throws Exception 
	{
		return authenticationConfiguration.getAuthenticationManager();
	}

    /**
     * anyRequest          |   匹配所有请求路径
     * access              |   SpringEl表达式结果为true时可以访问
     * anonymous           |   匿名可以访问
     * denyAll             |   用户不能访问
     * fullyAuthenticated  |   用户完全认证可以访问（非remember-me下自动登录）
     * hasAnyAuthority     |   如果有参数，参数表示权限，则其中任何一个权限可以访问
     * hasAnyRole          |   如果有参数，参数表示角色，则其中任何一个角色可以访问
     * hasAuthority        |   如果有参数，参数表示权限，则其权限可以访问
     * hasIpAddress        |   如果有参数，参数表示IP地址，如果用户IP和参数匹配，则可以访问
     * hasRole             |   如果有参数，参数表示角色，则其角色可以访问
     * permitAll           |   用户可以任意访问
     * rememberMe          |   允许通过remember-me登录的用户访问
     * authenticated       |   用户登录后可访问
     */
    @Bean
    protected SecurityFilterChain filterChain(HttpSecurity httpSecurity) throws Exception
    {
        httpSecurity
            // CSRF禁用，因为不使用session
            .csrf(csrf -> csrf.disable())
            // 禁用HTTP响应标头
            .headers((headersCustomizer) -> {
                headersCustomizer.cacheControl(cache -> cache.disable()).frameOptions(options -> options.sameOrigin());
            })
            // 认证失败处理类
            .exceptionHandling(exception -> exception.authenticationEntryPoint(unauthorizedHandler))
            // JWT 接口保持无状态；OAuth 授权码流程需要短暂 Session 保存 state/nonce。
            .sessionManagement(session -> session.sessionCreationPolicy(
                oauthLoginProperties.isEnabled() ? SessionCreationPolicy.IF_REQUIRED : SessionCreationPolicy.STATELESS))
            // 注解标记允许匿名访问的url
            .authorizeHttpRequests((requests) -> {
                // Spring Security 6 默认对 ASYNC/FORWARD/INCLUDE/ERROR 等派发也跑鉴权。
                // SSE(SseEmitter)在流结束时有一次 ASYNC dispatch，此时 JwtAuthenticationTokenFilter
                // (OncePerRequestFilter，默认不重跑 ASYNC)不会重建 SecurityContext，STATELESS 下上下文已丢失，
                // 会被 AuthorizationFilter 以 Access Denied 拒绝。ASYNC 派发是已鉴权请求的延续，放行即可。
                requests.shouldFilterAllDispatcherTypes(false);
                permitAllUrl.getUrls().forEach(url -> requests.requestMatchers(url).permitAll());
                // 对于登录login 注册register 验证码captchaImage 允许匿名访问
                requests.requestMatchers("/login", "/register", "/captchaImage", "/oauth2/**", "/login/oauth2/**").permitAll()
                    // WebSocket 握手使用短时一次性票据认证，浏览器原生 WebSocket 无法设置 Authorization 头
                    .requestMatchers("/ws/ai/chat").permitAll()
                    // 知识库上传目录禁止静态匿名访问（须在 /profile/** permitAll 之前；下载走受控 API）
                    .requestMatchers("/profile/upload/kb/**").denyAll()
                    // 静态资源，可匿名访问
                    .requestMatchers(HttpMethod.GET, "/", "/*.html", "/**.html", "/**.css", "/**.js", "/profile/**").permitAll()
                    .requestMatchers("/swagger-ui.html", "/v3/api-docs/**", "/swagger-ui/**", "/druid/**").permitAll()
                    // 除上面外的所有请求全部需要鉴权认证
                    .anyRequest().authenticated();
            })
            // 添加Logout filter
            .logout(logout -> logout.logoutUrl("/logout").logoutSuccessHandler(logoutSuccessHandler))
            // 添加JWT filter
            .addFilterBefore(authenticationTokenFilter, UsernamePasswordAuthenticationFilter.class)
            // 授权请求带 target=desktop 时，将回调目标保存在 OAuth session 中。
            .addFilterBefore(new OAuth2LoginTargetFilter(), OAuth2AuthorizationRequestRedirectFilter.class)
            // 添加CORS filter
            .addFilterBefore(corsFilter, JwtAuthenticationTokenFilter.class)
            .addFilterBefore(corsFilter, LogoutFilter.class);
        configureOAuth2Login(httpSecurity);
        return httpSecurity.build();
    }

    /**
     * OAuth2 配置与原有 JWT 链路并存：回调成功后由成功处理器签发原有 JWT。
     */
    private void configureOAuth2Login(HttpSecurity httpSecurity) throws Exception
    {
        if (!oauthLoginProperties.isEnabled())
        {
            return;
        }
        OAuth2LoginSuccessHandler successHandler = oauth2LoginSuccessHandler.getIfAvailable();
        if (successHandler == null)
        {
            throw new IllegalStateException("OAuth 登录已启用，但未初始化 OAuth2LoginSuccessHandler");
        }
        httpSecurity.oauth2Login(oauth2 -> oauth2
            .successHandler(successHandler)
            .failureHandler((request, response, exception) -> {
                String frontendUrl = successHandler.frontendUrl(request);
                successHandler.clearLoginTarget(request);
                response.sendRedirect(
                UriComponentsBuilder.fromUriString(frontendUrl)
                    .path("/login")
                    .queryParam("oauthError", "authorization_failed")
                    .build()
                    .toUriString());
            }));
    }

    /**
     * 强散列哈希加密实现
     */
    @Bean
    public BCryptPasswordEncoder bCryptPasswordEncoder()
    {
        return new BCryptPasswordEncoder();
    }
}
