package com.ruoyi.system.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 应用启动时触发工具同步(MCP 连接 + ai_tool 同步 + 索引刷新)。
 *
 * <p>异步执行:提交后立即返回,不阻塞启动主线程 —— 慢 MCP(uvx 拉包、SSE 握手
 * 走到超时)不再拖慢应用启动。窗口期与竞态处理见 {@link ToolStartupSync} /
 * {@link ToolLifecycle}。
 *
 * @author ruoyi
 */
@Component
public class ToolSyncApplicationRunner implements ApplicationRunner
{
    private static final Logger log = LoggerFactory.getLogger(ToolSyncApplicationRunner.class);

    @Autowired
    private ToolStartupSync toolStartupSync;

    @Override
    public void run(ApplicationArguments args)
    {
        log.info("应用启动,提交后台任务连接 MCP server 并同步工具(不阻塞启动)");
        toolStartupSync.submitStartupSync();
    }
}
