-- KB-GR-13 图索引版本回滚指针（幂等）
alter table kb_graph_index add column if not exists previous_graph_version varchar(64);
