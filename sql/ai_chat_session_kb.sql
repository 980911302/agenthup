-- 新建 会话-知识库关联表(会话级多选知识库)
-- 2026-08-11: 智能体-知识库绑定(ai_agent_kb)废弃,知识库选择下沉到会话。
-- 与 init/03_ai_chat.sql 的 ai_chat_session_kb 建表保持一致；后端/前端改动见同次代码变更。
-- 配套执行 sql/drop_ai_agent_kb.sql 删除废弃的 ai_agent_kb 表。

create table if not exists ai_chat_session_kb (
  session_id  varchar(64) not null comment '会话ID(关联ai_chat_session)',
  kb_id       bigint(20)  not null comment '知识库ID(关联kb_knowledge.kb_id)',
  sort        int(11)     default 0 comment '显示顺序',
  create_time datetime    default null comment '创建时间',
  primary key (session_id, kb_id),
  key idx_kb_id (kb_id)
) engine=innodb comment = '会话-知识库关联';
