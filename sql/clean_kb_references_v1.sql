-- 执行前先确认行数仍是开发期量级(个位数)。若已积累大量生产数据,停止并改写迁移。
-- select count(1) from ai_chat_special_event where name='kb.references';

-- v1 payload 结构与 v2 不兼容,且均为开发期数据,直接清理
delete from ai_chat_special_event where name = 'kb.references';
