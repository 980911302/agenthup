package com.ruoyi.system.domain.vo;

import java.util.ArrayList;
import java.util.List;

/**
 * 模型供应候选：可添加的渠道 + 被排除的渠道及原因。
 */
public class SupplyCandidateResult
{
    private List<SupplyCandidateVo> candidates = new ArrayList<>();
    private List<SupplyExcludedVo> excluded = new ArrayList<>();

    public List<SupplyCandidateVo> getCandidates()
    {
        return candidates;
    }

    public void setCandidates(List<SupplyCandidateVo> candidates)
    {
        this.candidates = candidates != null ? candidates : new ArrayList<>();
    }

    public List<SupplyExcludedVo> getExcluded()
    {
        return excluded;
    }

    public void setExcluded(List<SupplyExcludedVo> excluded)
    {
        this.excluded = excluded != null ? excluded : new ArrayList<>();
    }
}
