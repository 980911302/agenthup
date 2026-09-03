package com.ruoyi.system.ai.context;

/**
 * 上下文占用的一个分类分片(§3)。
 *
 * <p><b>为什么用通用 record 而不是给每个分类开字段</b>:前端按数组顺序渲染、按下标取色,
 * 不认识具体 key。将来新增分类(如「前情提要」「附件」)只需在
 * {@link ContextUsageAnalyzer} 里多 add 一条,前端零改动。
 *
 * @param key         稳定标识(前端不得据此做分支,仅用于调试与埋点)
 * @param label       中文展示名
 * @param tokens      该分类的 token 数
 * @param compactable 是否可被上下文压缩回收(只有消息类为 true,详见 §3 约定三)
 */
public record ContextSegment(String key, String label, long tokens, boolean compactable)
{
}
