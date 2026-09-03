-- ----------------------------
-- 智能体定时任务(配合 docs/ai/ai-job.md)
--
-- 背景：AI 定时任务不能复用 sys_job。sys_job.invoke_target 是 Spring bean 反射调用字符串,
-- 受 ScheduleUtils.whiteList 白名单约束、面向开发者;本模块的载荷是自然语言 prompt,
-- 创建者是业务用户甚至智能体自己,权限归属走 owner_user_id。二者语义不重叠。
-- 底层 Quartz Scheduler 复用(阶段零已切 JDBC 集群),但调度对象、权限模型、UI 完全独立。
-- ----------------------------

-- ----------------------------
-- 会话类型:让定时任务产生的会话可被筛选与归因
--
-- 为什么必须加 source_job_id:session_mode=new 时每次触发都新建一个会话,
-- 跑一个月就是 30 条。没有回指字段,这些会话在列表里就是一堆无法归因的孤儿。
-- 索引把 session_type 放最左,因为筛选时它必然是等值条件;不加索引的话会话列表页
-- 在数据量上来后会全表扫。
-- ----------------------------
alter table ai_chat_session
  add column session_type  varchar(20) default 'chat' comment '会话类型(chat普通对话 job定时任务)' after title,
  add column source_job_id bigint(20)  default null   comment '来源任务ID(session_type=job时回指ai_job)' after session_type,
  add key idx_type_user (session_type, user_id, create_time);

-- dict_type id=11、dict_data id=30/31 已核对可用(现有最大 type=10、data=29)
insert into sys_dict_type values(11, '会话类型', 'ai_session_type', '0', 'admin', sysdate(), '', null, 'AI会话类型');
insert into sys_dict_data values(30, 1, '普通对话', 'chat', 'ai_session_type', '', 'primary', 'Y', '0', 'admin', sysdate(), '', null, '');
insert into sys_dict_data values(31, 2, '定时任务', 'job',  'ai_session_type', '', 'warning', 'N', '0', 'admin', sysdate(), '', null, '');

-- ----------------------------
-- 智能体定时任务定义
--
-- 与 sys_job 的区别(为什么不复用):
--   sys_job.invoke_target 是 Spring bean 反射调用字符串,受 ScheduleUtils.whiteList 白名单约束,
--   面向开发者;本表的载荷是自然语言 prompt,创建者是业务用户甚至智能体自己,
--   权限归属走 owner_user_id 而不是 admin 独占。二者语义不重叠。
--   底层 Quartz Scheduler 复用,但调度对象、权限模型、UI 完全独立。
--
-- 设计要点:
--   timezone 不能省 —— 不存时区的 cron 在服务器迁移或跨时区用户场景下必然漂移。
--   没有 concurrent 字段 —— fixed 模式的并发由 uk_ai_chat_run_active 天然阻断,
--     new 模式每次新会话本就不冲突,留一个不起作用的配置项只会误导用户。
--   status 有第三态 2 已完成 —— once 执行完 / max_runs 到顶 / expire_time 过期
--     都需要区别于「用户主动暂停」的终态。
--   source_run_id —— 智能体自建任务必须能溯源到用户当时说了哪句话。
-- ----------------------------
drop table if exists ai_job;
create table ai_job (
  job_id            bigint(20)   not null auto_increment  comment '任务ID',
  job_name          varchar(100) not null                 comment '任务名称',
  agent_id          bigint(20)   not null                 comment '执行智能体ID(关联ai_agent)',
  prompt            longtext     not null                 comment '触发时投喂给智能体的指令',
  attachments       json         default null             comment '固定附件元数据[{path,name,size}]',

  -- 触发规则
  trigger_type      varchar(20)  not null default 'cron'  comment '触发类型(cron周期 once一次性)',
  cron_expression   varchar(255) default null             comment 'cron表达式(trigger_type=cron时必填)',
  fire_time         datetime     default null             comment '执行时刻(trigger_type=once时必填)',
  timezone          varchar(64)  default 'Asia/Shanghai'  comment '时区',
  -- 禁用 misfire=1(FireAllTriggers),它会把停机期间所有错过的触发补跑一遍,token 烧穿
  misfire_policy    char(1)      default '3'              comment '错过策略(2补跑一次 3放弃执行);禁用1,它会补跑全部错过的触发',

  -- 会话归属
  session_mode      varchar(20)  default 'new'            comment '会话模式(new每次新建 fixed固定追加)',
  session_id        varchar(64)  default null             comment 'fixed模式绑定的会话ID',

  -- 运行边界(无人值守必须有闸)
  timeout_seconds   int(11)      default 600              comment '单次运行超时(秒)',
  max_retry         int(4)       default 0                comment '失败重试次数',
  max_runs          int(11)      default null             comment '累计执行上限(null不限),到达自动转已完成',
  expire_time       datetime     default null             comment '过期时间,到期自动转已完成',

  -- 创建来源与归属
  source            varchar(20)  default 'user'           comment '创建来源(user后台手建 agent智能体自建)',
  source_run_id     varchar(64)  default null             comment 'agent自建时的来源运行ID,可溯源到用户原话',
  owner_user_id     bigint(20)   not null                 comment '归属用户(执行时以其身份鉴权)',

  -- 运行态快照(冗余,避免列表页 join ai_job_log)
  status            char(1)      default '0'              comment '状态(0正常 1暂停 2已完成)',
  prev_fire_time    datetime     default null             comment '上次触发时间',
  next_fire_time    datetime     default null             comment '下次触发时间',
  run_count         int(11)      default 0                comment '累计触发次数',
  fail_count        int(11)      default 0                comment '累计失败次数',
  last_run_id       varchar(64)  default null             comment '最近一次运行ID',
  last_status       varchar(20)  default null             comment '最近一次结果',

  create_by         varchar(64)  default ''               comment '创建者',
  create_time       datetime                              comment '创建时间',
  update_by         varchar(64)  default ''               comment '更新者',
  update_time       datetime                              comment '更新时间',
  remark            varchar(500) default null             comment '备注',
  del_flag          char(1)      default '0'              comment '删除标志(0存在 2删除)',
  primary key (job_id),
  key idx_owner_status (owner_user_id, status),
  key idx_source (source, owner_user_id),
  key idx_agent (agent_id)
) engine=innodb auto_increment=100 comment='智能体定时任务表';

-- ----------------------------
-- 触发日志
--
-- 为什么不能用 ai_chat_run 代替(这是最容易被砍错的一张表):
--   1. 触发了但没能创建出 run 的情况,run 表里根本没有行 ——
--      上一轮未结束(uk_ai_chat_run_active 冲突)、智能体被停用、misfire 放弃。
--      而这些恰恰是排障时最需要看到的记录。
--   2. run 是执行视角,本表是调度视角。scheduled_time 与 fire_time 的差即调度延迟,
--      run 表没有这个概念。
--   3. 开启重试后一次触发对应多个 run,需要一个父级把它们串起来。
--
-- idx_status 给对账任务用(扫 status='DISPATCHED' 的行)。
-- ----------------------------
drop table if exists ai_job_log;
create table ai_job_log (
  log_id          bigint(20)   not null auto_increment  comment '日志ID',
  job_id          bigint(20)   not null                 comment '任务ID',
  job_name        varchar(100) default ''               comment '任务名快照(任务改名后历史仍可读)',
  agent_id        bigint(20)   default null             comment '智能体ID快照',
  scheduled_time  datetime     default null             comment '计划触发时刻',
  fire_time       datetime     not null                 comment '实际触发时刻',
  run_id          varchar(64)  default null             comment '产生的运行ID(未创建成功则为空)',
  session_id      varchar(64)  default null             comment '会话ID',
  status          varchar(20)  not null                 comment 'SKIPPED/DISPATCHED/SUCCEEDED/FAILED/CANCELLED/TIMEOUT',
  skip_reason     varchar(200) default null             comment '跳过原因',
  retry_no        int(4)       default 0                comment '第几次重试(0为首次)',
  duration_ms     bigint(20)   default null             comment '端到端耗时(毫秒)',
  tokens_used     bigint(20)   default 0                comment '本次token消耗',
  result_summary  text         default null             comment '结果摘要,列表页直出不必回查message',
  error_message   text         default null             comment '失败原因',
  create_time     datetime                              comment '创建时间',
  primary key (log_id),
  key idx_job_fire (job_id, fire_time),
  key idx_run (run_id),
  key idx_status (status)
) engine=innodb comment='智能体定时任务触发日志';

-- ----------------------------
-- 走 sys_config 而非 application.yml:这几个是上线后要按实际效果调的运营参数,
-- 与 ai_chat_context.sql 里 ai.context.* 的处理方式保持一致。
--
-- ai.job.minIntervalMinutes 不要省略。模型生成 cron 时把 0 0 9 * * ? 写成 0 * * * * ?
-- 是高频错误,一晚上能烧掉可观的 token。
-- sys_config id 103-105 已核对可用(ai_chat_context.sql 占用 100-102)。
-- ----------------------------
insert into sys_config values(103, 'AI定时任务-单用户任务上限', 'ai.job.maxPerUser', '20', 'Y', 'admin', sysdate(), '', null, '单用户可拥有的启用中定时任务数上限,防止智能体失控刷任务');
insert into sys_config values(104, 'AI定时任务-最小触发间隔分钟', 'ai.job.minIntervalMinutes', '5', 'Y', 'admin', sysdate(), '', null, 'cron最小触发间隔,防止模型生成每秒执行的表达式');
insert into sys_config values(105, 'AI定时任务-日志保留天数', 'ai.job.logRetainDays', '90', 'Y', 'admin', sysdate(), '', null, 'ai_job_log 超过该天数自动清理,填0不清理');

-- 菜单:挂在 AI 中心(menu_id=2000)下
-- 固定 menu_id,便于角色授权与按钮权限(前端 v-hasPermi 需要 F 级 perms)
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
values (2083, '定时任务', 2000, 8, 'job', 'ai/job/index', 'AiJob', 1, 0, 'C', '0', '0', 'ai:job:list', 'job', 'admin', sysdate(), '智能体定时任务');
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
values (2084, '任务查询', 2083, 1, '#', '', 1, 0, 'F', '0', '0', 'ai:job:query', '#', 'admin', sysdate(), '');
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
values (2085, '任务新增', 2083, 2, '#', '', 1, 0, 'F', '0', '0', 'ai:job:add', '#', 'admin', sysdate(), '');
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
values (2086, '任务修改', 2083, 3, '#', '', 1, 0, 'F', '0', '0', 'ai:job:edit', '#', 'admin', sysdate(), '');
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
values (2087, '任务删除', 2083, 4, '#', '', 1, 0, 'F', '0', '0', 'ai:job:remove', '#', 'admin', sysdate(), '');
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
values (2088, '任务启停', 2083, 5, '#', '', 1, 0, 'F', '0', '0', 'ai:job:changeStatus', '#', 'admin', sysdate(), '');

-- 给 admin 角色(role_id=1)授权
insert into sys_role_menu (role_id, menu_id) select 1, menu_id from sys_menu where menu_id between 2083 and 2088;
