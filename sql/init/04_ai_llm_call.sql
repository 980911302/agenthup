-- ============================================================================
-- 04_ai_llm_call.sql — LLM调用计量初始化
-- 合并了 ai_llm_call.sql + ai_llm_call_cache_tokens.sql(cache_hit/miss_tokens)
-- ============================================================================

-- LLM调用明细表(粒度:一次真实的模型API调用 = 一行)
drop table if exists ai_llm_call;
create table ai_llm_call (
  call_id           bigint(20)     not null auto_increment  comment '主键',
  session_id        varchar(64)    not null                 comment '会话ID',
  agent_id          bigint(20)     default null             comment '发起调用的智能体ID(通用对话为空)',
  conversation_id   varchar(128)   default null             comment 'LLM记忆键(sessionId:agentId)',
  message_id        bigint(20)     default null             comment '归属的最终ASSISTANT消息ID(工具中间轮为空)',

  model_id          bigint(20)     default null             comment '配置表模型ID(ai_model)',
  model_name        varchar(100)   default null             comment '上游实际返回的模型名',

  call_seq          int(8)         default 1                comment '本轮对话内的第几次调用(1=首次,>1=工具续轮)',
  depth             int(4)         default 0                comment '智能体嵌套深度(0=顶层,1+=子智能体)',
  finish_reason     varchar(32)    default null             comment '结束原因(stop/tool_calls/length等)',

  prompt_tokens     int(11)        default 0                comment '输入token(真实)',
  completion_tokens int(11)        default 0                comment '输出token(真实)',
  total_tokens      int(11)        default 0                comment '合计token',
  cache_hit_tokens  int(11)        default 0                comment '输入中命中上游缓存的token数',
  cache_miss_tokens int(11)        default 0                comment '输入中未命中上游缓存的token数',
  usage_source      char(1)        default '0'              comment 'token来源(0上游真实 1本地估算)',

  duration_ms       bigint(20)     default null             comment '本次调用耗时(毫秒)',
  response_id       varchar(64)    default null             comment '上游响应ID(排障用)',
  create_time       datetime                                comment '创建时间',

  primary key (call_id),
  key idx_session (session_id, call_id),
  key idx_agent_time (agent_id, create_time),
  key idx_model_time (model_id, create_time),
  key idx_create_time (create_time)
) engine=innodb auto_increment=100 comment = 'LLM调用明细表(一次模型API调用一行)';
