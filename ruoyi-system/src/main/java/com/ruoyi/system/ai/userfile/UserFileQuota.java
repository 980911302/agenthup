package com.ruoyi.system.ai.userfile;

/**
 * 用户存储配额快照。
 *
 * @param usedBytes  已用字节数(按存活记录求和,秒传的每条记录各自计入 —— 用户看到几个文件
 *                   就该算几份,物理去重是实现细节,不该影响他对「我用了多少」的判断)
 * @param quotaBytes 配额上限字节数
 * @param fileCount  文件条数
 * @param maxFiles   文件条数上限
 *
 * @author ruoyi
 */
public record UserFileQuota(long usedBytes, long quotaBytes, int fileCount, int maxFiles)
{
    /** 剩余可用字节数,不为负。 */
    public long remainingBytes()
    {
        return Math.max(0, quotaBytes - usedBytes);
    }
}
