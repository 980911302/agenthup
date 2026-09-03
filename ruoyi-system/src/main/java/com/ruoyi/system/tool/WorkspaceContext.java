package com.ruoyi.system.tool;

/**
 * 当前工具调用所属会话的载体(ThreadLocal)。
 *
 * <p><b>为什么这里 ThreadLocal 是可靠的</b>:与请求线程跨 reactive 线程传递
 * (需要显式参数下传)的场景不同 -- 本类由 {@link RecordingToolCallback#record}
 * 在<b>同一个方法、同一个线程</b>内 set -> 调 delegate.call() -> finally restore,
 * 不跨任何线程边界,与工具跑在哪个调度线程上无关。
 *
 * <p>用 {@link #restore} 而非 clear:子智能体的工具调用可能嵌套在父调用的同一线程上,
 * 保存/恢复上一层的值比直接清空更安全。
 *
 * @author ruoyi
 */
public final class WorkspaceContext
{
    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();
    private static final ThreadLocal<String> WORKSPACE_HOLDER = new ThreadLocal<>();

    private WorkspaceContext()
    {
    }

    /** 绑定当前线程的会话ID,返回绑定前的旧值(供 finally 恢复) */
    public static String bind(String sessionId)
    {
        String prev = HOLDER.get();
        if (sessionId == null || sessionId.isBlank())
        {
            HOLDER.remove();
        }
        else
        {
            HOLDER.set(sessionId);
        }
        return prev;
    }

    /** 取当前线程绑定的会话ID,未绑定返回 null */
    public static String getOrNull()
    {
        return HOLDER.get();
    }

    /**
     * 当前文件工作区键。项目会话是 project-{id}，普通会话回退 sessionId。
     * 不要用它做消息、运行或权限查询，那些仍必须使用 {@link #getOrNull()}。
     */
    public static String getWorkspaceKeyOrSessionId()
    {
        String key = WORKSPACE_HOLDER.get();
        return key == null || key.isBlank() ? HOLDER.get() : key;
    }

    public static String bindWorkspaceKey(String workspaceKey)
    {
        String prev = WORKSPACE_HOLDER.get();
        if (workspaceKey == null || workspaceKey.isBlank())
        {
            WORKSPACE_HOLDER.remove();
        }
        else
        {
            WORKSPACE_HOLDER.set(workspaceKey);
        }
        return prev;
    }

    public static void restoreWorkspaceKey(String prev)
    {
        if (prev == null)
        {
            WORKSPACE_HOLDER.remove();
        }
        else
        {
            WORKSPACE_HOLDER.set(prev);
        }
    }

    /** 恢复到 bind 之前的值;prev 为 null 时清空(防内存泄漏) */
    public static void restore(String prev)
    {
        if (prev == null)
        {
            HOLDER.remove();
        }
        else
        {
            HOLDER.set(prev);
        }
    }
}
