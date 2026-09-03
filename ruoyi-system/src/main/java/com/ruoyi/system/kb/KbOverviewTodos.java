package com.ruoyi.system.kb;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.ruoyi.system.domain.vo.KbWorkbenchItem;

/**
 * 知识库概览「待办」列表（纯函数，便于单测）。
 */
public final class KbOverviewTodos
{
    private KbOverviewTodos() {}

    /**
     * @param item  已 apply 健康态的工作台行
     * @param graph 图谱摘要（available/entityCount/…），可 null
     */
    public static List<Map<String, Object>> build(KbWorkbenchItem item, Map<String, Object> graph)
    {
        List<Map<String, Object>> todos = new ArrayList<>();
        if (item == null)
        {
            return todos;
        }
        int failed = nz(item.getFailedCount());
        int processing = nz(item.getProcessingCount());
        int docs = nz(item.getDocCount());
        int ready = nz(item.getReadyCount());

        if (!"0".equals(item.getStatus()))
        {
            todos.add(todo("DISABLED", "知识库已停用", "在设置中重新启用后，智能体才会引用本库",
                "open_settings", "high"));
        }
        if (failed > 0)
        {
            todos.add(todo("FAILED_DOCS", failed + " 篇文档处理失败",
                "打开内容页查看失败原因并重新处理", "open_content_failed", "high"));
        }
        if (processing > 0)
        {
            todos.add(todo("PROCESSING", processing + " 篇文档处理中",
                "处理完成后才会参与回答", "open_content", "medium"));
        }
        if (docs == 0)
        {
            todos.add(todo("EMPTY", "还没有内容", "上传文档后即可被智能体使用",
                "add_content", "high"));
        }
        if (graph != null && Boolean.FALSE.equals(graph.get("available"))
            && "1".equals(String.valueOf(graph.getOrDefault("graphEnabled", ""))))
        {
            todos.add(todo("GRAPH_DOWN", "知识图谱服务暂不可用",
                "不影响基础检索；可稍后在知识图谱页重试", "open_graph", "low"));
        }
        if (graph != null && "1".equals(String.valueOf(graph.getOrDefault("graphEnabled", "")))
            && ready > 0
            && nz((Integer) graph.get("entityCount")) == 0
            && processing == 0)
        {
            todos.add(todo("GRAPH_EMPTY", "图谱尚未形成实体",
                "文档完成后会自动抽取；也可在知识图谱页查看进度", "open_graph", "low"));
        }
        return todos;
    }

    private static Map<String, Object> todo(String code, String title, String hint,
                                            String action, String severity)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("title", title);
        m.put("hint", hint);
        m.put("action", action);
        m.put("severity", severity);
        return m;
    }

    private static int nz(Integer v)
    {
        return v == null ? 0 : v;
    }
}
