-- ----------------------------
-- AI Token 计量：LLM 调用明细表 + 会话/消息补列
-- 执行前请备份；已有环境用本脚本增量变更（勿 drop 业务表）
--
-- ⚠️ 本脚本只能执行一次
-- alter table ... add column 不具备幂等性，重复执行会报 Duplicate column name。
-- 若不确定是否已执行过，先运行下面的检查语句：
--
--   select table_name, column_name from information_schema.columns
--    where table_schema = database()
--      and ((table_name = 'ai_chat_session'       and column_name = 'llm_call_count')
--        or (table_name = 'ai_chat_session_agent' and column_name = 'llm_call_count')
--        or (table_name = 'ai_chat_message'       and column_name = 'usage_source'));
--
--   有结果 = 对应的 alter 已执行过，请注释掉那一段再跑。
-- ----------------------------

-- ----------------------------
-- LLM 调用明细表(ai_llm_call)
-- 粒度：一次真实的模型 API 调用 = 一行
-- ----------------------------
create table if not exists ai_llm_call (
  call_id           bigint(20)     not null auto_increment    comment '主键',
  session_id        varchar(64)    not null                   comment '会话ID',
  agent_id          bigint(20)     default null               comment '发起调用的智能体ID(通用对话为空)',
  conversation_id   varchar(128)   default null               comment 'LLM记忆键(sessionId:agentId)，子智能体无状态时为空',
  message_id        bigint(20)     default null               comment '归属的最终 ASSISTANT 消息ID(工具中间轮为空)',

  model_id          bigint(20)     default null               comment '配置表模型ID(ai_model)',
  model_name        varchar(100)   default null               comment '上游实际返回的模型名(metadata.getModel())，可能与配置不同',

  call_seq          int(8)         default 1                  comment '本轮对话内的第几次调用(1=首次，>1=工具续轮)',
  depth             int(4)         default 0                  comment '智能体嵌套深度(0=顶层，1+=子智能体)',
  finish_reason     varchar(32)    default null               comment '结束原因(stop/tool_calls/length等)',

  prompt_tokens     int(11)        default 0                  comment '输入token(真实)',
  completion_tokens int(11)        default 0                  comment '输出token(真实)',
  total_tokens      int(11)        default 0                  comment '合计token',
  usage_source      char(1)        default '0'                comment 'token来源(0上游真实 1本地估算)',

  duration_ms       bigint(20)     default null               comment '本次调用耗时(毫秒)',
  response_id       varchar(64)    default null               comment '上游响应ID(排障用)',
  create_time       datetime                                  comment '创建时间',

  primary key (call_id),
  key idx_session (session_id, call_id),
  key idx_agent_time (agent_id, create_time),
  key idx_model_time (model_id, create_time),
  key idx_create_time (create_time)
) engine=innodb auto_increment=100 comment = 'LLM调用明细表(一次模型API调用一行)';

-- ----------------------------
-- 补列：ai_chat_session
-- ----------------------------
alter table ai_chat_session
  add column prompt_tokens     bigint(20) default 0 comment '累计输入token'   after total_tokens,
  add column completion_tokens bigint(20) default 0 comment '累计输出token'   after prompt_tokens,
  add column llm_call_count    int(11)    default 0 comment '累计LLM调用次数' after completion_tokens;

-- ----------------------------
-- 补列：ai_chat_session_agent
-- ----------------------------
alter table ai_chat_session_agent
  add column prompt_tokens     bigint(20) default 0 comment '累计输入token'   after tokens_used,
  add column completion_tokens bigint(20) default 0 comment '累计输出token'   after prompt_tokens,
  add column llm_call_count    int(11)    default 0 comment '累计LLM调用次数' after completion_tokens;

-- ----------------------------
-- 补列：ai_chat_message
-- ----------------------------
alter table ai_chat_message
  add column prompt_tokens     int(11)      default 0    comment '该消息对应的输入token(仅ASSISTANT有值)' after tokens,
  add column completion_tokens int(11)      default 0    comment '该消息对应的输出token(仅ASSISTANT有值)' after prompt_tokens,
  add column model_name        varchar(100) default null comment '产生该消息的模型名'                     after completion_tokens,
  add column usage_source      char(1)      default '1'  comment 'token来源(0上游真实 1本地估算)'         after model_name;
