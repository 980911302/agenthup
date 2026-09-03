-- ============================================================
-- Graph TextUnit 解耦（PostgreSQL 增量，幂等）
-- 见 docs/ai/ai-kb-graphrag-next-plan.md 阶段 2 / KB-GR-05
-- 调整 graph-unit 参数只重建图谱，不重算向量。
-- ============================================================

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

comment on table kb_graph_text_unit is '图抽取 TextUnit（与检索 LEAF 解耦）';
comment on column kb_graph_text_unit.graph_unit_version is '图单元切分版本，独立于 embedding/chunk 参数';
comment on column kb_graph_text_unit.unit_params_hash is 'size/overlap 等参数指纹';

create table if not exists kb_graph_text_unit_chunk (
  text_unit_id bigint not null,
  chunk_id     bigint not null,
  primary key (text_unit_id, chunk_id)
);

create index if not exists idx_graph_tu_chunk on kb_graph_text_unit_chunk (chunk_id);

comment on table kb_graph_text_unit_chunk is 'TextUnit → LEAF chunk 多对多映射';
