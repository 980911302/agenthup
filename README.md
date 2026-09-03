# AgentHub

AgentHub 是一个面向团队与个人的通用智能体平台。它提供模型渠道管理、智能体编排、会话与项目管理、知识库检索、工具调用及 MCP 服务接入能力，并包含 Web、桌面端和浏览器扩展等客户端实现。

> 项目的基础管理能力基于 RuoYi-Vue Spring Boot 3 体系扩展；本仓库的重点是 AI Agent、知识库与工具生态。

## 功能概览

- **智能体与模型**：配置智能体、模型、渠道与可见范围，支持 OpenAI 兼容模型接入。
- **会话与项目**：管理会话、运行记录、附件、项目与跨会话长期记忆。
- **知识库**：文档摄入、向量检索、pgvector 存储，以及可选的 Neo4j 知识图谱能力。
- **工具生态**：内置文件和 Shell 工具，支持技能、MCP 服务及远程工具执行节点。
- **文件空间**：支持 S3 兼容对象存储的个人文件空间。
- **平台能力**：用户、角色、菜单、参数、定时任务、操作审计与 OpenAPI 文档。

## 项目结构

| 路径 | 说明 |
| --- | --- |
| `ruoyi-admin` | Spring Boot 服务入口与 Web API |
| `ruoyi-ui` | Vue 3 + Vite Web 客户端 |
| `ruoyi-system` | AI 会话、知识库、工具、记忆等领域实现 |
| `ruoyi-framework` | 安全、认证、缓存与框架配置 |
| `ruoyi-adapter` | 存储、模型及基础设施适配层 |
| `ai-contract` / `ai-runtime` / `ai-kb` / `ai-tool` | AI 领域契约、运行时、知识库与工具模块 |
| `tool-mcp-server` | 可独立部署的 MCP 工具执行服务 |
| `sql/init` | 全新环境的数据库初始化脚本 |
| `desktop` / `extension` | 桌面端与浏览器扩展客户端 |

## 快速开始

### 1. 准备依赖

- JDK 17+
- Maven 3.9+
- Node.js（用于 `ruoyi-ui`）
- MySQL、Redis
- PostgreSQL + pgvector（知识库功能）
- Neo4j（启用知识图谱时需要）

### 2. 配置环境变量

敏感配置不写入仓库。复制示例文件并填入当前环境的真实值：

```bash
cp .env.example .env
set -a && source .env && set +a
```

`.env` 仅供本地辅助使用，Spring Boot 不会自动读取它；生产环境请通过容器编排、CI/CD 或密钥管理服务注入同名环境变量。

| 用途 | 必填环境变量 |
| --- | --- |
| 加密与认证 | `RUOYI_ENCRYPT_KEY`、`TOKEN_SECRET` |
| 缓存 | `REDIS_PASSWORD` |
| MySQL | `MYSQL_USERNAME`、`MYSQL_PASSWORD` |
| PostgreSQL | `POSTGRES_USERNAME`、`POSTGRES_PASSWORD` |
| Neo4j | `NEO4J_USERNAME`、`NEO4J_PASSWORD` |
| Druid 控制台 | `DRUID_LOGIN_USERNAME`、`DRUID_LOGIN_PASSWORD` |

数据库地址、端口和运行时目录等非机密配置位于 `ruoyi-admin/src/main/resources/application*.yml`，请按部署环境调整。对象存储和 OAuth 如需启用，也应使用配置文件中已有的环境变量。

### 3. 初始化数据库

新环境请按 [SQL 初始化说明](sql/init/README.md) 的顺序执行 MySQL 和 PostgreSQL 脚本。已有环境升级请使用 `sql/` 根目录下相应的增量脚本。

### 4. 启动后端

```bash
mvn -pl ruoyi-admin -am package -DskipTests
java -jar ruoyi-admin/target/ruoyi-admin.jar
```

默认服务端口为 `8080`。OpenAPI 文档默认位于 `/swagger-ui.html`。

### 5. 启动 Web 客户端

```bash
cd ruoyi-ui
npm install
npm run dev
```

开发服务器默认监听 `80` 端口，并将 `/dev-api` 代理到后端 `8080` 端口。

## 开发校验

```bash
# 编译后端及依赖模块
mvn -pl ruoyi-admin -am -DskipTests compile

# 构建 Web 客户端
cd ruoyi-ui && npm run build:prod
```

## 安全说明

- 不要提交 `.env`、私钥、访问令牌、数据库备份或部署平台密钥。
- 所有密钥在提交前应轮换；即使删除了文件，旧 Git 历史中的密钥仍需视为已泄露。
- 生产环境应限制 Druid、Swagger 和 MCP 工具服务的网络访问范围，并为高风险工具启用人工确认策略。

## 贡献

提交 Issue 或 Pull Request 前，请确保不包含真实凭据、个人数据和生成产物，并通过与修改范围相匹配的编译或测试验证。
