-- 已有 ai_chat_special_event 补乐观锁列。新库走 init/03_ai_chat.sql,不必执行。
alter table ai_chat_special_event
  add column version int not null default 0 comment '乐观锁版本';
