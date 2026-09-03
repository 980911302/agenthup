-- ============================================================================
-- 06_ai_job.sql — 定时任务域初始化
-- 合并了 ai_job.sql(ai_job + ai_job_log + sys_config + sys_dict)
-- ============================================================================

-- 1. 智能体定时任务定义
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
  misfire_policy    char(1)      default '3'              comment '错过策略(2补跑一次 3放弃执行)',

  -- 会话归属
  session_mode      varchar(20)  default 'new'            comment '会话模式(new每次新建 fixed固定追加)',
  session_id        varchar(64)  default null             comment 'fixed模式绑定的会话ID',

  -- 运行边界
  timeout_seconds   int(11)      default 600              comment '单次运行超时(秒)',
  max_retry         int(4)       default 0                comment '失败重试次数',
  max_runs          int(11)      default null             comment '累计执行上限(null不限)',
  expire_time       datetime     default null             comment '过期时间',

  -- 创建来源与归属
  source            varchar(20)  default 'user'           comment '创建来源(user后台手建 agent智能体自建)',
  source_run_id     varchar(64)  default null             comment 'agent自建时的来源运行ID',
  owner_user_id     bigint(20)   not null                 comment '归属用户(执行时以其身份鉴权)',

  -- 运行态快照
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

-- 2. 触发日志
drop table if exists ai_job_log;
create table ai_job_log (
  log_id          bigint(20)   not null auto_increment  comment '日志ID',
  job_id          bigint(20)   not null                 comment '任务ID',
  job_name        varchar(100) default ''               comment '任务名快照',
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
  result_summary  text         default null             comment '结果摘要',
  error_message   text         default null             comment '失败原因',
  create_time     datetime                              comment '创建时间',
  primary key (log_id),
  key idx_job_fire (job_id, fire_time),
  key idx_run (run_id),
  key idx_status (status)
) engine=innodb comment='智能体定时任务触发日志';
