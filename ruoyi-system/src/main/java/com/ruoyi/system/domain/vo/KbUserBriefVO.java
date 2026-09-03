package com.ruoyi.system.domain.vo;

/**
 * 知识库相关用户简要展示（owner / member 回填，禁止用 createBy 冒充）。
 */
public class KbUserBriefVO
{
    private Long userId;
    private String userName;
    private String nickName;
    private String deptName;

    public KbUserBriefVO()
    {
    }

    public KbUserBriefVO(Long userId, String userName, String nickName)
    {
        this.userId = userId;
        this.userName = userName;
        this.nickName = nickName;
    }

    public KbUserBriefVO(Long userId, String userName, String nickName, String deptName)
    {
        this.userId = userId;
        this.userName = userName;
        this.nickName = nickName;
        this.deptName = deptName;
    }

    public Long getUserId()
    {
        return userId;
    }

    public void setUserId(Long userId)
    {
        this.userId = userId;
    }

    public String getUserName()
    {
        return userName;
    }

    public void setUserName(String userName)
    {
        this.userName = userName;
    }

    public String getNickName()
    {
        return nickName;
    }

    public void setNickName(String nickName)
    {
        this.nickName = nickName;
    }

    public String getDeptName()
    {
        return deptName;
    }

    public void setDeptName(String deptName)
    {
        this.deptName = deptName;
    }
}
