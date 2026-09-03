-- 结构感知切片 v2：来源定位 + Parent-Child/GraphRAG 血统预留
-- PostgreSQL 增量迁移；执行应用新版本前运行。

alter table kb_chunk add column if not exists source_page_from int default null;
alter table kb_chunk add column if not exists source_page_to int default null;
alter table kb_chunk add column if not exists source_label varchar(500) default null;
alter table kb_chunk add column if not exists chunk_level varchar(20) default 'LEAF';
alter table kb_chunk add column if not exists parent_chunk_id bigint default null;

update kb_chunk set chunk_level = 'LEAF' where chunk_level is null;

create index if not exists idx_chunk_parent on kb_chunk (parent_chunk_id);

comment on column kb_chunk.source_page_from is '来源起始页,1-based';
comment on column kb_chunk.source_page_to is '来源结束页,1-based';
comment on column kb_chunk.source_label is '工作表/幻灯片/结构路径等来源标签';
comment on column kb_chunk.chunk_level is '层级分块类型:LEAF/PARENT/SUMMARY';
comment on column kb_chunk.parent_chunk_id is '父分块ID,为 Parent-Child/GraphRAG 检索预留';
