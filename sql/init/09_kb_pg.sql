-- ============================================================================
-- 09_kb_pg.sql — 知识库域 PostgreSQL 初始化(合并版)
-- 合并了 kb_pg.sql + kb_acl_v2.sql + kb_index_policy_v1.sql +
-- kb_chunk_v2.sql + kb_graph_index_previous.sql
-- 执行前需: CREATE EXTENSION vector;
-- ============================================================================

create extension if not exists vector;

-- ----------------------------
-- kb_knowledge(知识库主表)
-- ----------------------------
create table if not exists kb_knowledge (
  kb_id                     bigserial    primary key,
  kb_name                   varchar(100) not null,
  description               varchar(500) default null,
  embedding_model_code      varchar(100) default null,
  graph_enabled             char(1)      default '0',
  extract_model_code        varchar(100) default null,
  chunk_strategy            varchar(20)  default 'P',
  chunk_size                int          default 800,
  chunk_overlap             int          default 100,
  -- ACL 扩展(kb_acl_v2.sql)
  owner_user_id             bigint       default null,
  -- 默认私有；部门/全员共享必须由负责人显式选择。
  visibility                varchar(20)  default 'PRIVATE',
  -- 策略版本指针(kb_index_policy_v1.sql)
  active_policy_version_id  bigint       default null,
  desired_policy_version_id bigint       default null,
  previous_policy_version_id bigint      default null,
  index_state               varchar(20)  default 'READY',
  -- 基础字段
  status                    char(1)      default '0',
  create_user_id            bigint       not null,
  dept_id                   bigint       not null,
  create_by                 varchar(64)  default '',
  create_time               timestamp    default null,
  update_by                 varchar(64)  default '',
  update_time               timestamp    default null,
  remark                    varchar(500) default null,
  del_flag                  char(1)      default '0'
);
create index if not exists idx_kb_dept on kb_knowledge (dept_id);
create index if not exists idx_kb_creator on kb_knowledge (create_user_id);
create index if not exists idx_kb_owner on kb_knowledge (owner_user_id);
create index if not exists idx_kb_visibility on kb_knowledge (visibility);
create index if not exists idx_kb_active_policy on kb_knowledge (active_policy_version_id);
create index if not exists idx_kb_index_state on kb_knowledge (index_state);
comment on table kb_knowledge is '知识库表';

-- ----------------------------
-- kb_document(文档表)
-- ----------------------------
create table if not exists kb_document (
  doc_id         bigserial    primary key,
  kb_id          bigint       not null,
  doc_name       varchar(255) not null,
  file_path      varchar(500) not null,
  file_size      bigint       default 0,
  file_type      varchar(32)  default '',
  content_hash   varchar(64)  default null,
  ir_path        varchar(255) default null,
  parse_status   varchar(20)  default 'PENDING',
  parse_step     varchar(40)  default null,
  progress       int          default 0,
  chunk_count    int          default 0,
  error_type     varchar(50)  default null,
  error_stage    varchar(20)  default null,
  error_msg      text         default null,
  parser_version varchar(20)  default null,
  status         char(1)      default '0',
  create_by      varchar(64)  default '',
  create_time    timestamp    default null,
  update_by      varchar(64)  default '',
  update_time    timestamp    default null,
  remark         varchar(500) default null,
  del_flag       char(1)      default '0'
);
create index if not exists idx_kd_kb on kb_document (kb_id);
create index if not exists idx_kd_kb_hash on kb_document (kb_id, content_hash);
comment on table kb_document is '知识库文档表';

-- ----------------------------
-- kb_chunk(分块表,无embedding,向量在kb_vector_*)
-- ----------------------------
create table if not exists kb_chunk (
  chunk_id          bigserial    primary key,
  kb_id             bigint       not null,
  doc_id            bigint       not null,
  chunk_index       int          not null,
  content           text         not null,
  heading_path      varchar(500) default null,
  block_type        varchar(20)  default null,
  token_count       int          default 0,
  embedding_dim     int          default 0,
  chunker_strategy  varchar(20)  default null,
  chunk_params_hash varchar(64)  default null,
  embedding_model   varchar(100) default null,
  source_page_from  int          default null,
  source_page_to    int          default null,
  source_label      varchar(500) default null,
  chunk_level       varchar(20)  default 'LEAF',
  parent_chunk_id   bigint       default null,
  create_time       timestamp    default null
);
create index if not exists idx_chunk_doc on kb_chunk (doc_id);
create index if not exists idx_chunk_kb on kb_chunk (kb_id);
create index if not exists idx_chunk_parent on kb_chunk (parent_chunk_id);
comment on table kb_chunk is '知识库分块表';

-- ----------------------------
-- 向量表(按维度预建,HNSW余弦)
-- ----------------------------
create table if not exists kb_vector_768 (
  chunk_id  bigint primary key,
  kb_id     bigint not null,
  embedding vector(768) not null
);
create index if not exists idx_kb_vec_768_hnsw on kb_vector_768 using hnsw (embedding vector_cosine_ops);
create index if not exists idx_kb_vec_768_kb on kb_vector_768 (kb_id);

create table if not exists kb_vector_1024 (
  chunk_id  bigint primary key,
  kb_id     bigint not null,
  embedding vector(1024) not null
);
create index if not exists idx_kb_vec_1024_hnsw on kb_vector_1024 using hnsw (embedding vector_cosine_ops);
create index if not exists idx_kb_vec_1024_kb on kb_vector_1024 (kb_id);

create table if not exists kb_vector_1536 (
  chunk_id  bigint primary key,
  kb_id     bigint not null,
  embedding vector(1536) not null
);
create index if not exists idx_kb_vec_1536_hnsw on kb_vector_1536 using hnsw (embedding vector_cosine_ops);
create index if not exists idx_kb_vec_1536_kb on kb_vector_1536 (kb_id);

create table if not exists kb_vector_3072 (
  chunk_id  bigint primary key,
  kb_id     bigint not null,
  embedding vector(3072) not null
);
-- pgvector HNSW上限2000维,3072只能全表扫(带kb_id过滤仍可用)
create index if not exists idx_kb_vec_3072_kb on kb_vector_3072 (kb_id);

-- ----------------------------
-- kb_acl_member(成员ACL)
-- ----------------------------
create table if not exists kb_acl_member (
  id          bigserial primary key,
  kb_id       bigint       not null,
  user_id     bigint       not null,
  role        varchar(20)  not null default 'VIEWER',
  create_by   varchar(64)  default '',
  create_time timestamp    default current_timestamp,
  constraint uk_kb_acl_member unique (kb_id, user_id)
);
create index if not exists idx_kb_acl_kb on kb_acl_member (kb_id);
create index if not exists idx_kb_acl_user on kb_acl_member (user_id);
comment on table kb_acl_member is '知识库成员ACL:VIEWER/EDITOR/QUALITY/OWNER';

-- ----------------------------
-- kb_index_policy_version(策略版本,不可变)
-- ----------------------------
create table if not exists kb_index_policy_version (
  version_id      bigserial primary key,
  version_no      int          not null,
  version_label   varchar(64)  default null,
  status          varchar(20)  not null default 'DRAFT',
  payload_json    text         not null,
  fingerprint     varchar(128) default null,
  check_report    text         default null,
  published_by    varchar(64)  default '',
  published_at    timestamp    default null,
  create_by       varchar(64)  default '',
  create_time     timestamp    default current_timestamp,
  remark          varchar(500) default null
);
create unique index if not exists uk_kb_policy_ver_no on kb_index_policy_version (version_no);
create index if not exists idx_kb_policy_status on kb_index_policy_version (status);

-- ----------------------------
-- kb_index_policy(平台当前策略指针,单行)
-- ----------------------------
create table if not exists kb_index_policy (
  id                     smallint primary key default 1 check (id = 1),
  draft_payload_json     text         default null,
  published_version_id   bigint       default null,
  max_concurrent_jobs    int          default 2,
  update_by              varchar(64)  default '',
  update_time            timestamp    default current_timestamp
);
insert into kb_index_policy (id, max_concurrent_jobs)
values (1, 2)
on conflict (id) do nothing;

-- ----------------------------
-- kb_index_job(索引/升级任务)
-- ----------------------------
create table if not exists kb_index_job (
  job_id              bigserial primary key,
  kb_id               bigint       not null,
  job_type            varchar(20)  not null,
  from_version_id     bigint       default null,
  to_version_id       bigint       default null,
  status              varchar(20)  not null default 'PENDING',
  progress            int          default 0,
  doc_total           int          default 0,
  doc_done            int          default 0,
  error_msg           text         default null,
  impact_json         text         default null,
  create_by           varchar(64)  default '',
  create_time         timestamp    default current_timestamp,
  started_at          timestamp    default null,
  finished_at         timestamp    default null
);
create index if not exists idx_kb_job_kb on kb_index_job (kb_id);
create index if not exists idx_kb_job_status on kb_index_job (status);

-- ----------------------------
-- 图谱相关表
-- ----------------------------
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
create index if not exists idx_graph_tu_run on kb_graph_text_unit (run_id);

create table if not exists kb_graph_text_unit_chunk (
  text_unit_id bigint not null,
  chunk_id     bigint not null,
  primary key (text_unit_id, chunk_id)
);
create index if not exists idx_graph_tu_chunk on kb_graph_text_unit_chunk (chunk_id);

create table if not exists kb_llm_cache (
  cache_key   varchar(64)  primary key,
  cache_type  varchar(20)  not null,
  response    text         not null,
  model_code  varchar(100),
  hit_count   int          default 0,
  create_time timestamp    default now()
);

-- ----------------------------
-- 图索引/社区(Leiden)
-- ----------------------------
create table if not exists kb_graph_index (
  kb_id              bigint       primary key,
  graph_version      varchar(64)  default null,
  previous_graph_version varchar(64) default null,
  status             varchar(20)  not null default 'IDLE',
  step               varchar(40)  default null,
  entity_count       int          default 0,
  relation_count     int          default 0,
  community_count    int          default 0,
  level_count        int          default 0,
  extractor_version  varchar(40)  default null,
  community_version  varchar(40)  default null,
  report_version     varchar(40)  default null,
  gds_available      char(1)      default '0',
  gds_version        varchar(40)  default null,
  dirty_at           timestamp    default null,
  started_at         timestamp    default null,
  finished_at        timestamp    default null,
  error_type         varchar(50)  default null,
  error_msg          text         default null
);
comment on table kb_graph_index is '知识库图索引/社区任务状态';

create table if not exists kb_graph_community (
  kb_id               bigint       not null,
  graph_version       varchar(64)  not null,
  level               int          not null,
  community_id        bigint       not null,
  parent_community_id bigint       default null,
  rank                int          default 0,
  entity_count        int          default 0,
  relation_count      int          default 0,
  source_chunk_count  int          default 0,
  content_hash        varchar(64)  default null,
  primary key (kb_id, graph_version, level, community_id)
);
create index if not exists idx_graph_comm_parent on kb_graph_community (kb_id, graph_version, parent_community_id);
create index if not exists idx_graph_comm_kb on kb_graph_community (kb_id);
comment on table kb_graph_community is '层级社区节点';

create table if not exists kb_graph_entity_community (
  kb_id         bigint       not null,
  graph_version varchar(64)  not null,
  level         int          not null,
  community_id  bigint       not null,
  entity_key    varchar(500) not null,
  entity_name   varchar(500) default null,
  primary key (kb_id, graph_version, level, entity_key)
);
create index if not exists idx_graph_ent_comm on kb_graph_entity_community (kb_id, graph_version, level, community_id);
comment on table kb_graph_entity_community is '实体→社区映射(每层一条)';

create table if not exists kb_graph_community_report (
  report_id       bigint       primary key,
  kb_id           bigint       not null,
  graph_version   varchar(64)  not null,
  level           int          not null,
  community_id    bigint       not null,
  title           varchar(500) default null,
  summary         text         default null,
  full_content    text         default null,
  findings_json   text         default null,
  source_count    int          default 0,
  token_count     int          default 0,
  model_code      varchar(100) default null,
  prompt_version  varchar(40)  default null,
  content_hash    varchar(64)  default null,
  status          varchar(20)  default 'PENDING',
  create_time     timestamp    default now()
);
create index if not exists idx_graph_report_comm on kb_graph_community_report (kb_id, graph_version, level, community_id);

create table if not exists kb_graph_community_report_source (
  report_id     bigint not null,
  chunk_id      bigint not null,
  evidence_rank int    default 0,
  primary key (report_id, chunk_id)
);

-- ----------------------------
-- 社区报告向量(按维度)
-- ----------------------------
create table if not exists kb_community_vector_768 (
  report_id bigint primary key,
  kb_id     bigint not null,
  embedding vector(768) not null
);
create index if not exists idx_kb_comm_vec_768_hnsw on kb_community_vector_768 using hnsw (embedding vector_cosine_ops);
create index if not exists idx_kb_comm_vec_768_kb on kb_community_vector_768 (kb_id);

create table if not exists kb_community_vector_1024 (
  report_id bigint primary key,
  kb_id     bigint not null,
  embedding vector(1024) not null
);
create index if not exists idx_kb_comm_vec_1024_hnsw on kb_community_vector_1024 using hnsw (embedding vector_cosine_ops);
create index if not exists idx_kb_comm_vec_1024_kb on kb_community_vector_1024 (kb_id);

create table if not exists kb_community_vector_1536 (
  report_id bigint primary key,
  kb_id     bigint not null,
  embedding vector(1536) not null
);
create index if not exists idx_kb_comm_vec_1536_hnsw on kb_community_vector_1536 using hnsw (embedding vector_cosine_ops);
create index if not exists idx_kb_comm_vec_1536_kb on kb_community_vector_1536 (kb_id);

create table if not exists kb_community_vector_3072 (
  report_id bigint primary key,
  kb_id     bigint not null,
  embedding vector(3072) not null
);
create index if not exists idx_kb_comm_vec_3072_kb on kb_community_vector_3072 (kb_id);
