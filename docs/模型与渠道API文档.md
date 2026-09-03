# 模型与渠道 API 文档

> 适用版本:agent-java(RuoYi-Vue 改造版)
> 范围:`AiModelController`(`/ai/model`,模型本体 + 上游导入 + 渠道供应)+ `AiChannelController`(`/ai/channel`,上游渠道)。
> 配套阅读:`docs/模型管理模块.md`、`docs/模型渠道模块.md`(设计原理)、`docs/智能体模块API文档.md`(agent 如何选模型)。
> 已下线:`/ai/channel/genCode`(编码预览)、`/ai/modelChannel` 独立控制器(绑定 API 收敛到 `/ai/model/{modelId}/supply`)。

---

## 1. 模块定位

模型与渠道是"上游 LLM 能力"的两层抽象:

```
AiModel(模型本体:语义/能力/价格档位) ──1:N── AiModelChannel(供应:权重/重试/本渠道 modelName) ──N:1── AiChannel(渠道:协议/baseUrl/apiKey)
```

- **模型(ai_model)**:`deepseek-v4-flash` 这类逻辑模型,记录上下文窗口、输出上限、能力位。
- **渠道(ai_channel)**:上游提供方(OPENAI 官方 / DeepSeek / 自建中转 / Anthropic 兼容),含 `baseUrl` 与加密存储的 `apiKey`。
- **供应(ai_model_channel)**:模型×渠道绑定,含 `weight` 权重路由、`retryCount` 重试、独立计价;运行期由 `ChatModelFactory` 按权重选渠道动态构造模型实例。

---

## 2. 渠道管理 `/ai/channel`(`AiChannelController`)

### 2.1 接口总览

| 方法 | 路径 | 说明 | 审计 |
|---|---|---|---|
| GET | `/list` | 分页列表(**apiKey 脱敏** `sk-a****xyz`) | - |
| GET | `/{channelId}` | 详情(脱敏) | - |
| POST | `/` | 新增(`channelCode` 后端自动生成 `CH + yyyyMMdd + 4位流水`;apiKey 加密入库) | `@Log INSERT` |
| PUT | `/` | 修改;成功广播 `AiChannelChangedEvent` 让三个模型工厂精准清缓存 | `@Log UPDATE` |
| DELETE | `/{channelIds}` | 批量删除(物理删除 + 级联清理绑定 + 广播事件) | `@Log DELETE` |
| POST | `/{channelId}/check` | 手动健康检查(5s 连接 / 10s 读,3xx 不算 200) | `@Log UPDATE` |

### 2.2 数据模型 `AiChannel`

| 字段 | 说明 |
|---|---|
| `channelId` / `channelName` | 主键 / 展示名 |
| `channelCode` | 程序引用编码,唯一;`CH + yyyyMMdd + 4位流水`,后端自动生成(新增表单不展示) |
| `channelType` | 协议族:`OPENAI` / `ANTHROPIC` / `GEMINI` / `OLLAMA`(等 OpenAI 兼容中转) |
| `baseUrl` | API 根地址(入库后展示脱敏) |
| `apiKey` | **AES 加密入库**,列表/详情一律脱敏 |
| `healthCheckUri` | 探活路径,默认 `/models` |
| `healthStatus` / `healthFailCount` | 健康状态机:连续 3 次失败置异常 |
| `status` | 0 正常 / 1 停用 |

### 2.3 示例:新增渠道

```http
POST /ai/channel
Content-Type: application/json

{
  "channelName": "DeepSeek 官方",
  "channelType": "OPENAI",
  "baseUrl": "https://api.deepseek.com/v1",
  "apiKey": "sk-xxxx",
  "healthCheckUri": "/models",
  "status": "0"
}
```

响应 `{ code:200 }`;列表接口返回的 `apiKey` 已是 `sk-a****xyz` 形态。

### 2.4 健康检查

`POST /ai/channel/{channelId}/check` → `{ code:200, msg:"渠道正常"/"渠道异常" }`。渠道被引用时停用/删除会触发级联:先物理删 `ai_model_channel` 绑定,再广播 `AiChannelChangedEvent`,五个工厂按 `channelId+":"` 前缀清缓存(精准失效某渠道,避免误清其他渠道),下次请求按新配置重建。

---

## 3. 模型管理 `/ai/model`(`AiModelController`)

### 3.1 接口总览

| 方法 | 路径 | 说明 | 审计 |
|---|---|---|---|
| GET | `/list` | 分页列表 | - |
| GET | `/{modelId}` | 详情 | - |
| PUT | `/` | 修改(展示名/上下文/输出上限/能力位/状态) | `@Log UPDATE` |
| DELETE | `/{modelIds}` | 批量删除 | `@Log DELETE` |
| GET | `/import/upstream?channelId=` | 查询已落库的渠道模型清单(含导入状态三态:已建/已绑/未导入) | - |
| POST | `/import` | 导入模型(不存在则创建 + 建供应;已存在仅新增供应),事务内完成 | `@Log IMPORT` |
| GET | `/{modelId}/supply` | 该模型的供应渠道列表 | - |
| POST | `/{modelId}/supply` | 添加供应(`modelId` 从路径注入,body 为绑定) | `@Log INSERT` |
| PUT | `/supply` | 修改供应(权重/重试/价格/modelName/状态) | `@Log UPDATE` |
| DELETE | `/supply/{ids}` | 删除供应(软删) | `@Log DELETE` |

### 3.2 数据模型 `AiModel`

| 字段 | 说明 |
|---|---|
| `modelId` / `modelCode` | 主键 / 编码,唯一;`MDL + yyyyMMdd + 4位流水`(导入时生成) |
| `displayName` | 展示名 |
| `modelType` | `CHAT` / `EMBEDDING` / `IMAGE` / `VIDEO` |
| `contextWindow` / `maxOutputTokens` | 上下文预算与输出上限(装配期算 inputBudget) |
| `reasoningEnabled` | 思考运行开关：`1` 时请求携带 `reasoning_effort=medium`，并记录/展示上游 reasoning；`0` 时不携带该参数，且丢弃 reasoning 内容 |
| `inputModalities` | **模型能接收哪些输入模态**,逗号分隔:`image` / `file` / `video` / `audio`,空串=纯文本。决定媒体是否进入请求,判定走 `ModelInputModalities`。编辑/导入表单只在 `modelType=CHAT` 时展示 |
| `visionEnabled` | **已废弃**,勿用于判定。由代码按 `inputModalities` 是否含 `image` 单向同步,仅为存量查询保留 |
| `sort` / `status` / `delFlag` | 排序 / 启停 / 软删 |

### 3.3 上游导入 `POST /import`

请求体 `AiModelImportDto`:

```json
{
  "channelId": 3,
  "models": [
    { "id": "deepseek-chat", "displayName": "DeepSeek V4 Flash" }
  ]
}
```

导入语义:同 `modelCode` 已存在则只新增供应(绑到该渠道);不存在则"建模型 + 建供应"同事务;`modelName` 缺省取上游 `id`。导入完成后,模型即可被智能体 `modelCode` 引用,对话时由工厂按权重选渠道。

### 3.4 供应绑定示例

```http
POST /ai/model/{modelId}/supply
Content-Type: application/json

{
  "channelId": 3,
  "modelName": "deepseek-chat",
  "weight": 80,
  "retryCount": 1,
  "inputPrice": 0.0001,
  "outputPrice": 0.0002,
  "status": "0"
}
```

> `addSupply` 内部走 `saveBinding`,软删后同 `(modelId, channelId)` 重建走"复活旧行"路径,避免撞 `uk_model_channel`(`AiModelSupplyServiceImpl.java:174`)。

---

## 4. 前端调用对照

| 文件 | 函数 |
|---|---|
| `ruoyi-ui/src/api/ai/channel.js` | `listChannel` / `getChannel` / `addChannel` / `updateChannel` / `delChannel` / `checkChannel` |
| `ruoyi-ui/src/api/ai/model.js` | `listModel` / `getModel` / `updateModel` / `delModel` / `listUpstreamModels` / `importModel` / `listModelSupply` / `addModelSupply` / `updateModelSupply` / `delModelSupply` |
| `ruoyi-ui/src/api/ai/upstreamModel.js` | 供应新增时读取渠道模型清单,以及渠道清单的增删改与同步 |

页面:`views/ai/channel/index.vue`(渠道卡片管理)、`views/ai/model/index.vue` + `views/ai/model/supply.vue`(模型列表 + 供应管理)、`views/ai/model/import.vue`(上游导入)。

> `views/ai/modelChannel/` 与 `api/ai/modelChannel.js` 已随独立控制器一并移除,渠道绑定统一在"模型管理"页内维护。

---

## 附录:关键文件速查

| 关注点 | 路径 |
|---|---|
| 渠道 REST | `ruoyi-admin/.../controller/ai/AiChannelController.java` |
| 模型 REST | `ruoyi-admin/.../controller/ai/AiModelController.java` |
| 供应服务 | `ruoyi-system/.../service/impl/AiModelSupplyServiceImpl.java` |
| 渠道服务 | `ruoyi-system/.../service/impl/AiChannelServiceImpl.java` |
| 上游探测 | `ruoyi-system/.../ai/UpstreamModelClient.java` |
| 模型工厂 | `ruoyi-system/.../ai/ChatModelFactory.java`(事件订阅清缓存) |
| 前端 API | `ruoyi-ui/src/api/ai/model.js`、`channel.js` |
| 表结构 | `docs/AI业务表结构.md`(ai_channel / ai_model / ai_model_channel) |
