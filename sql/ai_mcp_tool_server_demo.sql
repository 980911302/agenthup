-- ============================================================================
-- 内置「执行型」工具 → 独立 MCP server 的接入示例行
-- 前置:tool-mcp-server 已启动(见 tool-mcp-server/README.md),本行插入后
-- DynamicMcpService 启动 connectAll 会自动拉这 8 个工具(bash/read/write/edit/
-- grep/find/ls/captureScreenshot),按 tool_code 同步进 ai_tool,tool_type=2 (MCP)。
--
-- ⚠️ 三个关键前置(缺一个就踩坑,详见 tool-mcp-server/README.md 超时矩阵):
--   1. 名称冲突:远端工具名与内置完全相同,tool_code 唯一 + Registry last-write-wins
--      会把同名字互相覆盖。因此主应用侧必须把 ruoyi.ai.tool.exec-tools-mode 配成 mcp
--      (主应用不再注册本地 bash/文件/截图),保证“一行一义”。
--   2. 长命令超时:远端 bash 上限 600s,而主应用 MCP client 请求超时默认 60s —— 须把
--      application-ai.yml 的 ruoyi.ai.tool.mcp-request-timeout-ms 提到 ≥ 600000,
--      否则 >60s 的命令响应没回来就被主应用掐断。
--   3. 不要用本行做“回连自己”的自测(mode 仍=local 时,同名的本地工具会被这行同步结果
--      覆盖或反过来,名字打架);直接 tools/call 那条通道在独立 client 上验证。
-- ============================================================================

insert into ai_mcp_server (server_name, server_code, transport, command, args, endpoint, env, status, remark)
values (
  '内置执行工具-MCP',
  'builtin-exec-server',
  'HTTP',
  null,
  null,
  -- 本地演示用 localhost;部署到沙箱/OPI 节点时改成该节点地址(如 ZeroTier 10.72.121.63:8090)
  'http://localhost:8090/mcp',
  -- 可选:把沙箱根指到持久卷(与主应用 workspace-root 语义一致)
  '{"TOOL_SERVER_WORKSPACE_ROOT":"./agent-java/ai/workspace"}',
  '0',
  '独立 tool-mcp-server 暴露 bash/read/write/edit/grep/find/ls/captureScreenshot,由 DynamicMcpService 消费'
);

-- 配套切主应用执行型工具来源为 MCP(application-ai.yml):
--   ruoyi.ai.tool.exec-tools-mode: mcp
--   ruoyi.ai.tool.mcp-request-timeout-ms: 600000
--   ruoyi.ai.tool.remote-workspace-base-url: http://10.72.121.63:8090
--      (工作区抽屉经远端 /ws 读写 OPI 上的文件;与工具调用同一 workspaceKey 按会话隔离)
-- 改完重启主应用,ai_tool 里这 8 个工具应显示 tool_type=2、mcp_server_id=上述行。