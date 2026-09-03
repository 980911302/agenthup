package com.ruoyi.system.kb.graph.community;

/** GDS 能力探测结果 */
public class GdsCapability
{
    private final boolean available;
    private final String version;
    private final String reason;

    public GdsCapability(boolean available, String version, String reason)
    {
        this.available = available;
        this.version = version;
        this.reason = reason;
    }

    public static GdsCapability ok(String version)
    {
        return new GdsCapability(true, version, null);
    }

    public static GdsCapability unavailable(String reason)
    {
        return new GdsCapability(false, null, reason);
    }

    public boolean isAvailable()
    {
        return available;
    }

    public String getVersion()
    {
        return version;
    }

    public String getReason()
    {
        return reason;
    }
}
