-- ============================================================================
-- ai_chat_session.message_count 存量回填
--
-- 该列在 ai_chat_context.sql 里随 context_length 一起加上,但从未进入 ORM 层:
-- AiChatSession 实体没有对应字段,resultMap 没有映射,全代码零引用,因此自建表
-- 起一直恒为 0。查库的人会把它当成「这个会话没有消息」,是个持续误导。
--
-- 代码侧已在 ChatMessageRecorder.persist() 统一累加(消息落表的唯一出口),
-- 本脚本按 ai_chat_message 实际行数回填历史数据,之后两者保持一致。
--
-- 幂等:重复执行结果相同(整列按实际行数重算,不是增量)。
-- ============================================================================

update ai_chat_session s
   set s.message_count = (
       select count(*) from ai_chat_message m where m.session_id = s.session_id
   );

-- 校验:应返回 0 行(即不存在计数与实际行数不一致的会话)
-- select s.session_id, s.message_count,
--        (select count(*) from ai_chat_message m where m.session_id = s.session_id) actual
--   from ai_chat_session s
--  having s.message_count <> actual;
