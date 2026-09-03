-- =====================================================================
-- ai_memory_extract_progress.sql — 记忆提炼进度表(MySQL 增量)
--
-- 空闲会话兜底提炼(IdleSessionExtractScheduler)的提炼位点。
-- 压缩走 SUMMARY 行的 summary_to_id 当边界;兜底扫描没有压缩,需要一个独立位点
-- 记录「该会话记忆提炼到哪一条 message_id」,避免重复提炼同一段历史。
--
-- 极简表,不做软删:会话删除时整行一并清掉(不保留意义)。主键就是 session_id。
-- 提炼是旁路,位点丢失最坏是重提炼一遍,由提炼器的 hash/相似度去重兜底。
--
-- 执行应用新版本前运行;全新安装见 sql/init/12_ai_memory_extract_progress.sql。
-- =====================================================================

drop table if exists ai_memory_extract_progress;

create table ai_memory_extract_progress (
  session_id           varchar(64)   not null comment '会话ID(主键)',
  agent_id             bigint(20)    not null comment '主智能体ID(提炼一律记到主agent名下,spec §8.5)',
  user_id              bigint(20)    not null comment '发起用户ID(隔离维度,永远强制)',
  extract_to_message_id bigint(20)   default null comment '已提炼到哪条message_id(下次从它之后开始)',
  update_time          datetime      default null comment '最近一次提炼时间',
  primary key (session_id)
) engine=InnoDB comment='记忆提炼进度(空闲兜底扫描位点)';
