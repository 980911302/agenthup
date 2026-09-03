# 移动端对话之书 Implementation Plan

> **落地状态(2026-08-25)**:`mobile/` 工程曾按本计划落地。
> **下线(2026-09-01)**:书本式移动端已从仓库删除,不要按本计划再开工。
>

> **For agentic workers:** 本计划在同一会话内联执行（用户已要求直接实现）。

**Goal:** 在仓库新增可独立运行的 `mobile/` Web 移动端：登录 + 翻页对话 + 知识库 + 会话工作区，复用现有 AgentHub API。

**Architecture:** 新 Vite/Vue3 应用。翻页 UI 迁自 `翻页对话交互`；Run/WS 迁自 `ruoyi-ui` 的 `useChatRun` + `chatRpc`；页面只做编排。

**Tech Stack:** Vue 3.5 / Vue Router 4 / Pinia / Vite 5 / GSAP / axios / marked / DOMPurify / Vitest

## Global Constraints

- 不改后端表结构与 Run 协议
- 不做注册
- 聊天必须可选智能体与会话级知识库
- 工作区挂当前会话，不是第三 Tab
- 翻页静止态禁止常驻 3D / backdrop-filter
- 全程中文文案

---

### Task 1: 工程脚手架 + 投影纯函数

**Files:** `mobile/package.json`、`vite.config.js`、`src/chat/turnsToPages.js` 及测试

- [x] turnsToPages 单测 + 实现
- [x] Vite 工程可 `npm run dev` / `npm test` / `npm run build`

### Task 2: 鉴权与请求层

**Files:** `src/api/request.js`、`auth.js`、`stores/auth.js`、`views/LoginView.vue`

- [x] JWT 登录、验证码、401 回登录

### Task 3: 翻页壳 + 书/会话

**Files:** `components/chat/*`、`stores/book.js`

- [x] 迁入 BookView/PageItem/QuickNav/Bookshelf
- [x] 书架 = 会话列表 API

### Task 4: Run 引擎接入

**Files:** `composables/useChatRun.js`、`api/chatRpc.js`、`views/ChatView.vue`、`Composer.vue`

- [x] 发送翻页并走 `/ai/chat/run` + WS
- [x] 智能体 / 知识库 / 附件

### Task 5: 知识库 + 工作区

**Files:** `views/KbView.vue`、`KbDetailView.vue`、`WorkspaceSheet.vue`

- [x] 列表/详情/上传
- [x] 当前会话工作区抽屉

### Task 6: 验证

- [x] unit test + production build
