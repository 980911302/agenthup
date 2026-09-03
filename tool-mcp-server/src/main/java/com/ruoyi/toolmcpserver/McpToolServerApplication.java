package com.ruoyi.toolmcpserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.ruoyi.system.tool.AiToolProperties;

/**
 * 独立 MCP server 入口 —— 只服务内置「执行型」工具(bash/文件/截图)。
 *
 * <p><b>为什么能独立启动且不连库</b>:工具类(ShellTool/FileTools/ScreenshotToolCallback)
 * 早在单测里就是 {@code new Xxx(props)} 直接构造的,不依赖 Spring 上下文;本模块
 * 只用 {@link AiToolProperties} 一行配置,并显式排除一切数据/缓存/定时自动配置
 * (application.yml 的 {@code spring.autoconfigure.exclude}),保证零数据库零中间件。
 *
 * <p><b>为什么不扫描 {@code com.ruoyi.system}</b>:扫描会把 ruoyi-system 里数以百计的
 * {@code @Component}(Service/Mapper/…依赖库的 Bean)拉进来。这里只需要三个工具类,
 * 由 {@link BuiltinToolBeans} 手动 new 出来,名字/schema/输出文案天然与内置工具一致。
 *
 * @author ruoyi
 */
@SpringBootApplication
@EnableConfigurationProperties({AiToolProperties.class, ToolServerProperties.class})
public class McpToolServerApplication
{
    public static void main(String[] args)
    {
        SpringApplication.run(McpToolServerApplication.class, args);
    }
}