package com.ruoyi.system.kb;

/**
 * 知识库文档处理状态机(第一期粗粒度)。
 * <p>更细的步骤写在 {@code kb_document.parse_step}(如 EMBEDDING_BATCH_3/12)。
 */
public enum KbDocStatus
{
    /** 已上传,等待处理 */
    PENDING,
    /** 解析中 */
    PARSING,
    /** 分块中 */
    CHUNKING,
    /** 向量化中 */
    EMBEDDING,
    /** 处理完成 */
    COMPLETED,
    /** 失败 */
    FAILED;

    public boolean isTerminal()
    {
        return this == COMPLETED || this == FAILED;
    }
}
