# AgentHub 桌面客户端

对外独立 PC Web 客户端:登录后就是干净的主对话体验(会话列表 + 消息流 + 输入框),不含管理后台的菜单/权限/计量/Trace 等管理元素。后端沿用仓库里的 AgentHub API(不改后端),账号由桌面后台开通。

## 定位

独立 PC Web 客户端,与管理后台 `ruoyi-ui/` 并列。共用同一套后端对话核心(Run + WebSocket);聊天运行时逻辑在 `src/composables/`(`useChatRun` / `useTurnBuilder`)和 `src/api/chatRpc.js`。

## 开发

开发环境与 PC 管理端相同:请求同源 `/dev-api`,由 Vite 转发到 `http://localhost:8080`。生产环境走 `/prod-api`。

```bash
cd desktop
npm install
npm run dev
```

浏览器打开 http://localhost:5175 。

## 命令

```bash
npm run build     # 生产构建
npm run preview   # 预览构建产物
```

生产环境通过 `VITE_APP_BASE_API`(默认 `/prod-api`)反代到后端,WebSocket 同样走该前缀下的 `/ws/ai/chat`。部署在 nginx 站点根路径(管理端在 `/admin/`)。

## 登录

支持两种方式:

- 验证码 + 用户名密码(`POST /login`)
- Keycloak 统一登录(OAuth,`/oauth2/login/config` → 授权 → 回跳 `oauthTicket` → `/oauth2/login/exchange` 换 JWT)

OAuth 在后端已就绪,桌面端与管理端 ruoyi-ui 均已接入。

## 功能范围

- 会话:新建 / 搜索 / 切换 / 删除,历史滚动加载(游标分页)
- 对话:流式渲染、思考/工具/子智能体步骤折叠、知识库引用折叠、图片/音频/视频产物展示、停止生成、重新生成
- 输入:Agent 切换、知识库多选、附件上传(工作区)、上下文刻度条
- 不做管理:Agent/模型/渠道/知识库 CRUD、权限、计量、Trace、定时任务
