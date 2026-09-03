package com.ruoyi.system.tool;

/**
 * 当前工具调用操作者身份的载体(ThreadLocal)。
 *
 * <p>与 {@link WorkspaceContext} 同理:工具回调跑在 reactive 线程,
 * {@code SecurityContextHolder}(默认 MODE_THREADLOCAL)在请求线程 set、
 * reactive 线程 get 不到。本类由 {@link RecordingToolCallback#record} 在
 * <b>同一个方法、同一个线程</b>内 set -> 调 delegate.call() -> finally restore,
 * 不跨任何线程边界,因此可靠。
 *
 * <p>操作者身份在请求线程(AiChatController.stream)由 AgentContextFactory 捕获,
 * 显式传入 RecordingToolCallback 构造器,工具执行时经 bind/restore 暴露给 @Tool 方法。
 *
 * <p>典型场景:AgentMetaTools 的 updateAgent/deleteAgent 需校验当前用户是否有权
 * 修改/删除目标智能体(超管放行,否则只能操作自己 createBy 的)。
 *
 * @author ruoyi
 */
public final class OperatorContext
{
    /** 操作者快照:用户名 + userId + 是否超管,一次捕获全程只读 */
    private record Operator(String username, Long userId, boolean admin) {}

    private static final ThreadLocal<Operator> HOLDER = new ThreadLocal<>();

    private OperatorContext()
    {
    }

    /** 绑定当前线程的操作者,返回绑定前的旧值(供 finally 恢复) */
    public static String bind(String username, Long userId, boolean admin)
    {
        Operator prev = HOLDER.get();
        if (username == null || username.isBlank())
        {
            HOLDER.remove();
        }
        else
        {
            HOLDER.set(new Operator(username, userId, admin));
        }
        return prev != null ? prev.username() : null;
    }

    /** 取当前线程的操作者用户名,未绑定返回 null */
    public static String getUsername()
    {
        Operator op = HOLDER.get();
        return op != null ? op.username() : null;
    }

    /** 取当前线程的操作者 userId,未绑定返回 null */
    public static Long getUserId()
    {
        Operator op = HOLDER.get();
        return op != null ? op.userId() : null;
    }

    /** 当前操作者是否超级管理员(userId=1),未绑定返回 false */
    public static boolean isAdmin()
    {
        Operator op = HOLDER.get();
        return op != null && op.admin();
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
            // restore 只需恢复 username 维度足够,admin 判断在嵌套场景无实际意义
            HOLDER.set(new Operator(prev, null, false));
        }
    }
}
