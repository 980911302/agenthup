package com.ruoyi.system.kb.search;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Global Search 访问/剪枝/采用轨迹（调试与验收）。
 */
public class GlobalSearchTrace
{
    private String graphVersion;
    private String degradeReason;
    private final Set<String> visited = new LinkedHashSet<>();
    private final Set<String> pruned = new LinkedHashSet<>();
    private final Set<String> selected = new LinkedHashSet<>();
    private final List<String> notes = new ArrayList<>();

    public String getGraphVersion() { return graphVersion; }
    public void setGraphVersion(String graphVersion) { this.graphVersion = graphVersion; }
    public String getDegradeReason() { return degradeReason; }
    public void setDegradeReason(String degradeReason) { this.degradeReason = degradeReason; }
    public Set<String> getVisited() { return visited; }
    public Set<String> getPruned() { return pruned; }
    public Set<String> getSelected() { return selected; }
    public List<String> getNotes() { return notes; }

    public void visit(int level, long communityId)
    {
        visited.add(key(level, communityId));
    }

    public void prune(int level, long communityId, String reason)
    {
        pruned.add(key(level, communityId));
        if (reason != null)
        {
            notes.add("prune " + key(level, communityId) + ": " + reason);
        }
    }

    public void select(int level, long communityId)
    {
        selected.add(key(level, communityId));
    }

    public static String key(int level, long communityId)
    {
        return level + "|" + communityId;
    }

    /** 紧凑 JSON，便于挂到 hit.debugTrace */
    public String toJson()
    {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"graphVersion\":").append(quote(graphVersion)).append(',');
        sb.append("\"degradeReason\":").append(quote(degradeReason)).append(',');
        sb.append("\"visited\":").append(setJson(visited)).append(',');
        sb.append("\"pruned\":").append(setJson(pruned)).append(',');
        sb.append("\"selected\":").append(setJson(selected));
        sb.append('}');
        return sb.toString();
    }

    private static String setJson(Set<String> s)
    {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String x : s)
        {
            if (!first)
            {
                sb.append(',');
            }
            first = false;
            sb.append(quote(x));
        }
        sb.append(']');
        return sb.toString();
    }

    private static String quote(String s)
    {
        if (s == null)
        {
            return "null";
        }
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
