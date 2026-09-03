package com.ruoyi.system.tool;

/**
 * 工具本次调用声明的、只给前端的 UI 产物。
 *
 * <p>由 {@link UiArtifactAware} 在 {@code call()} 后给出,经 {@link RecordingToolCallback}
 * 发成 {@code type=ui} 事件。不进 LLM 上下文。
 *
 * @param name          登记过的产物名,见 {@link UiArtifactNames}
 * @param schemaVersion 载荷版本,从 1 起
 * @param payload       按 name 约定的 JSON 可序列化对象
 */
public record UiArtifact(String name, int schemaVersion, Object payload)
{
    public static UiArtifact kbReferences(Object payload)
    {
        UiArtifactNames.Spec spec = UiArtifactNames.spec(UiArtifactNames.KB_REFERENCES);
        return new UiArtifact(spec.name(), spec.schemaVersion(), payload);
    }

    public static UiArtifact workspaceChanges(Object payload)
    {
        UiArtifactNames.Spec spec = UiArtifactNames.spec(UiArtifactNames.WORKSPACE_CHANGES);
        return new UiArtifact(spec.name(), spec.schemaVersion(), payload);
    }
}
