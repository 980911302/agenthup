-- 可选:把历史上由 MySQL current_timestamp(UTC) 写入的 ai_chat_run 时间拨到 GMT+8,
-- 与 ai_llm_call.create_time 对齐。先备份,先 SELECT 确认行数。
-- 把 <CUTOVER> 换成本次上线时间(GMT+8)。

-- SELECT COUNT(*) FROM ai_chat_run WHERE create_time < '<CUTOVER>';

UPDATE ai_chat_run
   SET started_time   = DATE_ADD(started_time,   INTERVAL 8 HOUR),
       finished_time  = DATE_ADD(finished_time,  INTERVAL 8 HOUR),
       create_time    = DATE_ADD(create_time,    INTERVAL 8 HOUR),
       update_time    = DATE_ADD(update_time,    INTERVAL 8 HOUR)
 WHERE create_time < '<CUTOVER>';
