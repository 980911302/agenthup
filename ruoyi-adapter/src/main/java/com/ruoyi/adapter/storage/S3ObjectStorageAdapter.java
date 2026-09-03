package com.ruoyi.adapter.storage;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import com.ruoyi.ai.contract.storage.ObjectReadHandle;
import com.ruoyi.ai.contract.storage.ObjectStorage;
import com.ruoyi.ai.contract.storage.ObjectWriteRequest;
import com.ruoyi.ai.contract.storage.StoredObject;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/**
 * {@link ObjectStorage} 的 S3 协议实现,同时覆盖 MinIO / 阿里云 OSS / 腾讯云 COS / Cloudflare R2。
 *
 * <p>由 {@link ObjectStorageConfig} 在 {@code ruoyi.ai.storage.enabled=true} 时构造;关闭时整个
 * bean 不存在,上层用 {@code ObjectProvider} 拿不到就给出「未配置」的业务提示,而不是启动即失败。
 *
 * <p><b>为什么用同步 {@link S3Client} 而不是异步</b>:调用方是 Servlet 线程上的上传/下载,
 * 拿到异步 Future 后还是得阻塞等,徒增一层线程切换;而 {@code S3AsyncClient} 会把 netty 拉进
 * 依赖树,与 Spring AI 用的 reactor-netty 版本对齐是额外的维护负担。
 *
 * @author ruoyi
 */
public class S3ObjectStorageAdapter implements ObjectStorage, AutoCloseable
{
    private static final Logger log = LoggerFactory.getLogger(S3ObjectStorageAdapter.class);

    private final ObjectStorageProperties properties;
    private final S3Client client;
    private final S3Presigner presigner;

    public S3ObjectStorageAdapter(ObjectStorageProperties properties)
    {
        this.properties = properties;
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(properties.getAccessKey(), properties.getSecretKey()));
        Region region = Region.of(properties.getRegion());
        URI endpoint = URI.create(properties.getEndpoint());
        S3Configuration s3Configuration = S3Configuration.builder()
                .pathStyleAccessEnabled(properties.isPathStyleAccess())
                .build();

        this.client = S3Client.builder()
                .endpointOverride(endpoint)
                .region(region)
                .credentialsProvider(credentials)
                .serviceConfiguration(s3Configuration)
                .httpClientBuilder(ApacheHttpClient.builder())
                .build();
        // presigner 必须与 client 用同一套 endpoint / pathStyle 配置,否则签出来的 URL
        // 主机名形态和实际服务对不上,表现为 403 SignatureDoesNotMatch
        this.presigner = S3Presigner.builder()
                .endpointOverride(endpoint)
                .region(region)
                .credentialsProvider(credentials)
                .serviceConfiguration(s3Configuration)
                .build();
        log.info("对象存储已启用: endpoint={} bucket={} pathStyle={}",
                properties.getEndpoint(), properties.getBucket(), properties.isPathStyleAccess());
    }

    @Override
    public StoredObject put(ObjectWriteRequest request)
    {
        String key = absoluteKey(request.key());
        PutObjectRequest.Builder builder = PutObjectRequest.builder()
                .bucket(properties.getBucket())
                .key(key)
                .contentLength(request.size());
        if (StringUtils.hasText(request.contentType()))
        {
            builder.contentType(request.contentType());
        }
        PutObjectResponse response = client.putObject(builder.build(),
                RequestBody.fromInputStream(request.content(), request.size()));
        return new StoredObject(request.key(), request.size(), request.contentType(),
                normalizeEtag(response.eTag()), Instant.now());
    }

    @Override
    public ObjectReadHandle open(String key)
    {
        try
        {
            ResponseInputStream<GetObjectResponse> stream = client.getObject(GetObjectRequest.builder()
                    .bucket(properties.getBucket())
                    .key(absoluteKey(key))
                    .build());
            GetObjectResponse response = stream.response();
            StoredObject object = new StoredObject(key, response.contentLength(),
                    response.contentType(), normalizeEtag(response.eTag()), response.lastModified());
            return new S3ReadHandle(object, stream);
        }
        catch (NoSuchKeyException e)
        {
            return null;
        }
    }

    @Override
    public StoredObject stat(String key)
    {
        try
        {
            HeadObjectResponse response = client.headObject(HeadObjectRequest.builder()
                    .bucket(properties.getBucket())
                    .key(absoluteKey(key))
                    .build());
            return new StoredObject(key, response.contentLength(), response.contentType(),
                    normalizeEtag(response.eTag()), response.lastModified());
        }
        catch (NoSuchKeyException e)
        {
            return null;
        }
        catch (S3Exception e)
        {
            // headObject 对不存在的键返回 404 但不带错误体,部分实现(含 MinIO)因此抛的是
            // 通用 S3Exception 而不是 NoSuchKeyException,这里按状态码兜住
            if (e.statusCode() == 404) return null;
            throw e;
        }
    }

    @Override
    public void delete(String key)
    {
        client.deleteObject(DeleteObjectRequest.builder()
                .bucket(properties.getBucket())
                .key(absoluteKey(key))
                .build());
    }

    @Override
    public String presignedUrl(String key, Duration ttl, String downloadFilename)
    {
        GetObjectRequest.Builder get = GetObjectRequest.builder()
                .bucket(properties.getBucket())
                .key(absoluteKey(key));
        if (StringUtils.hasText(downloadFilename))
        {
            get.responseContentDisposition(contentDisposition(downloadFilename));
        }
        return presigner.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .getObjectRequest(get.build())
                .build()).url().toString();
    }

    /** 拼上部署级 keyPrefix。对外的 key 始终是不含前缀的相对键,换前缀不用改库。 */
    private String absoluteKey(String key)
    {
        String prefix = properties.getKeyPrefix();
        if (!StringUtils.hasText(prefix)) return key;
        return prefix.endsWith("/") ? prefix + key : prefix + "/" + key;
    }

    /** S3 的 ETag 带引号,存库前剥掉,免得比对时因引号不等而误判「已被修改」。 */
    private static String normalizeEtag(String etag)
    {
        if (etag == null) return null;
        return etag.replace("\"", "");
    }

    /** 非 ASCII 文件名必须走 RFC 5987 的 filename*,否则中文名下载后变乱码或被截断。 */
    private static String contentDisposition(String filename)
    {
        String encoded = java.net.URLEncoder.encode(filename, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
        return "attachment; filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded;
    }

    @Override
    public void close()
    {
        try { presigner.close(); } catch (Exception ignored) { }
        try { client.close(); } catch (Exception ignored) { }
    }

    private record S3ReadHandle(StoredObject object, InputStream stream) implements ObjectReadHandle
    {
        @Override public void close()
        {
            try { stream.close(); }
            catch (Exception ignored) { }
        }
    }
}
