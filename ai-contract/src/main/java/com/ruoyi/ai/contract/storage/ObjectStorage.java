package com.ruoyi.ai.contract.storage;

import java.io.InputStream;
import java.time.Duration;

/**
 * 对象存储契约:个人文件 / 附件这类「只上传下载、不参与 shell 运算」的二进制资产的落地口。
 *
 * <p><b>与 {@link com.ruoyi.ai.contract.workspace.WorkspaceStorage} 的分工</b>——两者刻意不合并:
 * <ul>
 *   <li>WorkspaceStorage 是 <i>会话沙箱</i> 语义:相对路径 + 目录树 + etag CAS,底下必须是 POSIX
 *       文件系统,因为 {@code bash} / {@code grep} / {@code edit} 工具直接在上面跑真实进程。</li>
 *   <li>ObjectStorage 是 <i>对象</i> 语义:扁平 key + 预签名 URL,底下是 S3 兼容存储,没有真目录、
 *       不能 seek 写,shell 工具永远不会碰它。</li>
 * </ul>
 * 把个人文件塞进 WorkspaceStorage 会逼着后者退化成对象语义,反过来则会给对象存储强加它做不到的
 * POSIX 承诺。分开是为了让两边各自的实现都能保持诚实。
 *
 * <p>本接口不出现 bucket 参数:桶是部署期配置,由实现方持有。调用方只认 key。
 *
 * @author ruoyi
 */
public interface ObjectStorage
{
    /**
     * 写入对象。同 key 覆盖。
     *
     * @param request 写入请求;{@code content} 由调用方负责关闭
     * @return 落地后的对象元数据
     */
    StoredObject put(ObjectWriteRequest request);

    /**
     * 打开对象读取流。
     *
     * @param key 对象键
     * @return 读取句柄;用完必须 close。对象不存在返回 {@code null}
     */
    ObjectReadHandle open(String key);

    /**
     * 读取对象元数据,不拉正文。
     *
     * @param key 对象键
     * @return 元数据;对象不存在返回 {@code null}
     */
    StoredObject stat(String key);

    /**
     * 删除对象。对象不存在时静默返回(幂等)。
     *
     * @param key 对象键
     */
    void delete(String key);

    /**
     * 生成带时效的下载直链,让浏览器绕开应用服务器直连存储。
     *
     * <p>用于图片/PDF 内联预览这类高频只读场景 —— 走应用服务器转发等于让每张缩略图
     * 都占一个 Tomcat 线程。
     *
     * @param key              对象键
     * @param ttl              有效期
     * @param downloadFilename 非空时在响应里带 {@code Content-Disposition: attachment},
     *                         浏览器按该名字另存;传 {@code null} 则内联展示
     * @return 预签名 URL
     */
    String presignedUrl(String key, Duration ttl, String downloadFilename);

    /** 便捷重载:内联预览用的预签名 URL。 */
    default String presignedUrl(String key, Duration ttl)
    {
        return presignedUrl(key, ttl, null);
    }

    /**
     * 便捷重载:直接用字节数组写入。
     *
     * @param key         对象键
     * @param content     内容
     * @param contentType MIME 类型
     */
    default StoredObject put(String key, byte[] content, String contentType)
    {
        return put(new ObjectWriteRequest(key, new java.io.ByteArrayInputStream(content),
                content.length, contentType));
    }

    /**
     * 便捷重载:流式写入。
     *
     * @param key         对象键
     * @param content     内容流,由调用方关闭
     * @param size        字节数,必须准确
     * @param contentType MIME 类型
     */
    default StoredObject put(String key, InputStream content, long size, String contentType)
    {
        return put(new ObjectWriteRequest(key, content, size, contentType));
    }
}
