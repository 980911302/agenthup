package com.ruoyi.ai.contract.kb;

import java.util.List;
import java.util.Map;

public record SearchResponse(List<SearchHit> hits, SearchMode effectiveMode,
                             List<String> degradations, Map<String, Object> trace)
{
    public SearchResponse
    {
        hits = hits == null ? List.of() : List.copyOf(hits);
        effectiveMode = effectiveMode == null ? SearchMode.BASIC : effectiveMode;
        degradations = degradations == null ? List.of() : List.copyOf(degradations);
        trace = trace == null ? Map.of() : Map.copyOf(trace);
    }
}
