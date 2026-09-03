package com.ruoyi.system.mapper;

import org.apache.ibatis.annotations.Param;
import com.ruoyi.system.domain.SysUserOauthAccount;

/**
 * 外部身份绑定数据层。
 */
public interface SysUserOauthAccountMapper
{
    SysUserOauthAccount selectByIssuerAndSubject(@Param("issuer") String issuer, @Param("subject") String subject);

    int insert(SysUserOauthAccount account);

    int updateProfile(SysUserOauthAccount account);
}
