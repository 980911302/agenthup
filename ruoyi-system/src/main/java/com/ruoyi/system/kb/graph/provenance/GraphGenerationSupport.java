package com.ruoyi.system.kb.graph.provenance;

/**
 * generation / active-run 判定（纯函数，供流水线与单测共用）。
 */
public final class GraphGenerationSupport
{
    private GraphGenerationSupport()
    {
    }

    /** 在现有代数上 +1；null/负数视为 0。 */
    public static long nextGeneration(Long current)
    {
        long base = current == null || current < 0 ? 0L : current;
        return base + 1L;
    }

    /**
     * 仅当 run 仍是文档当前 active 且 generation 匹配时允许提交。
     */
    public static boolean canCommit(Long activeRunId, Long activeGeneration,
        Long runId, Long runGeneration)
    {
        if (runId == null || runGeneration == null)
        {
            return false;
        }
        if (activeRunId == null || activeGeneration == null)
        {
            return false;
        }
        return runId.equals(activeRunId) && runGeneration.equals(activeGeneration);
    }

    /**
     * 旧 generation 禁止覆盖：候选 generation 必须严格大于当前生效代数。
     */
    public static boolean isStaleGeneration(Long activeGeneration, Long candidateGeneration)
    {
        if (candidateGeneration == null)
        {
            return true;
        }
        long active = activeGeneration == null ? 0L : activeGeneration;
        return candidateGeneration <= active;
    }
}
