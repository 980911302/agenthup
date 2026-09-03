package com.ruoyi.adapter.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import com.ruoyi.ai.contract.storage.ObjectStorage;

/**
 * 对象存储装配。
 *
 * <p>刻意用 {@code @ConditionalOnProperty} 而不是无条件建 bean:没配存储的开发机、跑单测的 CI
 * 都不该因为「个人文件模块」而启动失败。上层用 {@code ObjectProvider<ObjectStorage>} 取,
 * 取不到就返回业务提示。
 *
 * @author ruoyi
 */
@Configuration
@EnableConfigurationProperties(ObjectStorageProperties.class)
public class ObjectStorageConfig
{
    /**
     * S3 兼容对象存储客户端。
     *
     * <p>{@code destroyMethod} 显式指向 close:S3Client 与 presigner 各持一个连接池,
     * 容器关闭时不释放会在热重启场景下堆积。
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "ruoyi.ai.storage", name = "enabled", havingValue = "true")
    public ObjectStorage objectStorage(ObjectStorageProperties properties)
    {
        requireText(properties.getEndpoint(), "ruoyi.ai.storage.endpoint");
        requireText(properties.getAccessKey(), "ruoyi.ai.storage.access-key");
        requireText(properties.getSecretKey(), "ruoyi.ai.storage.secret-key");
        requireText(properties.getBucket(), "ruoyi.ai.storage.bucket");
        return new S3ObjectStorageAdapter(properties);
    }

    /**
     * 启用了却漏配必填项时,启动期就报清楚是哪一项 —— 比运行到第一次上传才抛
     * SdkClientException 好排查得多。
     */
    private static void requireText(String value, String name)
    {
        if (!StringUtils.hasText(value))
        {
            throw new IllegalStateException("已开启 ruoyi.ai.storage.enabled,但缺少必填配置: " + name);
        }
    }
}
