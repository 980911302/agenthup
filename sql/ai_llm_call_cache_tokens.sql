-- ----------------------------
-- AI Token 计量：ai_llm_call 补缓存命中列
-- 执行前请备份；已有环境用本脚本增量变更（勿 drop 业务表）
--
-- ⚠️ 本脚本只能执行一次
-- alter table ... add column 不具备幂等性，重复执行会报 Duplicate column name。
-- 若不确定是否已执行过，先运行下面的检查语句：
--
--   select column_name from information_schema.columns
--    where table_schema = database()
--      and table_name = 'ai_llm_call'
--      and column_name in ('cache_hit_tokens', 'cache_miss_tokens');
--
--   有结果 = 对应列已存在，请跳过本脚本。
-- ----------------------------

alter table ai_llm_call
  add column cache_hit_tokens  int(11) default 0 comment '输入中命中上游缓存的 token 数' after total_tokens,
  add column cache_miss_tokens int(11) default 0 comment '输入中未命中上游缓存的 token 数' after cache_hit_tokens;
