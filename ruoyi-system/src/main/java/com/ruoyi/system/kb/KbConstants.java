package com.ruoyi.system.kb;

/**
 * 知识库共享常量。
 * <p>章节面包屑分隔符必须全链路共用:分块写入、检索展示、token 预算裁剪都要按它反切。
 * 写死两处字面量,改一处就会静默错位(见 ai-kb-design-extract §2.2)。
 */
public final class KbConstants
{
    private KbConstants() {}

    /** 章节面包屑分隔符,如「第3章 技术方案 → 3.2 存储设计」 */
    public static final String HEADING_SEP = " → ";

    /** 解析器版本,重建时用于判定是否需要重新 parse */
    public static final String PARSER_VERSION = "3";

    /** 进度权重:解析 */
    public static final int PROGRESS_PARSE = 30;
    /** 进度权重:分块 */
    public static final int PROGRESS_CHUNK = 10;
    /** 进度权重:嵌入(最慢,按 chunk 线性推进) */
    public static final int PROGRESS_EMBED = 60;

    /** 嵌入批大小:一次请求多个 chunk,降低网络往返 */
    public static final int EMBED_BATCH_SIZE = 16;

    /** 默认 topK */
    public static final int DEFAULT_TOP_K = 5;

    /** 默认最低相似度 */
    public static final double DEFAULT_MIN_SCORE = 0.3;
}
