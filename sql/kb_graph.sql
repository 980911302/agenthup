-- ============================================================
-- 知识库第二期:知识图谱 DDL(PostgreSQL)
-- 见 docs/ai/ai-kb-phase2-graph-plan.md
-- Neo4j 约束在应用启动时 create if not exists
-- ============================================================

-- kb_knowledge 扩展
alter table kb_knowledge
  add column if not exists graph_enabled      char(1)      default '0';
alter table kb_knowledge
  add column if not exists extract_model_code varchar(100) default null;
comment on column kb_knowledge.graph_enabled is '是否启用图谱 0否 1是';
comment on column kb_knowledge.extract_model_code is '图谱抽取模型 code(建议便宜模型)';

-- 文档图谱抽取状态(与 parse_status 解耦)
create table if not exists kb_doc_graph (
  doc_id         bigint       primary key,
  kb_id          bigint       not null,
  graph_status   varchar(20)  not null default 'PENDING',
  graph_step     varchar(40),
  progress       int          default 0,
  chunk_total    int          default 0,
  chunk_done     int          default 0,
  entity_count   int          default 0,
  relation_count int          default 0,
  extract_model  varchar(100),
  error_type     varchar(50),
  error_msg      text,
  started_at     timestamp,
  finished_at    timestamp,
  active_run_id  bigint       default null,
  generation     bigint       default 0,
  graph_version  varchar(64)  default null
);
create index if not exists idx_doc_graph_kb on kb_doc_graph (kb_id);
create index if not exists idx_doc_graph_status on kb_doc_graph (graph_status);
comment on table kb_doc_graph is '文档图谱抽取状态';

-- 图抽取运行记录与 doc_graph 扩展(增量幂等,见 sql/kb_graph_v2.sql)
-- 新环境执行本文件已含 v2 列;存量库请另执行 kb_graph_v2.sql

create table if not exists kb_graph_run (
  run_id              bigint       primary key,
  kb_id               bigint       not null,
  doc_id              bigint       not null,
  generation          bigint       not null,
  source_content_hash varchar(64)  default null,
  parser_version      varchar(20)  default null,
  chunk_params_hash   varchar(64)  default null,
  extractor_version   varchar(40)  default null,
  prompt_version      varchar(40)  default null,
  model_code          varchar(100) default null,
  status              varchar(20)  not null default 'PENDING',
  step                varchar(40)  default null,
  error_type          varchar(50)  default null,
  error_msg           text         default null,
  entity_count        int          default 0,
  relation_count      int          default 0,
  evidence_count      int          default 0,
  extract_outcome     varchar(30)  default null,
  started_at          timestamp    default null,
  finished_at         timestamp    default null,
  create_time         timestamp    default now()
);
create index if not exists idx_graph_run_doc on kb_graph_run (doc_id, generation desc);
create index if not exists idx_graph_run_kb on kb_graph_run (kb_id);
create index if not exists idx_graph_run_status on kb_graph_run (status);
comment on table kb_graph_run is '知识库图抽取运行记录(generation/run 血统)';

-- Graph TextUnit（与检索 LEAF 解耦；增量见 sql/kb_graph_text_unit.sql）
create table if not exists kb_graph_text_unit (
  text_unit_id        bigint       primary key,
  kb_id               bigint       not null,
  doc_id              bigint       not null,
  ordinal             int          not null default 0,
  content             text         not null,
  heading_path        varchar(500) default null,
  block_type          varchar(40)  default null,
  source_page_from    int          default null,
  source_page_to      int          default null,
  source_label        varchar(500) default null,
  token_count         int          default 0,
  content_hash        varchar(64)  default null,
  parser_version      varchar(20)  default null,
  graph_unit_version  varchar(40)  default null,
  unit_params_hash    varchar(64)  default null,
  generation          bigint       default null,
  run_id              bigint       default null,
  create_time         timestamp    default now()
);
create index if not exists idx_graph_tu_doc on kb_graph_text_unit (doc_id, ordinal);
create index if not exists idx_graph_tu_kb on kb_graph_text_unit (kb_id);

create table if not exists kb_graph_text_unit_chunk (
  text_unit_id bigint not null,
  chunk_id     bigint not null,
  primary key (text_unit_id, chunk_id)
);
create index if not exists idx_graph_tu_chunk on kb_graph_text_unit_chunk (chunk_id);

-- LLM 抽取/摘要缓存(省钱)
create table if not exists kb_llm_cache (
  cache_key   varchar(64)  primary key,
  cache_type  varchar(20)  not null,
  response    text         not null,
  model_code  varchar(100),
  hit_count   int          default 0,
  create_time timestamp    default now()
);
comment on table kb_llm_cache is '知识库 LLM 抽取/摘要缓存';
