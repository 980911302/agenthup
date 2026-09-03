-- ============================================================
-- Leiden 层级社区（PostgreSQL 增量，幂等）
-- 见 docs/ai/ai-kb-graphrag-next-plan.md 阶段 4 / KB-GR-08
-- 报告表预建供 KB-GR-09 使用，本阶段只写社区检测结果。
-- ============================================================

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
create index if not exists idx_graph_comm_parent
  on kb_graph_community (kb_id, graph_version, parent_community_id);
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
create index if not exists idx_graph_ent_comm
  on kb_graph_entity_community (kb_id, graph_version, level, community_id);
comment on table kb_graph_entity_community is '实体→社区映射（每层一条）';

-- KB-GR-09 预建：社区报告（本阶段不写业务数据）
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
create index if not exists idx_graph_report_comm
  on kb_graph_community_report (kb_id, graph_version, level, community_id);

create table if not exists kb_graph_community_report_source (
  report_id     bigint not null,
  chunk_id      bigint not null,
  evidence_rank int    default 0,
  primary key (report_id, chunk_id)
);

-- KB-GR-09：社区报告独立向量（不占用 kb_chunk）
create table if not exists kb_community_vector_768 (
  report_id bigint primary key,
  kb_id     bigint not null,
  embedding vector(768) not null
);
create index if not exists idx_kb_comm_vec_768_hnsw
  on kb_community_vector_768 using hnsw (embedding vector_cosine_ops);
create index if not exists idx_kb_comm_vec_768_kb on kb_community_vector_768 (kb_id);

create table if not exists kb_community_vector_1024 (
  report_id bigint primary key,
  kb_id     bigint not null,
  embedding vector(1024) not null
);
create index if not exists idx_kb_comm_vec_1024_hnsw
  on kb_community_vector_1024 using hnsw (embedding vector_cosine_ops);
create index if not exists idx_kb_comm_vec_1024_kb on kb_community_vector_1024 (kb_id);

create table if not exists kb_community_vector_1536 (
  report_id bigint primary key,
  kb_id     bigint not null,
  embedding vector(1536) not null
);
create index if not exists idx_kb_comm_vec_1536_hnsw
  on kb_community_vector_1536 using hnsw (embedding vector_cosine_ops);
create index if not exists idx_kb_comm_vec_1536_kb on kb_community_vector_1536 (kb_id);

create table if not exists kb_community_vector_3072 (
  report_id bigint primary key,
  kb_id     bigint not null,
  embedding vector(3072) not null
);
create index if not exists idx_kb_comm_vec_3072_kb on kb_community_vector_3072 (kb_id);

-- KB-GR-13：图索引 previous 版本指针（幂等）
alter table kb_graph_index add column if not exists previous_graph_version varchar(64);
