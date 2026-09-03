package com.ruoyi.system.kb.graph;

/**
 * 知识图谱常量(成本与正确性开关集中在此)。
 */
public final class KbGraphConstants
{
    private KbGraphConstants() {}

    /** 单 chunk 最多实体数,防模型无限列举烧 token */
    public static final int MAX_ENTITIES_PER_CHUNK = 40;

    /** 单 chunk 最多关系数 */
    public static final int MAX_RELATIONS_PER_CHUNK = 100;

    /** 实体/关系 sourceIds 上限;超出保留最早 */
    public static final int MAX_SOURCE_IDS = 10;

    /** filePaths 上限 */
    public static final int MAX_FILE_PATHS = 10;

    /** 超出 filePaths 上限时的占位 */
    public static final String MORE_FILES_PLACEHOLDER = "更多文件";

    /**
     * 描述条数低于此且总 token 未超限时,合并阶段直接拼接不调 LLM。
     * 大部分实体只出现两三次,无此判断合并成本会和抽取同量级。
     */
    public static final int FORCE_LLM_SUMMARY_ON_MERGE = 6;

    /** 描述总 token 阈值,与条数阈值同时满足才跳过 LLM */
    public static final int SUMMARY_MAX_TOKENS = 500;

    /** 图检索默认跳数 */
    public static final int DEFAULT_HOPS = 1;

    /** 图检索邻居上限 */
    public static final int DEFAULT_NEIGHBOR_LIMIT = 30;

    /** mix 模式图路加权 */
    public static final double GRAPH_SCORE_BOOST = 1.1;

    /** 图路命中的基础分(无向量相似度时) */
    public static final double GRAPH_BASE_SCORE = 0.75;

    /** LLM 缓存类型 */
    public static final String CACHE_EXTRACT = "extract";
    public static final String CACHE_SUMMARY = "summary";

    /** 计量 session 前缀: kb-graph-&lt;docId&gt; */
    public static final String LLM_SESSION_PREFIX = "kb-graph-";
}
