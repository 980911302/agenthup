# Keycloak 登录接入

本项目使用 Keycloak 作为 OAuth2/OpenID Connect 身份中心；应用仍负责本地用户、角色与菜单权限，并继续签发现有 JWT。

## 1. 启动 Keycloak

在本目录执行：

```bash
KEYCLOAK_ADMIN_PASSWORD='请替换为强密码' \
KEYCLOAK_DB_PASSWORD='请替换为数据库强密码' \
docker compose up -d
```

本地管理控制台为 `http://localhost:18080`。此 compose 用于开发或内网验证；生产环境应使用 HTTPS、受管 PostgreSQL，并通过反向代理配置真实域名。

## 2. 在 Keycloak 创建客户端

1. 新建 Realm，例如 `agent`。
2. 新建 OpenID Connect Client，Client ID 设为 `agent-web`。
3. 打开 **Client authentication** 和 **Standard flow**，复制生成的 Client secret。
4. 设置 Valid redirect URIs：`http://localhost:8080/login/oauth2/code/keycloak`。
5. 设置 Web origins：`http://localhost:80`。

Keycloak 还可以作为身份代理：在 Realm 的 Identity Providers 中继续配置 GitHub、Google、企业 IdP 或其他 OIDC/SAML 服务，应用侧无需改变。

## 2.1 配置 GitHub 身份代理（可选）

应用侧零改动，GitHub 登录完全由 Keycloak 代理。步骤如下：

1. 在 GitHub 创建 OAuth App：`Settings → Developer settings → OAuth Apps → New OAuth App`。
   - Homepage URL：Keycloak 对外可访问地址（本地联调填 `http://localhost:18080`）。
   - Authorization callback URL：`http://localhost:18080/realms/agent/broker/github/endpoint`（生产环境替换为 Keycloak 真实域名；realm 名不是 `agent` 时同步替换）。
   - 创建后复制 Client ID 与 Client Secret。

2. 在 Keycloak 管理台（`http://localhost:18080`）→ Realm `agent` → `Identity Providers` → `Add provider` → 选择 `GitHub`：
   - Alias 保持 `github`（决定上面回调地址中的路径）。
   - 填入 GitHub 的 Client ID / Client Secret。
   - **Trust Email 保持关闭**：GitHub 账号的邮箱可能未验证。
   - 需要邮箱时在 Scopes 中加 `user:email`（GitHub 用户资料 email 可能为空）。

3. 首次登录行为由 Keycloak 的 First Broker Login 流程控制：默认会创建一个 Keycloak 用户（以 GitHub 身份注册）。该 Keycloak 用户首次登录本系统时，仍受 `oauth.login.auto-register` 控制——关闭时需管理员在 `sys_user` / `sys_user_oauth_account` 预先绑定；Keycloak 颁发的 `iss`/`sub` 对代理用户保持稳定，绑定逻辑不受影响。

4. 验证：登录页点击「使用统一账号登录」→ Keycloak 登录页出现「Sign in with GitHub」→ 授权后回跳，按原有票据流程换取 JWT。

## 3. 初始化应用数据与环境变量

先执行 [oauth_keycloak.sql](../../sql/oauth_keycloak.sql)。随后为后端进程设置：

```bash
export OAUTH_LOGIN_ENABLED=true
export OAUTH_FRONTEND_URL=http://localhost:80
export OAUTH_BACKEND_URL=http://localhost:8080
export KEYCLOAK_ISSUER_URI=http://localhost:18080/realms/agent
export KEYCLOAK_CLIENT_ID=agent-web
export KEYCLOAK_CLIENT_SECRET='复制的客户端密钥'
```

默认不允许外部身份自动创建本地账号。生产环境请由管理员预先维护 `sys_user` 和 `sys_user_oauth_account` 的绑定关系。

若仅用于首次联调，可临时开启自动创建，并指定一个权限最小的本地角色：

```bash
export OAUTH_AUTO_REGISTER=true
export OAUTH_DEFAULT_ROLE_ID=2
```

不要在生产环境将普通测试角色直接作为默认角色；请先创建专用的“外部登录用户”角色并配置其 role_id。

## 登录过程

登录页会显示“使用统一账号登录”。后端以授权码流程完成与 Keycloak 的交互；成功后创建一个 60 秒、仅能使用一次的票据，前端以该票据兑换现有 JWT。JWT 不会放入回调 URL。
