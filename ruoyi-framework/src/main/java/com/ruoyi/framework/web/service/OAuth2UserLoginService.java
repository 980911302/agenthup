package com.ruoyi.framework.web.service;

import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.enums.UserStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.DateUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.utils.uuid.IdUtils;
import com.ruoyi.framework.config.properties.OAuthLoginProperties;
import com.ruoyi.system.domain.SysUserOauthAccount;
import com.ruoyi.system.mapper.SysUserOauthAccountMapper;
import com.ruoyi.system.service.ISysUserService;

/**
 * 将 Keycloak OIDC 身份映射为系统本地用户。
 */
@Component
public class OAuth2UserLoginService
{
    @Autowired
    private SysUserOauthAccountMapper oauthAccountMapper;

    @Autowired
    private ISysUserService userService;

    @Autowired
    private SysPermissionService permissionService;

    @Autowired
    private OAuthLoginProperties properties;

    @Transactional(rollbackFor = Exception.class)
    public LoginUser login(String provider, Map<String, Object> attributes)
    {
        String issuer = valueOf(attributes, "iss");
        if (StringUtils.isEmpty(issuer))
        {
            issuer = properties.getKeycloak().getIssuerUri();
        }
        String subject = valueOf(attributes, "sub");
        if (StringUtils.isEmpty(issuer) || StringUtils.isEmpty(subject))
        {
            throw new ServiceException("身份服务未返回有效的 issuer 或 sub");
        }

        SysUserOauthAccount account = oauthAccountMapper.selectByIssuerAndSubject(issuer, subject);
        SysUser user;
        if (account == null)
        {
            if (!properties.isAutoRegister())
            {
                throw new ServiceException("该外部账号尚未绑定系统用户，请联系管理员开通");
            }
            user = createUser(attributes);
            account = createAccount(provider, issuer, subject, attributes, user.getUserId());
            oauthAccountMapper.insert(account);
        }
        else
        {
            user = userService.selectUserById(account.getUserId());
            if (user == null)
            {
                throw new ServiceException("外部账号绑定的系统用户不存在");
            }
            updateAccountProfile(account, attributes);
        }
        validateUser(user);
        return new LoginUser(user.getUserId(), user.getDeptId(), user, permissionService.getMenuPermission(user));
    }

    private SysUser createUser(Map<String, Object> attributes)
    {
        Long roleId = properties.getDefaultRoleId();
        Long deptId = properties.getDefaultDeptId();
        if (roleId == null || deptId == null)
        {
            throw new ServiceException("自动创建用户时必须配置 oauth.login.default-role-id 和 oauth.login.default-dept-id");
        }
        String externalName = firstNonEmpty(valueOf(attributes, "preferred_username"), valueOf(attributes, "name"));
        SysUser user = new SysUser();
        user.setUserName(nextUserName(externalName));
        user.setNickName(limit(firstNonEmpty(valueOf(attributes, "name"), externalName, "OAuth 用户"), 30));
        user.setEmail(limit(valueOf(attributes, "email"), 50));
        user.setAvatar(limit(valueOf(attributes, "picture"), 100));
        user.setSex("2");
        user.setStatus(UserStatus.OK.getCode());
        user.setDeptId(deptId);
        user.setRoleIds(new Long[] { roleId });
        // OAuth 用户不使用系统初始密码，避免触发本地密码修改提醒。
        user.setPwdUpdateDate(DateUtils.getNowDate());
        user.setCreateBy("keycloak");
        user.setRemark("通过 Keycloak OAuth/OIDC 自动创建");
        userService.insertUser(user);
        return user;
    }

    private SysUserOauthAccount createAccount(String provider, String issuer, String subject,
            Map<String, Object> attributes, Long userId)
    {
        SysUserOauthAccount account = new SysUserOauthAccount();
        account.setUserId(userId);
        account.setProvider(provider);
        account.setIssuer(issuer);
        account.setSubject(subject);
        fillAccountProfile(account, attributes);
        return account;
    }

    private void updateAccountProfile(SysUserOauthAccount account, Map<String, Object> attributes)
    {
        fillAccountProfile(account, attributes);
        oauthAccountMapper.updateProfile(account);
    }

    private void fillAccountProfile(SysUserOauthAccount account, Map<String, Object> attributes)
    {
        account.setUserName(limit(firstNonEmpty(valueOf(attributes, "preferred_username"), valueOf(attributes, "name")), 100));
        account.setEmail(limit(valueOf(attributes, "email"), 255));
        account.setAvatar(limit(valueOf(attributes, "picture"), 500));
    }

    private void validateUser(SysUser user)
    {
        if (UserStatus.DELETED.getCode().equals(user.getDelFlag()))
        {
            throw new ServiceException("系统用户已删除");
        }
        if (UserStatus.DISABLE.getCode().equals(user.getStatus()))
        {
            throw new ServiceException("系统用户已停用");
        }
    }

    private String nextUserName(String source)
    {
        String normalized = StringUtils.isEmpty(source) ? "oauth" : source.replaceAll("[^a-zA-Z0-9_.-]", "_");
        normalized = limit(normalized, 20);
        if (StringUtils.isEmpty(normalized))
        {
            normalized = "oauth";
        }
        String candidate = normalized;
        while (!userService.checkUserNameUnique(newUserName(candidate)))
        {
            candidate = limit(normalized, 20) + "_" + IdUtils.fastSimpleUUID().substring(0, 8);
        }
        return candidate;
    }

    private SysUser newUserName(String userName)
    {
        SysUser user = new SysUser();
        user.setUserName(userName);
        return user;
    }

    private String valueOf(Map<String, Object> attributes, String key)
    {
        Object value = attributes.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String firstNonEmpty(String... values)
    {
        for (String value : values)
        {
            if (StringUtils.isNotEmpty(value))
            {
                return value;
            }
        }
        return "";
    }

    private String limit(String value, int length)
    {
        if (StringUtils.isEmpty(value))
        {
            return "";
        }
        return value.length() <= length ? value : value.substring(0, length);
    }
}
