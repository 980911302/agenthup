-- =====================================================================
-- 11_mem_pg.sql — 跨会话长期记忆 向量表(PostgreSQL + pgvector)
--
-- 照抄 kb_vector_* 的分表范式:按 embedding 维度路由到预建表。
-- 维度不配置,运行时从 embedding.length 取;不在 {768,1024,1536,3072} 直接抛。
-- 无 status 列:supersede 时直接删向量行,检索天然只见 active,历史留在 MySQL 台账。
-- 分层:user_id 永远强制;agent_id=0 用户层;检索一次查两层 where user_id=? and agent_id in (0, ?)。
-- =====================================================================

create table if not exists mem_vector_768 (
  memory_id bigint primary key,
  user_id   bigint not null,
  agent_id  bigint not null,
  embedding vector(768) not null
);
create index if not exists idx_mem_vec_768_hnsw on mem_vector_768 using hnsw (embedding vector_cosine_ops);
create index if not exists idx_mem_vec_768_tenant on mem_vector_768 (user_id, agent_id);

create table if not exists mem_vector_1024 (
  memory_id bigint primary key,
  user_id   bigint not null,
  agent_id  bigint not null,
  embedding vector(1024) not null
);
create index if not exists idx_mem_vec_1024_hnsw on mem_vector_1024 using hnsw (embedding vector_cosine_ops);
create index if not exists idx_mem_vec_1024_tenant on mem_vector_1024 (user_id, agent_id);

create table if not exists mem_vector_1536 (
  memory_id bigint primary key,
  user_id   bigint not null,
  agent_id  bigint not null,
  embedding vector(1536) not null
);
create index if not exists idx_mem_vec_1536_hnsw on mem_vector_1536 using hnsw (embedding vector_cosine_ops);
create index if not exists idx_mem_vec_1536_tenant on mem_vector_1536 (user_id, agent_id);

create table if not exists mem_vector_3072 (
  memory_id bigint primary key,
  user_id   bigint not null,
  agent_id  bigint not null,
  embedding vector(3072) not null
);
-- pgvector HNSW 上限 2000 维,3072 只能全表扫(带 user_id/agent_id 过滤仍可控,同 KB)
create index if not exists idx_mem_vec_3072_tenant on mem_vector_3072 (user_id, agent_id);
