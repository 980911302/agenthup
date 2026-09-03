-- ============================================================
-- KB-PV2-10：版本化知识引擎策略与升级任务（PostgreSQL，幂等）
-- ============================================================

-- 策略版本（不可变：发布后不更新 payload）
create table if not exists kb_index_policy_version (
  version_id      bigserial primary key,
  version_no      int          not null,
  version_label   varchar(64)  default null,
  status          varchar(20)  not null default 'DRAFT',
  -- payload: embeddingModel/extractModel/chunkStrategy/chunkSize/chunkOverlap/graphEnabled
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

-- 平台当前指针（单行）
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

-- 知识库绑定策略版本
alter table kb_knowledge add column if not exists active_policy_version_id bigint;
alter table kb_knowledge add column if not exists desired_policy_version_id bigint;
alter table kb_knowledge add column if not exists previous_policy_version_id bigint;
alter table kb_knowledge add column if not exists index_state varchar(20) default 'READY';
comment on column kb_knowledge.active_policy_version_id is '当前生效策略版本';
comment on column kb_knowledge.desired_policy_version_id is '目标策略版本（升级中）';
comment on column kb_knowledge.previous_policy_version_id is '上一成功版本（回滚点）';
comment on column kb_knowledge.index_state is 'READY/UPGRADING/FAILED/STALE';

create index if not exists idx_kb_active_policy on kb_knowledge (active_policy_version_id);
create index if not exists idx_kb_index_state on kb_knowledge (index_state);

-- 索引任务
create table if not exists kb_index_job (
  job_id              bigserial primary key,
  kb_id               bigint       not null,
  job_type            varchar(20)  not null,
  -- INITIAL / UPGRADE / REBUILD / ROLLBACK
  from_version_id     bigint       default null,
  to_version_id       bigint       default null,
  status              varchar(20)  not null default 'PENDING',
  -- PENDING / RUNNING / SUCCEEDED / FAILED / CANCELLED
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
