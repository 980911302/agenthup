-- 运行中恢复「查看完整结果」需要步骤投影带上 TOOL 行 message_id
alter table ai_chat_run_step
  add column message_id bigint(20) default null after last_event_seq;
