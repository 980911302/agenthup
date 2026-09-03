package com.ruoyi.system.tool;

import java.util.List;

/**
 * 工具回调可选实现的 UI 产物声明。
 *
 * <p>当本次 {@code call()} 需要给前端一块不进 LLM 历史的结构化数据
 * (如知识库引用卡片),实现本接口让外层 {@link RecordingToolCallback} 在
 * {@code tool_end} 之后发出 {@code type=ui} 事件。
 *
 * <p>不强制所有工具实现 —— 普通文本工具不实现,外层按 instanceof 判定。
 */
public interface UiArtifactAware
{
    /**
     * 最近一次 call() 产出的 UI 产物。
     *
     * @return 产物列表;无产物返回 null 或空列表
     */
    List<UiArtifact> lastArtifacts();
}
