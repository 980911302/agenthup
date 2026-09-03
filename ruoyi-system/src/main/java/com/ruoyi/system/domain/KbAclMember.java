package com.ruoyi.system.domain;

import java.util.Date;

/**
 * 知识库成员 ACL kb_acl_member
 * <p>role: VIEWER / EDITOR / QUALITY / OWNER
 */
public class KbAclMember
{
    private Long id;
    private Long kbId;
    private Long userId;
    private String role;
    private String createBy;
    private Date createTime;

    /** 非表字段：展示名（批量回填，禁止 N+1） */
    private String userName;
    private String nickName;
    private String deptName;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getKbId() { return kbId; }
    public void setKbId(Long kbId) { this.kbId = kbId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getCreateBy() { return createBy; }
    public void setCreateBy(String createBy) { this.createBy = createBy; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }
    public String getNickName() { return nickName; }
    public void setNickName(String nickName) { this.nickName = nickName; }
    public String getDeptName() { return deptName; }
    public void setDeptName(String deptName) { this.deptName = deptName; }
}
