package com.ruoyi.framework.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Keycloak 登录配置。
 *
 * <p>认证请求由后端发起，避免把 client secret 暴露给 SPA；回调成功后仍签发系统原有 JWT。</p>
 */
@ConfigurationProperties(prefix = "oauth.login")
public class OAuthLoginProperties
{
    /** 是否开启 OAuth/OIDC 登录 */
    private boolean enabled = false;

    /** OAuth 回调完成后跳回的前端地址 */
    private String frontendUrl = "http://localhost:80";

    /** Desktop OAuth 回调完成后跳回的前端地址 */
    private String desktopFrontendUrl = "http://localhost:5175";

    /** 对外可访问的后端地址，用于从登录页跳转到授权入口 */
    private String backendUrl = "http://localhost:8080";

    /** 未绑定的外部账号是否自动创建本地用户，默认关闭以避免未授权开户 */
    private boolean autoRegister = false;

    /** 自动创建用户时授予的系统角色 ID */
    private Long defaultRoleId;

    /** 自动创建用户时归属的默认部门 ID */
    private Long defaultDeptId;

    private Keycloak keycloak = new Keycloak();

    public boolean isEnabled()
    {
        return enabled;
    }

    public void setEnabled(boolean enabled)
    {
        this.enabled = enabled;
    }

    public String getFrontendUrl()
    {
        return trimTrailingSlash(frontendUrl);
    }

    public void setFrontendUrl(String frontendUrl)
    {
        this.frontendUrl = frontendUrl;
    }

    public String getDesktopFrontendUrl()
    {
        return trimTrailingSlash(desktopFrontendUrl);
    }

    public void setDesktopFrontendUrl(String desktopFrontendUrl)
    {
        this.desktopFrontendUrl = desktopFrontendUrl;
    }

    public String getBackendUrl()
    {
        return trimTrailingSlash(backendUrl);
    }

    public void setBackendUrl(String backendUrl)
    {
        this.backendUrl = backendUrl;
    }

    public boolean isAutoRegister()
    {
        return autoRegister;
    }

    public void setAutoRegister(boolean autoRegister)
    {
        this.autoRegister = autoRegister;
    }

    public Long getDefaultRoleId()
    {
        return defaultRoleId;
    }

    public void setDefaultRoleId(Long defaultRoleId)
    {
        this.defaultRoleId = defaultRoleId;
    }

    public Long getDefaultDeptId()
    {
        return defaultDeptId;
    }

    public void setDefaultDeptId(Long defaultDeptId)
    {
        this.defaultDeptId = defaultDeptId;
    }

    public Keycloak getKeycloak()
    {
        return keycloak;
    }

    public void setKeycloak(Keycloak keycloak)
    {
        this.keycloak = keycloak;
    }

    private String trimTrailingSlash(String value)
    {
        if (value == null)
        {
            return "";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    public static class Keycloak
    {
        /** Keycloak Realm 的 OIDC issuer，例如 http://localhost:18080/realms/agent */
        private String issuerUri;

        private String clientId;

        private String clientSecret;

        public String getIssuerUri()
        {
            return issuerUri;
        }

        public void setIssuerUri(String issuerUri)
        {
            this.issuerUri = issuerUri;
        }

        public String getClientId()
        {
            return clientId;
        }

        public void setClientId(String clientId)
        {
            this.clientId = clientId;
        }

        public String getClientSecret()
        {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret)
        {
            this.clientSecret = clientSecret;
        }
    }
}
