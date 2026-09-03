-- --------------------------------------------
-- 清理孤儿统计明细：删除"已删除会话"残留的首页统计数据
-- --------------------------------------------
-- 背景：
--   删除会话时若未同时清理 ai_llm_call / ai_chat_run / ai_chat_message，
--   这些明细行会永久残留，导致首页统计(/ai/stat/*)把已删除会话计入。
--   本脚本只删除"会话已不存在或已 del_flag='2'"的孤儿行，
--   保留仍有效会话(session del_flag!='2')的真实统计数据。
--
-- 使用前先执行下面的 SELECT 预览将要删除的行数，确认无误再执行 DELETE。
-- 建议先做一次数据库备份。
-- --------------------------------------------

-- ① 预览：将要删除的孤儿调用明细行数
SELECT COUNT(*) AS llm_call_orphan_rows
FROM ai_llm_call c
LEFT JOIN ai_chat_session s ON c.session_id = s.session_id
WHERE s.session_id IS NULL OR s.del_flag = '2';

-- ② 预览：将要删除的孤儿运行记录行数
SELECT COUNT(*) AS chat_run_orphan_rows
FROM ai_chat_run c
LEFT JOIN ai_chat_session s ON c.session_id = s.session_id
WHERE s.session_id IS NULL OR s.del_flag = '2';

-- ③ 预览：将要删除的孤儿消息行数
SELECT COUNT(*) AS chat_message_orphan_rows
FROM ai_chat_message c
LEFT JOIN ai_chat_session s ON c.session_id = s.session_id
WHERE s.session_id IS NULL OR s.del_flag = '2';

-- -------------------- 确认后执行清理（建议在事务中） --------------------
START TRANSACTION;

DELETE FROM ai_llm_call
WHERE session_id IN (
    SELECT session_id FROM ai_chat_session WHERE del_flag = '2'
)
   OR session_id NOT IN (SELECT session_id FROM ai_chat_session);

DELETE FROM ai_chat_run
WHERE session_id IN (
    SELECT session_id FROM ai_chat_session WHERE del_flag = '2'
)
   OR session_id NOT IN (SELECT session_id FROM ai_chat_session);

DELETE FROM ai_chat_message
WHERE session_id IN (
    SELECT session_id FROM ai_chat_session WHERE del_flag = '2'
)
   OR session_id NOT IN (SELECT session_id FROM ai_chat_session);

-- 提交前核对删除后的剩余量（应只剩有效会话的数据）
SELECT '剩余 llm_call' k, COUNT(*) v FROM ai_llm_call
UNION ALL SELECT '剩余 chat_run', COUNT(*) FROM ai_chat_run
UNION ALL SELECT '剩余 chat_message', COUNT(*) FROM ai_chat_message;

COMMIT;