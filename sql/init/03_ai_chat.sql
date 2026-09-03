-- ============================================================================
-- 03_ai_chat.sql — 聊天域初始化
-- 合并了 ai_chat_session.sql、ai_chat_context.sql(补 message_count)、
-- ai_llm_call.sql(补 prompt/completion_tokens/llm_call_count)、
-- ai_job.sql(补 session_type/source_job_id)、ai_chat_run.sql、ai_trace_span.sql、
-- ai_chat_message_tokens_comment.sql(修正注释)、ai_project.sql(项目表+会话 project_id 列)
-- ============================================================================

-- 1. 会话表
drop table if exists ai_chat_session;
create table ai_chat_session (
  session_id         varchar(64)   not null                 comment '会话ID(业务生成,如uuid)',
  title              varchar(200)  default ''               comment '会话标题(可由首条消息摘要生成)',
  session_type       varchar(20)   default 'chat'           comment '会话类型(chat普通对话 job定时任务)',
  client_type        varchar(32)   default 'desktop'        comment '客户端形态:desktop/mobile/api/browser_ext',
  client_tools       text          default null             comment '客户端工具清单快照(规范化+排序后的JSON)',
  client_tools_ver   varchar(64)   default null             comment '客户端能力版本;变了才重写清单',
  source_job_id      bigint(20)    default null             comment '来源任务ID(session_type=job时回指ai_job)',
  project_id         bigint(20)    default null             comment '所属项目ID(空=未分组)',
  user_id            bigint(20)    default null             comment '发起用户ID(关联sys_user)',
  status             char(1)       default '0'              comment '会话状态(0活跃 1已结束)',
  total_tokens       bigint(20)    default 0                comment '会话累计token(所有agent合计)',
  prompt_tokens      bigint(20)    default 0                comment '累计输入token',
  completion_tokens  bigint(20)    default 0                comment '累计输出token',
  llm_call_count     int(11)       default 0                comment '累计LLM调用次数',
  context_length     bigint(20)    default 0                comment '当前会话总上下文长度(字符数)',
  message_count      int(11)       default 0                comment '会话累计消息条数',
  create_by          varchar(64)   default ''               comment '创建者',
  create_time        datetime                               comment '创建时间',
  update_by          varchar(64)   default ''               comment '更新者',
  update_time        datetime                               comment '更新时间',
  remark             varchar(500)  default null             comment '备注',
  del_flag           char(1)       default '0'              comment '删除标志(0存在 2删除)',
  primary key (session_id),
  key idx_user_id (user_id),
  key idx_create_time (create_time),
  key idx_type_user (session_type, user_id, create_time),
  key idx_project_id (project_id)
) engine=innodb comment = '会话表';

-- 1b. 项目表(会话的可选分组容器)
drop table if exists ai_project;
create table ai_project (
  project_id    bigint(20)     not null auto_increment comment '项目ID',
  project_name  varchar(100)   not null                comment '项目名称',
  description   varchar(500)   default null            comment '项目描述',
  user_id       bigint(20)     default null            comment '归属用户ID(关联sys_user)',
  create_by     varchar(64)    default ''              comment '创建者',
  create_time   datetime                               comment '创建时间',
  update_by     varchar(64)    default ''              comment '更新者',
  update_time   datetime                               comment '更新时间',
  remark        varchar(500)   default null            comment '备注',
  del_flag      char(1)        default '0'             comment '删除标志(0存在 2删除)',
  primary key (project_id),
  key idx_user_id (user_id),
  key idx_create_time (create_time)
) engine=innodb comment = '项目表';

-- 2. 会话-智能体关联表
drop table if exists ai_chat_session_agent;
create table ai_chat_session_agent (
  id                 bigint(20)    not null auto_increment  comment '主键',
  session_id         varchar(64)   not null                 comment '会话ID',
  agent_id           bigint(20)    not null                 comment '智能体ID',
  role               varchar(20)   default 'worker'         comment '本会话中的角色(supervisor/worker)',
  tokens_used        bigint(20)    default 0                comment '该智能体在本会话消耗的token',
  prompt_tokens      bigint(20)    default 0                comment '累计输入token',
  completion_tokens  bigint(20)    default 0                comment '累计输出token',
  llm_call_count     int(11)       default 0                comment '累计LLM调用次数',
  turn_count         int(8)        default 0                comment '该智能体在本会话被调用的轮数',
  first_active_time  datetime                               comment '首次接入时间',
  last_active_time   datetime                               comment '最近活动时间',
  create_time        datetime                               comment '创建时间',
  primary key (id),
  unique key uk_session_agent (session_id, agent_id),
  key idx_agent_id (agent_id)
) engine=innodb auto_increment=100 comment = '会话-智能体关联表';

-- 2b. 会话-知识库关联表(会话级多选知识库;替代原 ai_agent_kb 智能体绑定,见 drop_ai_agent_kb.sql)
drop table if exists ai_chat_session_kb;
create table ai_chat_session_kb (
  session_id  varchar(64) not null comment '会话ID(关联ai_chat_session)',
  kb_id       bigint(20)  not null comment '知识库ID(关联kb_knowledge.kb_id)',
  sort        int(11)     default 0 comment '显示顺序',
  create_time datetime    default null comment '创建时间',
  primary key (session_id, kb_id),
  key idx_kb_id (kb_id)
) engine=innodb comment = '会话-知识库关联';

-- 3. 对话消息表(LLM上下文 + 前端时间线共用)
--    message_type: USER/ASSISTANT/SYSTEM/TOOL/THINKING/SUMMARY
--    visible_to_llm: '0'参与LLM上下文 '1'只给前端看
drop table if exists ai_chat_message;
create table ai_chat_message (
  message_id        bigint(20)     not null auto_increment  comment '消息ID(自增,同时作为会话内顺序)',
  session_id        varchar(64)    not null                 comment '会话ID(关联ai_chat_session)',
  run_id            varchar(64)    default null             comment '归属运行ID',
  message_kind      varchar(32)    default null             comment '消息业务语义',
  step_id           varchar(128)   default null             comment '归属运行步骤ID',
  parent_step_id    varchar(128)   default null             comment '父步骤ID',
  agent_id          bigint(20)     default null             comment '产生该消息的智能体ID',
  conversation_id   varchar(128)   not null                 comment 'LLM记忆键(=sessionId:agentId)',
  sub_agent_id      bigint(20)     default null             comment '被调用的子智能体ID(agent-as-tool时)',

  message_type      varchar(20)    not null                 comment 'USER/ASSISTANT/SYSTEM/TOOL/THINKING/SUMMARY',
  content           longtext                                comment '消息正文(SUMMARY行存摘要文本)',
  visible_to_llm    char(1)        default '0'              comment '是否参与LLM上下文(0是 1否),本质属性,写入即定',
  summary_to_id     bigint(20)     default null             comment 'SUMMARY行专用:本摘要覆盖了message_id<=此值的消息',
  attachments       json           default null             comment '富媒体附件[{type,url,name,size}]',
  tool_calls        longtext                                comment 'ASSISTANT行专用:本轮tool_calls的JSON数组[{id,type,name,arguments}]',

  tool_call_id      varchar(64)    default null             comment 'TOOL消息回指的调用ID',
  tool_name         varchar(100)   default null             comment '工具名(子智能体调用时为agentCode)',
  tool_args         longtext                                comment '工具入参',
  tool_result       longtext                                comment '工具返回(超过内联上限时截断,全文见tool_result_path)',
  tool_result_path  varchar(255)   default null             comment '工具返回超过内联上限时的文件路径',
  tool_source       varchar(20)    default null             comment '工具来源(builtin/mcp/agent)',
  tool_duration_ms  bigint(20)     default null             comment '工具执行耗时(毫秒)',
  tool_success      char(1)        default null             comment '工具是否成功(0成功 1失败)',
  pruned            char(1)        default '0'              comment '是否已被上下文清理(0否 1是);为1时装配给LLM出占位文本,原文保留供审计',

  tokens            int(11)        default 0                comment '消息token数(TokenEstimator估算,供上下文占比/预算)',
  prompt_tokens     int(11)        default 0                comment '该消息对应的输入token(仅ASSISTANT有值)',
  completion_tokens int(11)        default 0                comment '该消息对应的输出token(仅ASSISTANT有值)',
  model_name        varchar(100)   default null             comment '产生该消息的模型名',
  usage_source      char(1)        default '1'              comment 'prompt/completion归因字段来源(0上游真实 1本地估算)',
  create_time       datetime                                comment '创建时间',

  primary key (message_id),
  unique key uk_conv_summary_to (conversation_id, summary_to_id),
  key idx_session_msg (session_id, message_id),
  key idx_run_kind (run_id, message_kind, message_id),
  key idx_run_step (run_id, step_id),
  key idx_conv_llm (conversation_id, visible_to_llm, message_id),
  key idx_session_agent (session_id, agent_id),
  key idx_tool_name (tool_name),
  key idx_conv_tool_call (conversation_id, tool_call_id),
  key idx_sub_agent (sub_agent_id)
) engine=innodb auto_increment=100 comment = 'AI对话消息表(只增不删,LLM上下文与前端时间线共用)';

-- 4. AI对话运行事实表
drop table if exists ai_chat_run;
create table ai_chat_run (
  run_id               varchar(64)    not null                   comment '运行ID(UUID)',
  session_id           varchar(64)    not null                   comment '会话ID',
  agent_id             bigint(20)     not null                   comment '主智能体ID',
  selected_model_id    bigint(20)     default null               comment '本轮客户端选择的聊天模型ID，空=智能体默认',
  selected_model_code  varchar(100)   default null               comment '本轮实际模型编码快照',
  effective_skill_ids  text           default null               comment '本轮生效技能ID JSON（默认+@技能）',
  user_id              bigint(20)     not null                   comment '发起用户ID',
  client_request_id    varchar(64)    not null                   comment '客户端幂等请求ID',
  active_key           varchar(64)    default null               comment '活动态=session_id,终态=NULL',
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

-- 4b. Run 可恢复步骤投影；页面恢复不再从消息角色和工具名猜执行链
drop table if exists ai_chat_run_step;
create table ai_chat_run_step (
  id                   bigint(20)     not null auto_increment,
  run_id               varchar(64)    not null,
  session_id           varchar(64)    not null,
  step_id              varchar(128)   not null,
  parent_step_id       varchar(128)   default null,
  step_type            varchar(32)    not null comment 'content/reasoning/tool/agent/context/ui',
  status               varchar(24)    not null comment 'STREAMING/RUNNING/WAITING/SUCCEEDED/FAILED/CANCELLED',
  sort_no              bigint(20)     not null comment '首次出现事件序号',
  name                 varchar(200)   default null,
  source               varchar(32)    default null,
  confirm_id           varchar(64)    default null,
  input_data           longtext,
  output_data          longtext,
  output_data_path     varchar(512)   default null comment '超长 output 外置路径,恢复只用预览',
  attachments          longtext,
  success              char(1)        default null,
  duration_ms          bigint(20)     default null,
  last_event_seq       bigint(20)     not null default 0,
  message_id           bigint(20)     default null comment '对应 ai_chat_message,运行中恢复查看完整结果',
  started_time         datetime       default null,
  finished_time        datetime       default null,
  create_time          datetime       not null,
  update_time          datetime       not null,
  primary key (id),
  unique key uk_run_step (run_id, step_id),
  key idx_run_sort (run_id, sort_no),
  key idx_session_run (session_id, run_id)
) engine=innodb comment='AI对话运行可恢复步骤投影';

-- 5. AI链路追踪span表
drop table if exists ai_trace_span;
create table ai_trace_span (
  span_id           bigint       not null auto_increment comment 'span主键',
  run_id            varchar(64)  not null                comment '归属轮次ai_chat_run.run_id',
  session_id        varchar(64)  not null                comment '会话id',
  parent_span_id    bigint       default null            comment '父span;turn根为NULL',
  span_type         varchar(20)  not null                comment 'turn/llm/tool_batch/tool/subagent',
  agent_id          bigint       default null            comment '执行agent',
  sub_agent_id      bigint       default null            comment 'subagent类型的子agent id',
  model_id          bigint       default null            comment 'llm类型模型id',
  model_name        varchar(100) default null            comment 'llm模型名',
  tool_name         varchar(100) default null            comment 'tool/tool_batch工具名(batch为null)',
  tool_call_id      varchar(64)  default null            comment '上游tool_call id(并行归位用)',
  call_seq          int          default null            comment 'run内LLM调用序号(llm类型)',
  depth             int          default 0               comment '子agent嵌套深度',
  status            varchar(20)  not null default 'started' comment 'started/succeeded/failed',
  prompt_tokens     int          default null            comment 'llm类型:输入token',
  completion_tokens int          default null            comment 'llm类型:输出token',
  total_tokens      int          default null            comment 'llm类型:总token',
  cache_hit_tokens  int          default null            comment 'llm类型:缓存命中',
  cache_miss_tokens int          default null            comment 'llm类型:缓存未命中',
  usage_source      char(1)      default null            comment '0上游真实 1本地估算',
  duration_ms       bigint       default null            comment '耗时(毫秒)',
  started_at        datetime     not null                comment '开始时间',
  finished_at       datetime     default null            comment '结束时间',
  create_time       datetime     default null            comment '创建时间',
  primary key (span_id),
  key idx_trace_run (run_id),
  key idx_trace_session (session_id, started_at)
) engine=innodb default charset=utf8mb4 comment='AI链路追踪span';

-- 6. 会话特殊事件(UI 产物)。生命周期跟会话,不跟 run。
drop table if exists ai_chat_special_event;
create table ai_chat_special_event (
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
