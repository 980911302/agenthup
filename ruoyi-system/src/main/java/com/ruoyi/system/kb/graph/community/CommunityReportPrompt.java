package com.ruoyi.system.kb.graph.community;

/**
 * 社区报告 prompt 模板与版本（KB-GR-09）。
 */
public final class CommunityReportPrompt
{
    public static final String PROMPT_VERSION = "p1";

    public static final String SYSTEM = """
        你是面向中文企业知识库的图谱社区分析师。根据给定的社区实体、关系、子社区摘要与原文证据，生成结构化中文社区报告。
        要求：
        1. 只依据输入事实，不要编造出处或外推。
        2. 输出严格 JSON（不要 markdown 代码块），字段：
           title, summary, findings(数组，每项含 claim/importance), key_entities(数组), risks(数组), sources_used(chunk id 数组)
        3. title、summary、findings.claim、risks、key_entities 均使用简洁中文；专有名词可保留英文。
        4. summary 不超过 200 字；findings 最多 8 条，按重要性从高到低。
        5. sources_used 只能使用输入中出现的 chunk id。
        """;

    private CommunityReportPrompt() {}

    public static String buildUserMessage(String communityLabel, String entitiesBlock,
        String relationsBlock, String childReportsBlock, String evidenceBlock)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("社区: ").append(communityLabel).append("\n\n");
        sb.append("## 实体\n").append(nullToEmpty(entitiesBlock)).append("\n\n");
        sb.append("## 关系\n").append(nullToEmpty(relationsBlock)).append("\n\n");
        if (childReportsBlock != null && !childReportsBlock.isBlank())
        {
            sb.append("## 子社区报告\n").append(childReportsBlock).append("\n\n");
        }
        sb.append("## 原文证据(chunkId|text)\n").append(nullToEmpty(evidenceBlock)).append("\n");
        return sb.toString();
    }

    private static String nullToEmpty(String s)
    {
        return s == null ? "" : s;
    }
}
