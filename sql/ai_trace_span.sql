-- -------------------------------------------
-- AI 链路追踪 span 表:一轮对话(run)内的调用树
-- turn / llm / tool_batch / tool / subagent
-- -------------------------------------------
DROP TABLE IF EXISTS ai_trace_span;
CREATE TABLE ai_trace_span (
  span_id          bigint AUTO_INCREMENT PRIMARY KEY COMMENT 'span 主键',
  run_id           varchar(64)  NOT NULL COMMENT '归属轮次 ai_chat_run.run_id(turn span 与 run 1:1)',
  session_id       varchar(64)  NOT NULL COMMENT '会话 id',
  parent_span_id   bigint       DEFAULT NULL COMMENT '父 span;turn 根为 NULL',
  span_type        varchar(20)  NOT NULL COMMENT 'turn/llm/tool_batch/tool/subagent',
  agent_id         bigint       DEFAULT NULL COMMENT '执行 agent',
  sub_agent_id     bigint       DEFAULT NULL COMMENT 'subagent 类型的子 agent id',
  model_id         bigint       DEFAULT NULL COMMENT 'llm 类型模型 id',
  model_name       varchar(100) DEFAULT NULL COMMENT 'llm 模型名',
  tool_name        varchar(100) DEFAULT NULL COMMENT 'tool/tool_batch 工具名(batch 为 null)',
  tool_call_id     varchar(64)  DEFAULT NULL COMMENT '上游 tool_call id(并行归位用)',
  call_seq         int          DEFAULT NULL COMMENT 'run 内 LLM 调用序号(llm 类型)',
  depth            int          DEFAULT 0 COMMENT '子 agent 嵌套深度',
  status           varchar(20)  NOT NULL DEFAULT 'started' COMMENT 'started/succeeded/failed',
  prompt_tokens    int          DEFAULT NULL COMMENT 'llm 类型:输入 token',
  completion_tokens int         DEFAULT NULL COMMENT 'llm 类型:输出 token',
  total_tokens     int          DEFAULT NULL COMMENT 'llm 类型:总 token',
  cache_hit_tokens int          DEFAULT NULL COMMENT 'llm 类型:缓存命中',
  cache_miss_tokens int         DEFAULT NULL COMMENT 'llm 类型:缓存未命中',
  usage_source     char(1)      DEFAULT NULL COMMENT '0上游真实 1本地估算',
  duration_ms      bigint       DEFAULT NULL COMMENT '耗时(毫秒)',
  started_at       datetime     NOT NULL COMMENT '开始时间',
  finished_at      datetime     DEFAULT NULL COMMENT '结束时间',
  create_time      datetime     DEFAULT NULL COMMENT '创建时间',
  KEY idx_trace_run (run_id),
  KEY idx_trace_session (session_id, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 链路追踪 span';
