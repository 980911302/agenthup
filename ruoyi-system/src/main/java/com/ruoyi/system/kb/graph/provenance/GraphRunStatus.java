package com.ruoyi.system.kb.graph.provenance;

/**
 * kb_graph_run.status
 */
public final class GraphRunStatus
{
    private GraphRunStatus()
    {
    }

    public static final String PENDING = "PENDING";
    public static final String RUNNING = "RUNNING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";
    /** 被更新的 generation 抢占 */
    public static final String SUPERSEDED = "SUPERSEDED";
}
