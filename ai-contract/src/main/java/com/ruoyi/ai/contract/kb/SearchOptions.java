package com.ruoyi.ai.contract.kb;

import java.util.Map;

public record SearchOptions(SearchMode mode, int topK, double minScore,
                            boolean includeDebugTrace, Map<String, Object> extensions)
{
    public SearchOptions
    {
        mode = mode == null ? SearchMode.BASIC : mode;
        topK = topK <= 0 ? 10 : topK;
        extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
    }
}
