package com.ruoyi.system.domain;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

import com.ruoyi.common.core.domain.BaseEntity;

/**
 * 用户个人文件表 ai_user_file。
 *
 * <p>desktop「文件」菜单的实体。生命周期跟人走,与任何一次会话无关 —— 这是它和
 * 会话工作区(随会话删除而级联清空)最本质的区别。
 *
 * <p>正文在 S3 兼容对象存储里,本实体只持有 {@link #objectKey} 这个指针。
 *
 * @author ruoyi
 */
public class AiUserFile extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 文件ID */
    private Long fileId;

    /** 归属用户ID(sys_user.user_id) */
    private Long userId;

    /** 原始文件名(含扩展名) */
    private String fileName;

    /** 对象键,不含部署级 keyPrefix */
    private String objectKey;

    /** 字节数 */
    private Long fileSize;

    /** MIME 类型 */
    private String contentType;

    /** 正文 SHA-256 */
    private String contentHash;

    /** 删除标志(0存在 2删除) */
    private String delFlag;

    /** 查询用:文件名模糊关键字。不落库 */
    private String keyword;

    /** 管理端展示用:归属用户登录名。来自 join sys_user,不落库 */
    private String userName;

    /** 管理端展示用:归属用户昵称。来自 join sys_user,不落库 */
    private String nickName;

    /**
     * 查询用:排序模式(date / name / size)。不落库。
     *
     * <p><b>不能叫 orderBy</b>:PageHelper 开了 {@code supportMethodsArguments: true}
     * (application.yml),它会反射读取参数对象里名为 {@code orderBy} 的属性并拼成
     * {@code order by ${orderBy}},绕过 Mapper XML 里的 choose 白名单 —— 既会因为
     * "date" 不是真实列名而直接报 Unknown column,也等于把用户可控字符串送进 SQL 拼接。
     */
    private String sortMode;

    public Long getFileId() { return fileId; }
    public void setFileId(Long fileId) { this.fileId = fileId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getObjectKey() { return objectKey; }
    public void setObjectKey(String objectKey) { this.objectKey = objectKey; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public String getDelFlag() { return delFlag; }
    public void setDelFlag(String delFlag) { this.delFlag = delFlag; }
    public String getKeyword() { return keyword; }
    public void setKeyword(String keyword) { this.keyword = keyword; }
    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }
    public String getNickName() { return nickName; }
    public void setNickName(String nickName) { this.nickName = nickName; }
    public String getSortMode() { return sortMode; }
    public void setSortMode(String sortMode) { this.sortMode = sortMode; }

    /** 是否图片。决定前端走内联预览还是只给下载。 */
    public boolean isImage()
    {
        return contentType != null && contentType.startsWith("image/");
    }

    @Override
    public String toString()
    {
        return new ToStringBuilder(this, ToStringStyle.MULTI_LINE_STYLE)
                .append("fileId", fileId)
                .append("userId", userId)
                .append("fileName", fileName)
                .append("objectKey", objectKey)
                .append("fileSize", fileSize)
                .append("contentType", contentType)
                .append("contentHash", contentHash)
                .append("delFlag", delFlag)
                .append("createBy", getCreateBy())
                .append("createTime", getCreateTime())
                .append("updateTime", getUpdateTime())
                .toString();
    }
}
