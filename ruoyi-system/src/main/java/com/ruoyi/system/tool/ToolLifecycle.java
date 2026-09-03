package com.ruoyi.system.tool;

import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

/**
 * 工具/MCP 生命周期协调:共享锁 + 启动同步状态旗标。
 *
 * <p>启动同步异步化后,「连/注销 MCP、upsert ai_tool、刷索引」这组动作不再由启动
 * 主线程串行独占,会与运行时操作交错:管理端增删改/重连 MCP、手动同步按钮、保活
 * 线程自动重连。它们必须被同一把锁串行化,否则会出现两个线程同时
 * registerSingleton/destroySingleton 同一个 provider Bean、或一边删工具一边同步的竞态。
 *
 * <ul>
 *   <li>持锁方:启动异步同步、{@link ToolSyncService} 各同步/删除入口、
 *       {@link DynamicMcpService} 的 connect/reconnect/disconnect</li>
 *   <li>避让方:{@link McpHealthChecker} 在启动未完成或锁被占用时跳过校准,
 *       避免把连接中的 server 误标异常、自动重连与启动流程打架</li>
 * </ul>
 *
 * <p>用 {@link ReentrantLock}:connectAll -> connect、reconnect -> unregister/connect
 * 都是同线程嵌套调用,锁必须可重入。
 *
 * @author ruoyi
 */
@Component
public class ToolLifecycle
{
    private final ReentrantLock lock = new ReentrantLock();

    /** 异步启动同步是否进行中(从提交到全部完成) */
    private volatile boolean startupSyncRunning = false;

    public ReentrantLock lock()
    {
        return lock;
    }

    public boolean isStartupSyncRunning()
    {
        return startupSyncRunning;
    }

    public void setStartupSyncRunning(boolean running)
    {
        this.startupSyncRunning = running;
    }
}
