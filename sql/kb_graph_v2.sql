-- ============================================================
-- 知识图谱证据血统 v2（PostgreSQL 增量，幂等）
-- 见 docs/ai/ai-kb-graph-provenance-v2.md / KB-GR-02
-- 本迁移只加表与列，不切换生产图写路径。
-- ============================================================

-- kb_doc_graph：当前生效 run 与代数
alter table kb_doc_graph add column if not exists active_run_id bigint default null;
alter table kb_doc_graph add column if not exists generation bigint default 0;
alter table kb_doc_graph add column if not exists graph_version varchar(64) default null;

comment on column kb_doc_graph.active_run_id is '当前生效的 kb_graph_run.run_id';
comment on column kb_doc_graph.generation is '文档图代数,单调递增;旧 generation 禁止覆盖';
comment on column kb_doc_graph.graph_version is '解析/切片/抽取/prompt/model 指纹摘要';

update kb_doc_graph set generation = 0 where generation is null;

-- 单次图抽取运行
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
comment on column kb_graph_run.generation is '本次运行代数';
comment on column kb_graph_run.status is 'PENDING/RUNNING/SUCCESS/FAILED/SUPERSEDED';
comment on column kb_graph_run.extract_outcome is 'SUCCESS/VALID_EMPTY/LLM_FAILED/PARSE_FAILED/VALIDATION_FAILED';

-- 校验（人工执行，可重复）
-- select column_name from information_schema.columns
--  where table_name = 'kb_doc_graph'
--    and column_name in ('active_run_id', 'generation', 'graph_version');
-- select to_regclass('public.kb_graph_run');
