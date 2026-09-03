-- =====================================================================
-- 13_ai_memory_extract_backoff.sql — 记忆提炼失败退避(增量 ALTER)
--
-- 已建过库的环境执行本脚本;新库直接用 sql/init/12_ai_memory_extract_progress.sql。
-- 执行顺序:必须先跑 sql/ai_memory_extract_progress.sql 建出表,再跑本脚本加列。
--
-- 背景:提炼失败(LLM 超时/畸形输出/取不到模型)时位点不推进、下轮重试。原实现没有
-- 失败计数也没有退避,稳定失败的会话会每 5 分钟重试到永远;而候选是
-- order by update_time asc limit N,这些卡住的老会话会永久占据名额前排,
-- 把新的待提炼会话挤出候选 —— 队头阻塞。
-- =====================================================================

alter table ai_memory_extract_progress
  add column fail_count      int(11)  default 0    comment '连续提炼失败次数(成功即清零;达上限后不再进候选)' after extract_to_message_id,
  add column next_retry_time datetime default null comment '下次可重试时间(指数退避;为空表示随时可试)' after fail_count;

alter table ai_memory_extract_progress
  add index idx_mem_extract_retry (next_retry_time, fail_count);
