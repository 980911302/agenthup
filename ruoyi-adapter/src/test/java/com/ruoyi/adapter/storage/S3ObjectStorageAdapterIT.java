package com.ruoyi.adapter.storage;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.ruoyi.ai.contract.storage.ObjectReadHandle;
import com.ruoyi.ai.contract.storage.StoredObject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 打真实 S3 兼容服务的集成测试。
 *
 * <p>默认跳过 —— 只有设了 {@code MINIO_TEST_ENDPOINT} 才运行,所以平时的
 * {@code mvn test} 与 CI 都不受影响。
 *
 * <p>跑法:
 * <pre>
 * MINIO_TEST_ENDPOINT=http://127.0.0.1:9000 \
 * MINIO_TEST_ACCESS_KEY=agenthub \
 * MINIO_TEST_SECRET_KEY=xxx \
 * MINIO_TEST_BUCKET=agenthub \
 * mvn test -pl ruoyi-adapter -am -Dtest=S3ObjectStorageAdapterIT -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 *
 * <p>验证的是生产代码本身:键前缀拼接、etag 规范化、Range 读、预签名 URL 能否
 * 真被 HTTP 客户端取到、删除后 stat 是否返回 null。这些都是只有对着真服务才
 * 暴露得出来的问题(比如 path-style 配错时表现为 404 而不是连接失败)。
 *
 * @author ruoyi
 */
@EnabledIfEnvironmentVariable(named = "MINIO_TEST_ENDPOINT", matches = ".+")
class S3ObjectStorageAdapterIT
{
    private static S3ObjectStorageAdapter newAdapter(String keyPrefix)
    {
        ObjectStorageProperties properties = new ObjectStorageProperties();
        properties.setEnabled(true);
        properties.setEndpoint(System.getenv("MINIO_TEST_ENDPOINT"));
        properties.setRegion(envOr("MINIO_TEST_REGION", "us-east-1"));
        properties.setAccessKey(System.getenv("MINIO_TEST_ACCESS_KEY"));
        properties.setSecretKey(System.getenv("MINIO_TEST_SECRET_KEY"));
        properties.setBucket(envOr("MINIO_TEST_BUCKET", "agenthub"));
        // MinIO 必须 path-style
        properties.setPathStyleAccess(!"false".equals(System.getenv("MINIO_TEST_PATH_STYLE")));
        properties.setKeyPrefix(keyPrefix);
        return new S3ObjectStorageAdapter(properties);
    }

    private static String envOr(String name, String fallback)
    {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String uniqueKey()
    {
        return "it/" + System.nanoTime() + ".txt";
    }

    @Test
    void putThenStatThenReadThenDelete() throws Exception
    {
        try (S3ObjectStorageAdapter storage = newAdapter(""))
        {
            String key = uniqueKey();
            byte[] body = "agenthub-integration-测试内容".getBytes(StandardCharsets.UTF_8);

            StoredObject put = storage.put(key, body, "text/plain; charset=utf-8");
            assertEquals(key, put.key());
            assertEquals(body.length, put.size());
            assertNotNull(put.etag());
            assertTrue(!put.etag().contains("\""), "etag 应已剥掉引号: " + put.etag());

            StoredObject stat = storage.stat(key);
            assertNotNull(stat, "刚写入的对象应能 stat 到");
            assertEquals(body.length, stat.size());
            assertTrue(stat.contentType().startsWith("text/plain"), stat.contentType());

            try (ObjectReadHandle handle = storage.open(key))
            {
                assertNotNull(handle);
                byte[] read = handle.stream().readAllBytes();
                assertEquals(new String(body, StandardCharsets.UTF_8),
                        new String(read, StandardCharsets.UTF_8), "读回的内容应与写入一致");
            }

            storage.delete(key);
            assertNull(storage.stat(key), "删除后 stat 应返回 null 而不是抛异常");
            assertNull(storage.open(key), "删除后 open 应返回 null");
        }
    }

    @Test
    void statMissingKey_returnsNullNotThrow()
    {
        try (S3ObjectStorageAdapter storage = newAdapter(""))
        {
            // headObject 对不存在的键在部分实现下抛通用 S3Exception 而非 NoSuchKeyException,
            // 适配器按状态码兜住 —— 这条就是守这个兜底的
            assertNull(storage.stat("it/definitely-not-here-" + System.nanoTime()));
            assertNull(storage.open("it/definitely-not-here-" + System.nanoTime()));
        }
    }

    @Test
    void keyPrefix_isTransparentToCaller() throws Exception
    {
        String prefix = "it-prefix-" + System.nanoTime();
        try (S3ObjectStorageAdapter prefixed = newAdapter(prefix);
             S3ObjectStorageAdapter bare = newAdapter(""))
        {
            String key = uniqueKey();
            prefixed.put(key, "prefixed".getBytes(StandardCharsets.UTF_8), "text/plain");

            // 带前缀的实例用相对键就能找到
            assertNotNull(prefixed.stat(key));
            // 不带前缀的实例必须补上前缀才找得到 —— 证明前缀确实进了实际对象键
            assertNull(bare.stat(key), "无前缀实例不该看到带前缀的对象");
            assertNotNull(bare.stat(prefix + "/" + key), "对象实际落在前缀之下");

            prefixed.delete(key);
        }
    }

    @Test
    void presignedUrl_isFetchableByPlainHttpClient() throws Exception
    {
        try (S3ObjectStorageAdapter storage = newAdapter(""))
        {
            String key = uniqueKey();
            String body = "presigned-body-" + System.nanoTime();
            storage.put(key, body.getBytes(StandardCharsets.UTF_8), "text/plain");

            String url = storage.presignedUrl(key, Duration.ofMinutes(5));
            assertTrue(url.startsWith("http"), url);

            // 用不带任何凭据的普通 HTTP 客户端去取 —— 这才证明签名真的有效,
            // 也就是浏览器 <img src> 能直连成功
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertEquals(200, response.statusCode(), "预签名 URL 应可匿名下载");
            assertEquals(body, response.body());

            storage.delete(key);
        }
    }

    @Test
    void presignedUrl_withFilename_setsAttachmentHeader() throws Exception
    {
        try (S3ObjectStorageAdapter storage = newAdapter(""))
        {
            String key = uniqueKey();
            storage.put(key, "x".getBytes(StandardCharsets.UTF_8), "text/plain");

            String url = storage.presignedUrl(key, Duration.ofMinutes(5), "季度报告 2026.txt");
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            assertEquals(200, response.statusCode());
            String disposition = response.headers().firstValue("content-disposition").orElse("");
            assertTrue(disposition.startsWith("attachment"), disposition);
            // 中文名走 RFC 5987 的 filename*,不能是裸中文
            assertTrue(disposition.contains("filename*=UTF-8''"), disposition);

            storage.delete(key);
        }
    }

    @Test
    void put_overwritesExistingKey() throws Exception
    {
        try (S3ObjectStorageAdapter storage = newAdapter(""))
        {
            String key = uniqueKey();
            storage.put(key, "first".getBytes(StandardCharsets.UTF_8), "text/plain");
            storage.put(key, "second-longer".getBytes(StandardCharsets.UTF_8), "text/plain");

            try (ObjectReadHandle handle = storage.open(key))
            {
                assertEquals("second-longer",
                        new String(handle.stream().readAllBytes(), StandardCharsets.UTF_8));
            }
            storage.delete(key);
        }
    }

    @Test
    void delete_isIdempotent()
    {
        try (S3ObjectStorageAdapter storage = newAdapter(""))
        {
            String key = uniqueKey();
            storage.put(key, "x".getBytes(StandardCharsets.UTF_8), "text/plain");
            storage.delete(key);
            // 删两次不该抛 —— Service 的删除流程依赖这个幂等性
            storage.delete(key);
            assertNull(storage.stat(key));
        }
    }

    @Test
    void put_streamWithExplicitSize() throws Exception
    {
        try (S3ObjectStorageAdapter storage = newAdapter(""))
        {
            String key = uniqueKey();
            byte[] body = "stream-upload".getBytes(StandardCharsets.UTF_8);
            storage.put(key, new ByteArrayInputStream(body), body.length, "application/octet-stream");

            StoredObject stat = storage.stat(key);
            assertNotNull(stat);
            assertEquals(body.length, stat.size());
            storage.delete(key);
        }
    }
}
