# 定时任务与计量统计 API 文档

> 适用版本:agent-java(RuoYi-Vue 改造版)
> 范围:`AiJobController`(`/ai/job`)+ `AiJobLogController`(`/ai/jobLog`)+ `AiStatController`(`/ai/stat`)。
> 配套阅读:`docs/定时任务模块.md`、`docs/计量统计模块.md`。
> 已下线:`GET /ai/jobLog/{logId}`(日志详情)与 `AiJobLogController` 详情接口。

---

## 1. 定时任务 `/ai/job`(`AiJobController`)

### 1.1 接口总览

| 方法 | 路径 | 说明 | 审计 |
|---|---|---|---|
| GET | `/list` | 分页列表 | - |
| GET | `/{jobId}` | 详情 | - |
| POST | `/` | 新增(校验 cron + 建 Quartz trigger) | `@Log INSERT` |
| PUT | `/` | 修改并重建调度(`status` 在此接口被忽略) | `@Log UPDATE` |
| PUT | `/changeStatus` | 启用/暂停 | `@Log UPDATE` |
| POST | `/run/{jobId}` | 立即执行一次 | `@Log` |
| DELETE | `/{jobIds}` | 批量删除(删 trigger) | `@Log DELETE` |
| GET | `/nextFireTimes` | cron 预览(默认 5 次) | - |

### 1.2 数据模型 `AiJob`

| 字段 | 说明 |
|---|---|
| `jobId` / `jobName` | 主键 / 任务名 |
| `agentId` | 执行时使用的智能体(装配 + 对话) |
| `prompt` | 任务指令(发给 agent 的 query) |
| `attachments` | 可选附件 |
| `triggerType` | `CRON` / `ONCE` |
| `cronExpression` / `fireTime` | cron 表达式 / 单次触发时间 |
| `timezone` / `misfirePolicy` | 时区 / 错失触发策略 |
| `sessionMode` / `sessionId` | 会话模式:`NEW` 每轮新建 / `FIXED` 固定会话 |
| `timeoutSeconds` | 超时兜底(超过则强制终态) |

### 1.3 立即执行与 cron 预览

```http
POST /ai/job/run/{jobId}                      # → 触发一次(manual- 幂等键)
GET /ai/job/nextFireTimes?cronExpression=0 0 9 * * ?&timezone=Asia/Shanghai
# → { data: ["2026-08-10 09:00:00", ...] }
```

调度模型:Quartz 与 RuoYi `sys_job` 共享 Scheduler,但 **Job 实现层完全分叉**(`AiJobDispatcher` 独立,不与 `AbstractQuartzJob` 混用)。`sessionMode=FIXED` 时复用指定会话(累计上下文),`NEW` 每轮新开会话。

---

## 2. 任务日志 `/ai/jobLog`(`AiJobLogController`)

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/list` | 日志分页列表(按 `jobId/status` 过滤) |
| DELETE | `/{logIds}` | 删除日志(不改 `ai_job` 本体) |

数据模型 `AiJobLog`:`logId / jobId / jobName / agentId / scheduledTime / fireTime / runId / sessionId / status / skipReason / retryNo`——执行即写日志,`runId` 关联 `ai_chat_run`(可在聊天页回看该轮执行)。

---

## 3. 计量统计 `/ai/stat`(`AiStatController`)

### 3.1 接口总览

| 方法 | 路径 | 参数 | 说明 |
|---|---|---|---|
| GET | `/overview` | `days=7` | 总览:调用量 / token / 费用 / 成功率 / 活跃 agent 数 |
| GET | `/by-model` | `days=7, limit=5` | 按模型维度 TOP(调用/token/费用) |
| GET | `/by-agent` | `days=7, limit=5` | 按智能体维度 TOP |
| GET | `/trend` | `days=30` | 趋势曲线(按天) |
| GET | `/cache` | `days=7` | 缓存命中率探针 |
| GET | `/runs` | `days=7` | Run 执行统计(状态分布/耗时) |
| GET | `/channels-health` | - | 渠道健康快照 |
| GET | `/mcp-health` | - | MCP 运行时健康快照 |

### 3.2 计量口径要点

- **差值记账**:`LlmCallCollector` 按"上一轮快照差值"统计 token(工具循环里上游给的是累计值,直接 sum 会指数虚高;差值变负自动重置识别非累计式上游)——见 `docs/计量统计模块.md`。
- 计量数据源:`ai_llm_call`(每次 LLM 调用,含 agentId/modelId 归因)+ `ai_llm_call_cache_tokens`(缓存命中)。
- 所有接口仅读,无写操作;前端 `stat.js` 对应 8 个函数(`getStatOverview` / `getStatByModel` / `getStatByAgent` / `getStatTrend` / `getStatCache` / `getStatRuns` / `getStatChannelsHealth` / `getStatMcpHealth`)。

---

## 4. 前端调用对照

| 文件 | 函数 |
|---|---|
| `ruoyi-ui/src/api/ai/job.js` | `listJob` / `getJob` / `addJob` / `updateJob` / `delJob` / `changeJobStatus` / `runJob` / `nextFireTimes` / `listJobLog` / `delJobLog` |
| `ruoyi-ui/src/api/ai/stat.js` | 上述 8 个统计函数 |

页面:`views/ai/job/index.vue` + `views/ai/job/log.vue`(任务列表 + 日志,日志行可跳到聊天回看)、`views/ai/stat/`(统计看板)。

---

## 附录:关键文件速查

| 关注点 | 路径 |
|---|---|
| 任务 REST | `ruoyi-admin/.../controller/ai/AiJobController.java` |
| 日志 REST | `ruoyi-admin/.../controller/ai/AiJobLogController.java` |
| 统计 REST | `ruoyi-admin/.../controller/ai/AiStatController.java` |
| 任务调度 | `ruoyi-system/.../ai/job/AiJobDispatcher.java`(用户态 Job) |
| 计量采集 | `ruoyi-system/.../ai/metering/LlmCallCollector.java`、`CacheUsageProbe.java` |
| 前端 API | `ruoyi-ui/src/api/ai/job.js`、`stat.js` |
| 表结构 | `docs/AI业务表结构.md`(ai_job / ai_job_log / ai_llm_call / ai_llm_call_cache_tokens) |
