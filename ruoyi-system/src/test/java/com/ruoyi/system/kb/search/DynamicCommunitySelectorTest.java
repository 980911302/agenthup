package com.ruoyi.system.kb.search;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.ruoyi.system.domain.KbGraphCommunity;
import com.ruoyi.system.domain.KbGraphCommunityReport;
import com.ruoyi.system.kb.search.DynamicCommunitySelector.Options;
import com.ruoyi.system.kb.search.DynamicCommunitySelector.Result;

class DynamicCommunitySelectorTest
{
    @Test
    void prunesUnrelatedRootAndSelectsRelevantLeaf()
    {
        // L1 roots: finance, hr
        // L0 children under finance: payroll, tax
        List<KbGraphCommunity> communities = new ArrayList<>();
        communities.add(comm(1, 100L, null)); // finance root
        communities.add(comm(1, 200L, null)); // hr root
        communities.add(comm(0, 101L, 100L)); // payroll
        communities.add(comm(0, 102L, 100L)); // tax
        communities.add(comm(0, 201L, 200L)); // leave

        List<KbGraphCommunityReport> reports = new ArrayList<>();
        reports.add(report(1, 100L, "财务总览", "财务与预算相关"));
        reports.add(report(1, 200L, "人事总览", "招聘与请假"));
        reports.add(report(0, 101L, "薪酬", "员工薪酬与奖金计算"));
        reports.add(report(0, 102L, "税务", "企业所得税申报"));
        reports.add(report(0, 201L, "请假", "年假病假流程"));

        Options opt = new Options();
        opt.minScore = 0.2;
        opt.maxSelected = 5;

        Result r = DynamicCommunitySelector.select(
            communities, reports, "员工薪酬奖金怎么算", Map.of(), opt);

        assertFalse(r.selected.isEmpty());
        // 不应把全部 5 份报告都塞入
        assertTrue(r.selected.size() < reports.size(),
            "must not dump all reports, got " + r.selected.size());
        // 应访问过节点并有剪枝或选择轨迹
        assertFalse(r.trace.getVisited().isEmpty());
        // 相关侧应被选中（薪酬/财务）
        boolean hasPayrollOrFinance = r.selected.stream().anyMatch(s ->
            s.report.getCommunityId() == 101L || s.report.getCommunityId() == 100L);
        assertTrue(hasPayrollOrFinance, "expected payroll/finance selected: " + r.trace.toJson());
        // 人事侧应被剪枝（或至少未全部选中 leave）
        boolean onlyLeave = r.selected.size() == 1
            && r.selected.get(0).report.getCommunityId() == 201L;
        assertFalse(onlyLeave);
    }

    @Test
    void neverSelectsAllWhenManyReports()
    {
        List<KbGraphCommunity> communities = new ArrayList<>();
        List<KbGraphCommunityReport> reports = new ArrayList<>();
        for (long i = 1; i <= 20; i++)
        {
            communities.add(comm(0, i, null));
            reports.add(report(0, i, "主题" + i, "内容段落 " + i + " 普通描述"));
        }
        // 其中几条与 query 相关
        reports.get(2).setSummary("知识图谱 GraphRAG 社区报告检索");
        reports.get(2).setTitle("GraphRAG");
        reports.get(7).setSummary("向量检索与混合检索");

        Options opt = new Options();
        opt.minScore = 0.15;
        opt.maxSelected = 6;

        Result r = DynamicCommunitySelector.select(
            communities, reports, "GraphRAG 社区检索", Map.of(), opt);

        assertTrue(r.selected.size() <= opt.maxSelected);
        assertTrue(r.selected.size() < 20);
        assertTrue(r.selected.stream().anyMatch(s -> s.report.getCommunityId() == 3L)
            || r.trace.getSelected().stream().anyMatch(k -> k.contains("|3")));
    }

    @Test
    void emptyReportsDegradesCleanly()
    {
        Result r = DynamicCommunitySelector.select(
            List.of(), List.of(), "anything", Map.of(), new Options());
        assertTrue(r.selected.isEmpty());
        assertTrue("no_reports".equals(r.trace.getDegradeReason()));
    }

    private static KbGraphCommunity comm(int level, long id, Long parent)
    {
        KbGraphCommunity c = new KbGraphCommunity();
        c.setLevel(level);
        c.setCommunityId(id);
        c.setParentCommunityId(parent);
        c.setKbId(1L);
        c.setGraphVersion("gv");
        return c;
    }

    private static KbGraphCommunityReport report(int level, long id, String title, String summary)
    {
        KbGraphCommunityReport r = new KbGraphCommunityReport();
        r.setReportId(id * 10);
        r.setLevel(level);
        r.setCommunityId(id);
        r.setTitle(title);
        r.setSummary(summary);
        r.setStatus("READY");
        r.setKbId(1L);
        r.setGraphVersion("gv");
        return r;
    }
}
