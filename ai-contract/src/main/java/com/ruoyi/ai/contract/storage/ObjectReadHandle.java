package com.ruoyi.ai.contract.storage;

import java.io.InputStream;

/**
 * 对象读取句柄。持有一个未消费的网络流,{@code close} 前不要丢弃 —— S3 客户端的连接池
 * 靠它归还连接,泄漏会让后续请求卡在取连接上。
 *
 * @author ruoyi
 */
public interface ObjectReadHandle extends AutoCloseable
{
    /** 对象元数据。 */
    StoredObject object();

    /** 内容流。 */
    InputStream stream();

    @Override
    void close();
}
