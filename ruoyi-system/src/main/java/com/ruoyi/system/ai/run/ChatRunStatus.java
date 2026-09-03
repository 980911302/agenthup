package com.ruoyi.system.ai.run;

import java.util.Set;

/** AI 对话运行状态机。 */
public final class ChatRunStatus
{
    public static final String QUEUED = "QUEUED";
    public static final String RUNNING = "RUNNING";
    public static final String FINALIZING = "FINALIZING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";
    public static final String INTERRUPTED = "INTERRUPTED";

    private static final Set<String> TERMINAL = Set.of(SUCCEEDED, FAILED, CANCELLED, INTERRUPTED);

    private ChatRunStatus() { }

    public static boolean isTerminal(String status)
    {
        return TERMINAL.contains(status);
    }
}
