package com.ruoyi.adapter.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * S3 兼容对象存储配置,前缀 {@code ruoyi.ai.storage}。
 *
 * <p>同一套配置对接四类服务,差别只在 {@code endpoint} / {@code region} / {@code pathStyleAccess}:
 * <pre>
 * MinIO        endpoint=http://127.0.0.1:9000  region=us-east-1        pathStyleAccess=true
 * 阿里云 OSS    endpoint=https://oss-cn-hangzhou.aliyuncs.com  region=oss-cn-hangzhou  pathStyleAccess=false
 * 腾讯云 COS    endpoint=https://cos.ap-guangzhou.myqcloud.com region=ap-guangzhou     pathStyleAccess=false
 * Cloudflare R2 endpoint=https://{account}.r2.cloudflarestorage.com  region=auto       pathStyleAccess=true
 * </pre>
 *
 * <p>这里只放<b>怎么连存储</b>的参数。配额、单文件上限、预签名时长这类<b>业务规则</b>在
 * {@code ruoyi.ai.user-file}(AiUserFileProperties),因为它们与用哪家对象存储无关 ——
 * 换 MinIO 为 OSS 不该顺带改配额。
 *
 * @author ruoyi
 */
@ConfigurationProperties(prefix = "ruoyi.ai.storage")
public class ObjectStorageProperties
{
    /** 总开关。关闭时不构造 S3 客户端,个人文件接口返回「未配置对象存储」而不是启动失败。 */
    private boolean enabled = false;

    /** 服务端点,含协议。 */
    private String endpoint;

    /**
     * 区域。MinIO 不校验但 SDK 必填,随便给个合法值即可(约定 us-east-1);
     * 云服务必须与实际区域一致,否则签名校验失败。
     */
    private String region = "us-east-1";

    /** 访问密钥 ID。 */
    private String accessKey;

    /** 访问密钥。 */
    private String secretKey;

    /** 桶名。 */
    private String bucket;

    /**
     * 是否用路径风格寻址({@code host/bucket/key})。
     *
     * <p>MinIO 与 R2 必须 true;OSS / COS 用虚拟主机风格({@code bucket.host/key}),设 false。
     * 设错的症状是 404 或 SignatureDoesNotMatch,不是连不上,排查时容易被误导。
     */
    private boolean pathStyleAccess = true;

    /** 对象键统一前缀,便于同一个桶给多个环境/应用共用。空表示不加前缀。 */
    private String keyPrefix = "";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getAccessKey() { return accessKey; }
    public void setAccessKey(String accessKey) { this.accessKey = accessKey; }
    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }
    public boolean isPathStyleAccess() { return pathStyleAccess; }
    public void setPathStyleAccess(boolean pathStyleAccess) { this.pathStyleAccess = pathStyleAccess; }
    public String getKeyPrefix() { return keyPrefix; }
    public void setKeyPrefix(String keyPrefix) { this.keyPrefix = keyPrefix; }
}
