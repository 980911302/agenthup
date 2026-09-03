# 移动端「对话之书」设计

> 日期：2026-08-16  
> 状态：已下线（2026-09-01 从仓库删除 `mobile/`，本文仅作历史设计稿）

## 一句话

对外独立 Web 移动端：后台开账号、用户登录后以翻页书的方式聊天，可选智能体与知识库，并管理知识库、查看当前会话工作区。桌面 `ruoyi-ui` 继续负责配置。

## 范围

**做**

- 独立工程 `mobile/`（Vue 3 + Vite + Pinia + Vue Router）
- 登录（用户名/密码 + 验证码；无注册）
- 对话（主体验）：一本书 = 一段会话，一页 = 一轮问答；发送翻页；左右滑翻历史；书签跳页
- 输入条：选智能体、多选知识库、上传附件、发送/停止
- 知识库次级页：列表、进库、文档列表、上传、新建库（无权限则提示）
- 工作区：挂在当前会话上的半屏抽屉（目录、预览、下载）

**不做**

- 注册、微信登录
- 智能体/渠道/MCP/Skill/计量/定时任务/图谱/引擎/ACL
- Trace、上下文刻度、右侧圆点导航
- 把 `ruoyi-ui` 做成响应式
- uni-app / 原生壳

## 信息架构

```
登录
  └─ 对话（默认）          知识库
       书页 + 输入条         列表 → 详情（文档/上传）
       上拉书架 = 会话列表
       工作区抽屉挂当前书
```

工作区按 `sessionId` 隔离，不是第三 Tab。

## 后端契约（复用，不改）

| 能力 | 接口 |
|------|------|
| 登录 | `POST /login`、`GET /captchaImage`、`GET /getInfo`、`POST /logout` |
| 跑一轮 | `POST /ai/chat/run` `{sessionId,agentId,message,attachments,kbIds,clientRequestId}` |
| 实时 | `POST /ai/chat/ws-ticket` + `WS /ws/ai/chat` |
| 会话 | `GET /ai/chat/session/list`、timeline、DELETE、`PUT .../knowledge-bases` |
| 智能体 | `GET /ai/agent/listAll` |
| 知识库 | `GET /ai/kb/list`、`POST /ai/kb`、文档 list/upload |
| 工作区 | `/ai/chat/workspace/{sid}/tree|file|upload|download` |

`sessionId` 仍由客户端生成 UUIDv4。鉴权 JWT，`Authorization: Bearer`。

## 组件分层

1. `components/ui/` 基础件（按钮、输入、Sheet、空态、底栏、Toast）
2. `components/chat|kb|workspace/` 领域件，不认路由
3. `views/` 只拼组件、接 store / composable

翻页壳来自 `翻页对话交互`：`BookView` / `PageItem` / `QuickNav` / `Bookshelf` + GSAP。性能约束保留：静止态纯 2D、阴影只动 opacity、无 backdrop-filter。

一页结构：问 → 过程（默认折）→ 答（流式纯文本，完成后 Markdown）→ 引用/附件（可折）。

## 视觉

暮色底、纸页、宋体、金书脊。知识库/登录同一套纸墨，不翻页。
