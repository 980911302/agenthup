package com.ruoyi.ai.contract.storage;

import java.io.InputStream;

/**
 * 对象写入请求。
 *
 * <p>{@code size} 必须准确:S3 兼容存储的单次 PUT 需要预先声明 Content-Length,
 * 传 -1 会退化成把整个流读进内存再算长度,大文件直接打爆堆。
 *
 * @param key         对象键
 * @param content     内容流,由调用方负责关闭
 * @param size        字节数
 * @param contentType MIME 类型,可为 null
 *
 * @author ruoyi
 */
public record ObjectWriteRequest(String key, InputStream content, long size, String contentType)
{
    public ObjectWriteRequest
    {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("对象键不能为空");
        if (content == null) throw new IllegalArgumentException("对象内容不能为空");
        if (size < 0) throw new IllegalArgumentException("对象大小必须已知且非负");
    }
}
