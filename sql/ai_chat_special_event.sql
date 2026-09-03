-- 会话特殊事件(UI 产物)。生命周期跟会话,不跟 run。
-- 重发某轮的 deleteByRunId 不得级联本表。
create table if not exists ai_chat_special_event (
  id               bigint       not null auto_increment comment '主键',
  session_id       varchar(64)  not null comment '会话 ID',
  run_id           varchar(64)  null     comment '产生它的 run（仅追溯用，不参与生命周期）',
  message_id       bigint       null     comment '回合锚点，关联 ai_chat_message.message_id',
  agent_id         bigint       null     comment '产出方 agent',
  owner_agent_code varchar(64)  null     comment '子智能体归属标签',
  name             varchar(64)  not null comment '产物名，见 UiArtifactNames',
  schema_version   int          not null default 1,
  event_id         varchar(160) not null comment '幂等键',
  payload          longtext     null     comment 'JSON 载荷',
  version          int          not null default 0 comment '乐观锁版本',
  create_time      datetime     not null,
  primary key (id),
  unique key uk_session_event (session_id, event_id),
  key idx_session_msg (session_id, message_id),
  key idx_session_name (session_id, name)
) engine=innodb default charset=utf8mb4 comment='会话特殊事件(UI 产物)';
