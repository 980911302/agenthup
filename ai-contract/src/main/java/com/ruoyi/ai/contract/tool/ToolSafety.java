package com.ruoyi.ai.contract.tool;

public record ToolSafety(RiskLevel riskLevel, boolean confirmationRequired)
{
    public enum RiskLevel { READ_ONLY, MUTATING, DESTRUCTIVE }

    public static ToolSafety readOnly()
    {
        return new ToolSafety(RiskLevel.READ_ONLY, false);
    }
}
