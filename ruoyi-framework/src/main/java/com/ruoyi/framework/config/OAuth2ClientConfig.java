package com.ruoyi.framework.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.util.Assert;
import com.ruoyi.framework.config.properties.OAuthLoginProperties;

/**
 * 只在开启后读取 Keycloak OIDC 元数据，避免本地默认启动依赖外部身份服务。
 */
@Configuration
@ConditionalOnProperty(prefix = "oauth.login", name = "enabled", havingValue = "true")
public class OAuth2ClientConfig
{
    @Bean
    public ClientRegistrationRepository clientRegistrationRepository(OAuthLoginProperties properties)
    {
        OAuthLoginProperties.Keycloak keycloak = properties.getKeycloak();
        Assert.hasText(keycloak.getIssuerUri(), "开启 OAuth 登录时必须配置 oauth.login.keycloak.issuer-uri");
        Assert.hasText(keycloak.getClientId(), "开启 OAuth 登录时必须配置 oauth.login.keycloak.client-id");
        Assert.hasText(keycloak.getClientSecret(), "开启 OAuth 登录时必须配置 oauth.login.keycloak.client-secret");

        ClientRegistration registration = ClientRegistrations.fromIssuerLocation(keycloak.getIssuerUri())
            .registrationId("keycloak")
            .clientId(keycloak.getClientId())
            .clientSecret(keycloak.getClientSecret())
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
            .scope(OidcScopes.OPENID, OidcScopes.PROFILE, OidcScopes.EMAIL)
            .userNameAttributeName("preferred_username")
            .clientName("Keycloak")
            .build();
        return new InMemoryClientRegistrationRepository(registration);
    }
}
