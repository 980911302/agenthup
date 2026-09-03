# 工具生态 API 文档

> 适用版本:agent-java(RuoYi-Vue 改造版)
> 范围:`AiToolController`(`/ai/tool`)、`AiMcpServerController`(`/ai/mcpServer`)、`AiSkillController`(`/ai/skill`)。
> 配套阅读:`docs/工具生态模块.md`(Tool/MCP/Skill 三类工具的统一封装)、`docs/智能体模块API文档.md`(agent 如何挂载工具)。
> 已下线:技能/MCP 的 `genCode`(编码预览)、MCP 即时检测 `POST /ai/mcpServer/{serverCode}/check`(与 `McpHealthChecker.checkNow`)。

---

## 1. 模块定位

AI 的"手脚"分三类来源:

| 类型 | 载体 | 说明 |
|---|---|---|
| 内置/业务工具 | `ai_tool`(toolType=`1` 内置) | 后端 `@Tool` 注解 / `MethodToolCallbackProvider` 注册的工具(Shell、文件读写、业务查询等),按 `toolCode` 从 `ToolCallbackRegistry` 取回调 |
| MCP 工具 | `ai_mcp_server` + `ai_tool`(toolType=`2` MCP) | 外部 MCP Server 的动态工具,启动/保存时由 `ToolSyncService.syncProviders` 自动 upsert 到 `ai_tool` 表 |
| 技能 | `ai_skill` | 提示词模板类能力,不产生新工具;agent 绑定时装配 `loadSkill` 按需取规则 |

运行期:agent 装配时按 `toolIds` 解析工具(顺序占槽保持 KV-cache 稳定)、按 `skillIds` 挂 `loadSkill`、按 `mcpServerId` 动态同步。所有工具调用被 `RecordingToolCallback` 包一层,统一获得记账/事件/预算/人工确认(见 `docs/工具生态模块.md`)。

---

## 2. 工具管理 `/ai/tool`(`AiToolController`)

### 2.1 接口总览

| 方法 | 路径 | 说明 | 审计 |
|---|---|---|---|
| GET | `/list` | 分页列表(按 toolCode/名称/类型/分类/状态) | - |
| GET | `/{toolId}` | 详情(含 `inputSchema` / 返回说明) | - |
| PUT | `/status` | 启停工具(0 启用 / 1 停用) | `@Log UPDATE` |
| POST | `/sync/mcp` | 全量同步 MCP 工具到 `ai_tool` 表 | `@Log` |
| POST | `/sync/mcp/{mcpServerId}` | 同步单个 MCP Server 的工具 | `@Log` |

### 2.2 数据模型 `AiTool`

| 字段 | 说明 |
|---|---|
| `toolId` / `toolCode` | 主键 / 工具编码(即 `ToolDefinition.name()`),`toolCode` 是装配期取回调的键 |
| `toolName` / `description` | 展示名 / 模型可见描述 |
| `toolType` | `1` 内置(`@Tool` 注解) / `2` MCP(`SyncMcpToolCallback`);由 `ToolSyncService` 根据 `ToolCallback` 实例类型判定后写入 |
| `category` | 分类(`ToolSyncService` 默认:内置写 `内置`、MCP 写 `MCP`;可在管理界面改) |
| `beanName` / `methodName` | 内置工具反射定位 |
| `mcpServerId` / `remoteToolName` | MCP 工具归属与远端工具名 |
| `inputSchema` / `returnDesc` | JSON Schema 与返回说明(模型据此调用) |
| `sort` / `status` | 排序 / 启停 |

### 2.3 启停示例

```http
PUT /ai/tool/status
Content-Type: application/json
{ "toolId": 21, "status": "1" }
```

停用后:agent 装配时该工具被跳过(少一个工具,不报错);MCP 掉线时工具保持"配置启用、连接中断"的分离状态,`ToolCallbackRegistry.refresh()` 自愈重试(装配期最多重刷一次)。

---

## 3. MCP Server 管理 `/ai/mcpServer`(`AiMcpServerController`)

### 3.1 接口总览

| 方法 | 路径 | 说明 | 审计 |
|---|---|---|---|
| GET | `/list` | 分页列表 | - |
| GET | `/{mcpServerId}` | 详情(env 密文解密返回) | - |
| POST | `/` | 新增;**保存后自动连接 + 同步工具**(status=0 时),失败不影响保存 | `@Log INSERT` |
| PUT | `/` | 修改;**自动重连 + 重新同步** | `@Log UPDATE` |
| DELETE | `/{mcpServerIds}` | 批量删除(软删 + 断开运行时连接 + 物理删工具记录) | `@Log DELETE` |
| POST | `/{mcpServerId}/reconnect` | 手动重连(返回 `{bean, synced}`) | `@Log UPDATE` |
| GET | `/runtime-status` | 全部 server 的运行时连接快照(与配置 status 区分) | - |

### 3.2 数据模型 `AiMcpServer`

| 字段 | 说明 |
|---|---|
| `serverId` / `serverName` / `serverCode` | 主键 / 名称 / 编码(`MCP + yyyyMMdd + 4位流水`,后端自动生成) |
| `transport` | `STDIO` / `SSE` / `HTTP` |
| `command` / `args` | STDIO 模式:启动命令与参数 |
| `endpoint` | SSE / HTTP 模式:服务地址 |
| `env` | 环境变量,AES 加密入库,详情解密返回 |
| `healthStatus` / `healthCheckTime` | 运行时健康与最近探测时间 |
| `status` / `delFlag` | 启停 / 软删 |

### 3.3 运行时状态 vs 配置状态

`GET /ai/mcpServer/runtime-status` 返回 `List<{serverCode, initialized, connected, lastError, reconnectCount, ...}>`——列表里的 `status` 是"启用/停用"的**配置意图**,这里是"此刻连没连上"的**事实**,两者不一致正是"第一次调用 MCP 工具必超时"的根源(见 `AiMcpServerController.java` 注释)。前端卡片按此显示链接状态色。

### 3.4 保存即连接

```http
POST /ai/mcpServer
Content-Type: application/json
{
  "serverName": "文件系统 MCP",
  "transport": "STDIO",
  "command": "npx",
  "args": "-y @modelcontextprotocol/server-filesystem ./data",
  "status": "0"
}
```

新增后自动 `dynamicMcpService.connect` + `toolSyncService.syncMcp(serverId)`;连接失败不阻断保存(配置意图先入库,运行时资源是衍生品)。

---

## 4. 技能管理 `/ai/skill`(`AiSkillController`)

### 4.1 接口总览

| 方法 | 路径 | 说明 | 审计 |
|---|---|---|---|
| GET | `/list` | 分页列表 | - |
| GET | `/{skillId}` | 详情(含完整 `promptTemplate`) | - |
| POST | `/` | 新增(`skillCode` 后端自动生成 `SKL + yyyyMMdd + 4位流水`) | `@Log INSERT` |
| PUT | `/` | 修改 | `@Log UPDATE` |
| DELETE | `/{skillIds}` | 批量删除(同时清理 `ai_agent_skill` 绑定) | `@Log DELETE` |

### 4.2 数据模型 `AiSkill`

| 字段 | 说明 |
|---|---|
| `skillId` / `skillCode` | 主键 / 编码,唯一 |
| `skillName` / `category` | 名称 / 分类 |
| `description` | 适用场景一句话(装配期进系统提示词,省 token) |
| `promptTemplate` | 详细操作规则(1~2K token,**不常驻**提示词,由 `loadSkill` 按需取回) |
| `sort` / `status` | 排序 / 启停 |

---

## 5. 前端调用对照

| 文件 | 函数 |
|---|---|
| `ruoyi-ui/src/api/ai/tool.js` | `listTool` / `getTool` / `changeToolStatus` / `syncMcpAllTools`(全量)/ `syncMcpTools(serverId)`(单 server) |
| `ruoyi-ui/src/api/ai/mcpServer.js` | `listMcpServer` / `getMcpServer` / `addMcpServer` / `updateMcpServer` / `delMcpServer` / `reconnectMcpServer` / `getMcpRuntimeStatus` |
| `ruoyi-ui/src/api/ai/skill.js` | `listSkill` / `getSkill` / `addSkill` / `updateSkill` / `delSkill` |

页面:`views/ai/tool/`、`views/ai/mcpServer/`、`views/ai/skill/`。

---

## 附录:关键文件速查

| 关注点 | 路径 |
|---|---|
| 工具 REST | `ruoyi-admin/.../controller/ai/AiToolController.java` |
| MCP REST | `ruoyi-admin/.../controller/ai/AiMcpServerController.java` |
| 技能 REST | `ruoyi-admin/.../controller/ai/AiSkillController.java` |
| 工具注册表 | `ruoyi-system/.../tool/ToolCallbackRegistry.java`(按 toolCode 取回调) |
| 工具同步 | `ruoyi-system/.../tool/ToolSyncService.java` |
| MCP 建连 | `ruoyi-system/.../tool/DynamicMcpService.java` |
| 连接保活 | `ruoyi-system/.../tool/McpHealthChecker.java`(`start/refreshAll/stop/snapshot`) |
| 工具装饰器 | `ruoyi-system/.../tool/RecordingToolCallback.java` |
| 前端 API | `ruoyi-ui/src/api/ai/tool.js`、`mcpServer.js`、`skill.js` |
| 表结构 | `docs/AI业务表结构.md`(ai_tool / ai_mcp_server / ai_skill;`ai_tool_policy.sql` 是给 `ai_tool` 加策略列的 ALTER,不是独立表)|
