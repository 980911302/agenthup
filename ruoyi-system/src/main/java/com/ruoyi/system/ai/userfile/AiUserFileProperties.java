package com.ruoyi.system.ai.userfile;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 个人文件业务规则,前缀 {@code ruoyi.ai.user-file}。
 *
 * <p>与 {@code ruoyi.ai.storage}(连接参数)分开:配额和限额是产品决策,换存储厂商时
 * 不该被一起改掉。
 *
 * @author ruoyi
 */
@Component
@ConfigurationProperties(prefix = "ruoyi.ai.user-file")
public class AiUserFileProperties
{
    /**
     * 单用户配额字节数,默认 1GB。desktop 配额条显示的分母就是它,
     * 替掉了 FilesView 里写死的 10MB。
     */
    private long userQuotaBytes = 1024L * 1024 * 1024;

    /**
     * 单文件上限字节数,默认 50MB。
     * 必须 ≤ {@code spring.servlet.multipart.max-file-size},否则超限请求会被 Tomcat
     * 在进 controller 之前就拒掉,用户看到的是 500 而不是这里给的友好提示。
     */
    private long maxFileBytes = 50L * 1024 * 1024;

    /** 单用户文件数上限,防止把个人空间当对象存储 API 用。 */
    private int maxFilesPerUser = 2000;

    /**
     * 预签名 URL 有效期秒数,默认 5 分钟。
     * 够浏览器加载完即可 —— 给长了等于把私有对象变成一条可转发的公开链接。
     */
    private long presignTtlSeconds = 300;

    public long getUserQuotaBytes() { return userQuotaBytes; }
    public void setUserQuotaBytes(long userQuotaBytes) { this.userQuotaBytes = userQuotaBytes; }
    public long getMaxFileBytes() { return maxFileBytes; }
    public void setMaxFileBytes(long maxFileBytes) { this.maxFileBytes = maxFileBytes; }
    public int getMaxFilesPerUser() { return maxFilesPerUser; }
    public void setMaxFilesPerUser(int maxFilesPerUser) { this.maxFilesPerUser = maxFilesPerUser; }
    public long getPresignTtlSeconds() { return presignTtlSeconds; }
    public void setPresignTtlSeconds(long presignTtlSeconds) { this.presignTtlSeconds = presignTtlSeconds; }
}
