-- 开发环境升级：历史对话数据无需兼容，执行前可先清空 ai_chat_message / ai_chat_run。
alter table ai_chat_message
  add column run_id varchar(64) default null after session_id,
  add column message_kind varchar(32) default null after run_id,
  add column step_id varchar(128) default null after message_kind,
  add column parent_step_id varchar(128) default null after step_id,
  add key idx_run_kind (run_id, message_kind, message_id),
  add key idx_run_step (run_id, step_id);

alter table ai_chat_run
  add column snapshot_seq bigint(20) not null default 0 after last_event_seq;

drop table if exists ai_chat_run_step;
create table ai_chat_run_step (
  id bigint(20) not null auto_increment, run_id varchar(64) not null, session_id varchar(64) not null,
  step_id varchar(128) not null, parent_step_id varchar(128), step_type varchar(32) not null,
  status varchar(24) not null, sort_no bigint(20) not null, name varchar(200), source varchar(32), confirm_id varchar(64),
  input_data longtext, output_data longtext, attachments longtext, success char(1), duration_ms bigint(20),
  last_event_seq bigint(20) not null default 0, started_time datetime, finished_time datetime,
  create_time datetime not null, update_time datetime not null,
  primary key (id), unique key uk_run_step (run_id, step_id), key idx_run_sort (run_id, sort_no),
  key idx_session_run (session_id, run_id)
) engine=innodb comment='AI对话运行可恢复步骤投影';
