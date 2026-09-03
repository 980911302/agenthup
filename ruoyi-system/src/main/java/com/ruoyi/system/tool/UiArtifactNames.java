package com.ruoyi.system.tool;

import java.util.Map;

/**
 * UI 产物登记表。新增产物只加一行规格,禁止未登记 name 发出。
 */
public final class UiArtifactNames
{
    /** 知识库检索引用,挂到本轮回答下方的引用折叠 */
    public static final String KB_REFERENCES = "kb.references";

    /** 本轮实时 token 用量(过程量,不落库) */
    public static final String RUN_TOKEN_USAGE = "run.tokenUsage";

    /** 本轮 Agent 对会话工作区造成的文件变化 */
    public static final String WORKSPACE_CHANGES = "workspace.changes";

    public enum MergePolicy
    {
        /** 同一工具步骤同名只保留最后一次 */
        REPLACE,
        /** 按 eventId 追加 */
        APPEND,
        /** 按 payload 内主键做局部更新,前端负责合并 */
        PATCH,
        /** 写入侧归并:同 messageId + name 合并为一行,由后端去重与体积控制 */
        MERGE
    }

    public enum Persistence
    {
        /** 不落库:实时过程量,最终值另有权威来源 */
        NONE,
        /** 会话级落库,随会话存活,删会话时清理 */
        SESSION
    }

    public enum Scope
    {
        /** 挂在某一轮上,前端按 messageId 归位 */
        RUN,
        /** 整个会话级,不绑定具体某轮 */
        SESSION,
        /** 全员广播 —— 本期仅占位,发射时拒绝 */
        GLOBAL
    }

    /**
     * @param name            产物名
     * @param schemaVersion   当前载荷版本
     * @param maxPayloadChars 序列化后载荷上限
     * @param mergePolicy     同名合并策略
     * @param persistence     是否落独立表
     * @param scope           投递范围
     * @param minIntervalMs   发射节流,0 表示不节流
     */
    public record Spec(String name, int schemaVersion, int maxPayloadChars,
                       MergePolicy mergePolicy, Persistence persistence,
                       Scope scope, int minIntervalMs)
    {
    }

    private static final Map<String, Spec> SPECS = Map.of(
            KB_REFERENCES, new Spec(KB_REFERENCES, 2, 16 * 1024,
                    MergePolicy.MERGE, Persistence.SESSION, Scope.RUN, 0),
            RUN_TOKEN_USAGE, new Spec(RUN_TOKEN_USAGE, 1, 2 * 1024,
                    MergePolicy.REPLACE, Persistence.NONE, Scope.RUN, 500),
            WORKSPACE_CHANGES, new Spec(WORKSPACE_CHANGES, 1, 64 * 1024,
                    MergePolicy.APPEND, Persistence.SESSION, Scope.RUN, 0)
    );

    private UiArtifactNames() {}

    public static boolean isKnown(String name)
    {
        return spec(name) != null;
    }

    public static Spec spec(String name)
    {
        return name == null ? null : SPECS.get(name);
    }
}
