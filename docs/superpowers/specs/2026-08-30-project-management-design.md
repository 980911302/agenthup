# 项目管理(桌面端会话分组)设计

> 日期:2026-08-30
> 状态:已与用户确认范围

## 1. 一句话定位

在桌面工作台(desktop)增加「项目」,作为会话的可选分组容器:每个项目有名称与描述,一个会话可归属一个项目,也可不归属任何项目(普通会话)。新建对话时若当前选中项目则自动归属,否则为普通会话。

## 2. 范围

**做**:

- 新增 `ai_project` 表(项目名称 / 描述 / 归属用户 / 逻辑删除)。
- `ai_chat_session` 增加一列 `project_id`(null 表示未分组)。
- 后端标准若依三层接口(Controller → Service → Mapper),仅操作当前登录用户自己的项目。
- 桌面端侧栏「项目」区落地:项目列表、新建项目、点项目过滤其下会话、删除项目(级联删会话)。
- 发起对话时按当前选中项目绑定 `project_id`。

**不做**(YAGNI,不在需求内):

- 会话跨项目移动/拖拽。
- 项目停用状态字段。
- 管理后台(ruoyi-ui)项目管理菜单。
- 项目级 token/用量统计。

## 3. 数据模型

### 3.1 新表 `ai_project`(MySQL 主库)

```sql
create table ai_project (
  project_id    bigint(20)     not null auto_increment comment '项目ID',
  project_name  varchar(100)   not null                comment '项目名称',
  description   varchar(500)   default null            comment '项目描述',
  user_id       bigint(20)     default null            comment '归属用户ID(关联sys_user)',
  create_by     varchar(64)    default ''              comment '创建者',
  create_time   datetime                               comment '创建时间',
  update_by     varchar(64)    default ''              comment '更新者',
  update_time   datetime                               comment '更新时间',
  remark        varchar(500)   default null            comment '备注',
  del_flag      char(1)        default '0'             comment '删除标志(0存在 2删除)',
  primary key (project_id),
  key idx_user_id (user_id),
  key idx_create_time (create_time)
) engine=innodb comment = '项目表';
```

- 逻辑删除,与全站 `del_flag` 惯例一致。
- 项目名唯一性:同一用户下不强制唯一(避免过度约束),前端做必填与长度校验。

### 3.2 `ai_chat_session` 加列(增量 ALTER 脚本,遵循仓库惯例)

```sql
alter table ai_chat_session
  add column project_id bigint(20) default null comment '所属项目ID(空=未分组)' after source_job_id;
alter table ai_chat_session add index idx_project_id (project_id);
```

- 会话 → 项目:**多会话对一项目**(一个会话至多归属一个项目)。
- 归属可选:null = 未分组普通会话。
- 项目删除级联会话:删除项目时取其下全部会话并逐个复用现有 `deleteAiChatSessionById` 清理。

## 4. 后端设计

### 4.1 分层与文件

| 层 | 文件 |
|----|------|
| Controller | `ruoyi-admin/.../web/controller/ai/AiProjectController.java`(`/ai/project`) |
| Domain | `ruoyi-system/.../system/domain/AiProject.java` |
| Mapper | `ruoyi-system/.../system/mapper/AiProjectMapper.java` + `AiProjectMapper.xml` |
| Service | `ruoyi-system/.../system/service/IAiProjectService.java` + `impl/AiProjectServiceImpl.java` |

### 4.2 接口(全部强制 `user_id == 当前登录用户`)

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/ai/project/list` | 我的项目列表(带 session_count 会话数) |
| GET | `/ai/project/{projectId}` | 项目详情 |
| POST | `/ai/project` | 新建项目(name 必填,description 可空) |
| PUT | `/ai/project/{projectId}` | 改名称/描述 |
| DELETE | `/ai/project/{projectId}` | 删项目 → 级联删其下会话 |

权限:不挂按钮级权限点,由 Controller 内校验归属(与 `AiChatSessionController` 的会话准入同思路,但更轻——项目本身无 run/文件,只需 `user_id` 匹配)。

### 4.3 删除项目级联

`AiProjectServiceImpl.delete(projectId)`:

1. 校验项目存在且属于当前用户。
2. 查出项目下全部 `session_id`(`selectSessionIdsByProjectId`)。
3. 对每个会话:`sessionGuard.requireNoActiveRun(sessionId)` 前置检查(有运行中则报"会话正在执行,请先停止")。
4. 逐个调用 `aiChatSessionService.deleteAiChatSessionById(sessionId)`(现有实现,负责逻辑删除 + 消息/run/LLM 明细 + 工作区/上下文/工具预算清理)。
5. 逻辑删除项目行(`del_flag='2'`)。

注意:会话行需先把 `project_id` 置 null 或直接逻辑删除均可 —— 因级联删除会删会话本身,无需额外处理残留归属。

### 4.4 会话归属落库(唯一改动点)

- 会话行仍由 `SessionAccessGuard.requireOrCreate` 创建(**不改其签名**,避免影响 job 会话等其他创建路径)。
- 桌面端发起对话的 `ChatRunCreateCommand` 增加 `projectId` 字段(可空)。
- `SessionAccessGuard.requireOrCreate` 的 insert 不带 `project_id`(保持现有建行不变);`ChatRunService.ensureOwnedSession` 在 `requireOrCreate` 返回后,若该会话为**本次新建**且请求带 `projectId`,则执行一次补写 `project_id` 的 update(用现有 `updateAiChatSession`)。存量会话一律不覆盖(避免中途改绑)。
- 会话列表查询 `selectAiChatSessionList` 增加 `projectId` 过滤条件(为空则查全部)。

## 5. 前端设计(desktop)

### 5.1 侧栏「项目」区(`SessionSidebar.vue` 占位区替换)

- 加载并展示当前用户的项目列表:`project_name` + 会话数。
- 「+」按钮 → 新建项目弹窗(名称必填、描述可空)。
- 点击项目 → 该项目下会话列表(展开),选中态高亮。
- 「未分组/全部会话」入口:查看所有不归属项目的普通会话。
- 项目项 hover 出操作:编辑名称/描述、删除项目。
- 删除项目用 `confirmDanger` 确认,提示"将级联删除该项目下所有会话,不可恢复"。

### 5.2 新建对话归属

- 侧栏当前选中某项目时,`newConversation()` 记住 `projectId`;发起对话时随 payload 带上。
- 未选中项目:`projectId` 为空,普通会话。
- 新建对话后进入该项目分组下展示。

### 5.3 会话列表按项目过滤

- `listSession` 查询参数加 `projectId`;切换项目时重新拉取该项目会话。
- 侧栏会话分组(今天/昨天/过去7天/更早)仅在项目过滤结果内生效。

## 6. 错误处理与边界

- 越权:项目接口查无此项目/不属于当前用户 → 返回"项目不存在"。
- 删除项目时其下有会话正在运行 → 拦截并提示先停止。
- 项目名称为空 → 前端校验 + 后端校验(400)。
- 删除项目级联删会话是破坏性操作,前端必须二次确认。

## 7. 测试

- 后端:项目 CRUD 单测(建/改/删/越权拒绝);删除项目级联删会话(含运行中拦截)。
- 前端:手动验证 —— 建项目、选中建会话自动归属、点项目过滤、删项目级联删会话、未分组会话正常。
- 回归:不选项目新建会话仍是普通会话,`ai_chat_session` 其他创建路径(job 会话)不受影响。

## 8. 不做清单(明确排除)

- 会话跨项目移动。
- 多对多归属。
- 后台管理菜单。
- 项目级计量统计。
