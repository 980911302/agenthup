# AgentHub 浏览器插件

Chrome Manifest V3 侧边栏。主界面只做聊天：历史会话和工作区是左右抽屉。

WebSocket 只活在侧边栏页面里，不放进 service worker（MV3 空闲约 30 秒会杀后台）。关掉侧边栏会断流，重开后按 `afterSeq` 回放续上。

## 开发

1. 后端 `8080` 已启动
2. 存量库执行 `sql/ai_chat_session_client.sql`（若已执行过旧版，再跑 `sql/ai_client_type_browser_ext.sql`）
3. `application-ai.yml` 打开渠道工具：`ai.chat.tool.channel.enabled: true`
4. `extension/.env.development` 里 `VITE_APP_BASE_API` 指向后端绝对地址（默认 `http://localhost:8080`）
5. 安装并构建：

```bash
cd extension
npm install
npm run build
```

6. Chrome → 扩展程序 → 加载已解压的扩展程序 → 选 `extension/dist`

开发期也可用 `npm run dev`（crxjs HMR），加载目录以终端提示为准。

## 登录

侧边栏支持账号密码，以及「统一登录」：会新开标签走 `/oauth2/login/extension/config`，授权完成后后台页带 `oauthTicket`，service worker 刮票并关掉标签，侧边栏兑换 JWT 写入 `chrome.storage.local`。

## 浏览器工具

会话 `declare` 的 `clientType` 是 `browser_ext`。工具在侧边栏执行,结果经 `chat.tool.result` 回传(或 REST `POST /ai/chat/run/{runId}/tool-result`);服务端断线补发按 `callId` 幂等,执行过的调用原样重发结果、不重跑。共 15 个,以 `src/tools/browserTools.js` 的注册为准:

读取:`getPageContent` / `listTabs` / `readTabs` / `screenshotTab`(截图上传个人文件,只回传 `mediaFileId`)/ `readPage` / `findInPage`

操作(按确认档位弹确认):`click` / `fillInput` / `navigate` / `openTabs` / `closeTabs` / `batch`(多步合并一次往返,内部逐项过确认,不可嵌套)

网络:`listRequests` / `readRequest` / `fetchWithSession`(全方法白名单 + body,但仅当前页同源或 `fetchAllowHosts` 白名单)

确认分两档:`confirmMode='none'`(默认,全不弹)与 `'all'`(全弹,控制台 `chrome.storage.local.set({confirmMode:'all'})` 开启)。

`readPage` / `findInPage` 每次生成新的 `snapshotId`。`click` / `fillInput` 必须带上它;页面变了会返回「快照已失效」,不会静默点错。

超长正文用 `offset` 续读,截断提示形如:`[已截断:本次返回 20000/52310 字符,用 offset=20000 继续读]`。

`chrome://`、扩展商店、PDF 查看器注入失败时返回可读错误,不抛给模型栈。

网页正文用 `<web_content untrusted="true">` 包裹。真正的隔离仍是:给插件配专用 Agent,不要挂 `bash` / 文件写。网络探针在 MAIN world、`document_start` 注入,不申请 `debugger` 权限。

机制细节(声明链路、装配互斥、断线补发、图片回传)见 `docs/渠道工具与浏览器插件.md`。

## 生产

- `.env.production` 把 `VITE_APP_BASE_API` 改成真实后端绝对地址
- CORS 把 `CORS_EXTENSION_ORIGINS` 设成精确的 `chrome-extension://<id>`，不要用通配
- 渠道工具默认关闭，上线前按灰度打开 `ai.chat.tool.channel.enabled`
- 上架 Chrome Web Store 前必须收窄 `host_permissions`（现在是 `http(s)://*/*`，审核会卡）。改用 `activeTab` + 动态申请，或精确域名列表。开发期可以保持全站权限。
