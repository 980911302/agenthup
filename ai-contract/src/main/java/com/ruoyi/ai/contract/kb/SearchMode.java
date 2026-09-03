package com.ruoyi.ai.contract.kb;

public enum SearchMode
{
    BASIC, LOCAL, HYBRID, GLOBAL, DRIFT, AUTO;

    public static SearchMode from(String value)
    {
        if (value == null || value.isBlank()) return BASIC;
        String normalized = value.trim().toUpperCase();
        if ("VECTOR".equals(normalized)) return BASIC;
        if ("GRAPH".equals(normalized)) return LOCAL;
        if ("MIX".equals(normalized)) return HYBRID;
        try
        {
            return valueOf(normalized);
        }
        catch (IllegalArgumentException ignored)
        {
            return BASIC;
        }
    }
}
