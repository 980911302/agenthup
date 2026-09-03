package com.ruoyi.ai.contract.storage;

import java.time.Instant;

/**
 * 对象存储里一个对象的元数据。
 *
 * @param key         对象键
 * @param size        字节数
 * @param contentType MIME 类型,可能为 null(上游没给)
 * @param etag        存储侧实体标签,用于校验与幂等判断
 * @param modifiedAt  最后修改时间
 *
 * @author ruoyi
 */
public record StoredObject(String key, long size, String contentType, String etag,
                           Instant modifiedAt)
{
}
