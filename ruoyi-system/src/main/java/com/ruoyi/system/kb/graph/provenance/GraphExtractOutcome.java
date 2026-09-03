package com.ruoyi.system.kb.graph.provenance;

/**
 * 单次抽取结果显式状态（与 run.status 正交）。
 * 只有 SUCCESS / VALID_EMPTY 可进长期 LLM 缓存。
 */
public final class GraphExtractOutcome
{
    private GraphExtractOutcome()
    {
    }

    public static final String SUCCESS = "SUCCESS";
    public static final String VALID_EMPTY = "VALID_EMPTY";
    public static final String LLM_FAILED = "LLM_FAILED";
    public static final String PARSE_FAILED = "PARSE_FAILED";
    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";

    public static boolean cacheable(String outcome)
    {
        return SUCCESS.equals(outcome) || VALID_EMPTY.equals(outcome);
    }
}
