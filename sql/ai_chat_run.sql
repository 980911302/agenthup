-- ----------------------------
-- AI 对话运行表
-- ----------------------------
-- 设计约束：
-- 1. ai_chat_run / ai_chat_message 是运行状态与消息内容的事实源；上下文文件仅是可重建投影。
-- 2. active_key 在活动态等于 session_id，进入终态后置空，唯一索引保证同一会话只有一个活动运行。
-- 3. (user_id, client_request_id) 用于页面重试、断网重发时的幂等创建。
-- 4. last_event_seq 是客户端恢复订阅的高水位，事件正文由 Redis Stream 短期保留。

create table if not exists ai_chat_run (
  run_id               varchar(64)    not null                   comment '运行ID(UUID)',
  session_id           varchar(64)    not null                   comment '会话ID',
  agent_id             bigint(20)     not null                   comment '主智能体ID',
  selected_model_id    bigint(20)     default null               comment '本轮客户端选择的聊天模型ID，空=智能体默认',
  selected_model_code  varchar(100)   default null               comment '本轮实际模型编码快照',
  effective_skill_ids  text           default null               comment '本轮生效技能ID JSON（默认+@技能）',
  user_id              bigint(20)     not null                   comment '发起用户ID',
  client_request_id    varchar(64)    not null                   comment '客户端幂等请求ID',
  active_key           varchar(64)    default null               comment '活动态=session_id，终态=NULL',
  status               varchar(20)    not null                   comment 'QUEUED/RUNNING/FINALIZING/SUCCEEDED/FAILED/CANCELLED/INTERRUPTED',
  input_text           longtext       not null                   comment '本轮用户输入',
  attachments          longtext       default null               comment '附件元数据JSON',
  request_message_id   bigint(20)     default null               comment '对应USER消息ID',
  response_message_id  bigint(20)     default null               comment '对应最终ASSISTANT消息ID',
  last_event_seq       bigint(20)     not null default 0         comment '最后发布事件序号',
  snapshot_seq         bigint(20)     not null default 0         comment '步骤快照已覆盖到的事件序号',
  cancel_requested     char(1)        not null default '0'       comment '是否请求取消(0否 1是)',
  worker_id            varchar(100)   default null               comment '执行实例ID',
  error_code           varchar(64)    default null               comment '终态错误码',
  error_message        text           default null               comment '终态错误摘要',
  started_time         datetime       default null               comment '开始执行时间',
  heartbeat_time       datetime       default null               comment '执行心跳时间',
  finished_time        datetime       default null               comment '结束时间',
  create_time          datetime       not null                   comment '创建时间',
  update_time          datetime       not null                   comment '更新时间',
  primary key (run_id),
  unique key uk_ai_chat_run_active (active_key),
  unique key uk_ai_chat_run_request (user_id, client_request_id),
  key idx_ai_chat_run_session_time (session_id, create_time),
  key idx_ai_chat_run_user_time (user_id, create_time),
  key idx_ai_chat_run_status_heartbeat (status, heartbeat_time)
) engine=innodb comment='AI对话运行事实表';

create table if not exists ai_chat_run_step (
  id bigint(20) not null auto_increment, run_id varchar(64) not null, session_id varchar(64) not null,
  step_id varchar(128) not null, parent_step_id varchar(128) default null,
  step_type varchar(32) not null, status varchar(24) not null, sort_no bigint(20) not null,
  name varchar(200), source varchar(32), confirm_id varchar(64), input_data longtext, output_data longtext,
  output_data_path varchar(512) default null, attachments longtext,
  success char(1), duration_ms bigint(20), last_event_seq bigint(20) not null default 0,
  started_time datetime, finished_time datetime, create_time datetime not null, update_time datetime not null,
  primary key (id), unique key uk_run_step (run_id, step_id), key idx_run_sort (run_id, sort_no),
  key idx_session_run (session_id, run_id)
) engine=innodb comment='AI对话运行可恢复步骤投影';
