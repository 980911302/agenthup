package com.ruoyi.system.domain.vo;

/**
 * 当前登录用户对单库的 capability 计算结果。
 * <p>仅描述权限，不包含成员列表、智能体或平台配置。
 */
public class KbAccessVO
{
    /**
     * 权限来源：PLATFORM_ADMIN / OWNER / ACL / DEPT / ORG / LEGACY
     */
    private String source;

    /**
     * 显式角色：VIEWER / EDITOR / QUALITY / OWNER；范围只读时可为 null
     */
    private String role;

    private boolean canRead;
    private boolean canUse;
    private boolean canWrite;
    private boolean canManage;
    private boolean canDelete;
    private boolean isPlatformAdmin;

    public String getSource()
    {
        return source;
    }

    public void setSource(String source)
    {
        this.source = source;
    }

    public String getRole()
    {
        return role;
    }

    public void setRole(String role)
    {
        this.role = role;
    }

    public boolean getCanRead()
    {
        return canRead;
    }

    public void setCanRead(boolean canRead)
    {
        this.canRead = canRead;
    }

    public boolean getCanUse()
    {
        return canUse;
    }

    public void setCanUse(boolean canUse)
    {
        this.canUse = canUse;
    }

    public boolean getCanWrite()
    {
        return canWrite;
    }

    public void setCanWrite(boolean canWrite)
    {
        this.canWrite = canWrite;
    }

    public boolean getCanManage()
    {
        return canManage;
    }

    public void setCanManage(boolean canManage)
    {
        this.canManage = canManage;
    }

    public boolean getCanDelete()
    {
        return canDelete;
    }

    public void setCanDelete(boolean canDelete)
    {
        this.canDelete = canDelete;
    }

    public boolean getIsPlatformAdmin()
    {
        return isPlatformAdmin;
    }

    public void setIsPlatformAdmin(boolean isPlatformAdmin)
    {
        this.isPlatformAdmin = isPlatformAdmin;
    }
}
