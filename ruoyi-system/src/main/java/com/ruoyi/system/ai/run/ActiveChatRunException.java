package com.ruoyi.system.ai.run;

import com.ruoyi.system.domain.AiChatRun;

/** 同一会话已有活动运行；携带现有运行供客户端恢复订阅。 */
public class ActiveChatRunException extends RuntimeException
{
    private static final long serialVersionUID = 1L;

    private final AiChatRun activeRun;

    public ActiveChatRunException(AiChatRun activeRun)
    {
        super("当前会话已有正在执行的任务");
        this.activeRun = activeRun;
    }

    public AiChatRun getActiveRun()
    {
        return activeRun;
    }
}
