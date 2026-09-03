-- ============================================================================
-- 清理零引用的表与列
--
-- 判定依据:导出全库 428 个 ai_* 列,与 Java / XML / Vue / JS 源码交叉比对,
-- 以下对象在整个代码库中零引用。
--
-- 【一】ai_plan* 五张表 —— AI 任务编排看板(plan → stage → item)
--   三样东西同时缺席:代码零引用、sql/ 无建表脚本、docs/AI业务表结构.md 未收录。
--   表内仅有 2026-08-19~20 的测试数据(「测试」「奥特曼网站」),此后未再写入。
--   按 sql/init/ 建的新库本就没有这几张表,删除后开发库与新库一致。
--   完整备份(建表 + 数据):sql/backup/ai_plan_tables_backup.sql —— 该目录已
--   gitignore,只在本地;执行那个文件即可原样还原。
--
-- 【二】ai_chat_session_agent.tool_call_count
--   与 ai_chat_session.message_count 同类:建表时随 turn_count 一起加上,
--   却从未进入 ORM 层。addAgentTokenDetail 的 SET 列表里没有它,29 行数据
--   合计恒为 0,而旁边 llm_call_count=436、turn_count=91 都在正常累加。
--   与 message_count 的处理不同 —— 那个补了维护(会话消息数是有意义的聚合),
--   这个直接删:工具调用次数随时可从 ai_chat_message 按 tool_name 非空算出,
--   没必要为一个无人读取的冗余计数付出每次工具调用一次 UPDATE 的代价。
--
-- 幂等:重复执行安全(IF EXISTS)。
-- ============================================================================

-- 【一】删除任务编排看板遗留表(先删子表,虽然未必有外键约束)
drop table if exists ai_plan_item_event;
drop table if exists ai_plan_item_run;
drop table if exists ai_plan_item;
drop table if exists ai_plan_stage;
drop table if exists ai_plan;

-- 【二】删除零引用的计数列
-- MySQL 8.0 不支持 drop column if exists,先查再执行:
--   select count(*) from information_schema.columns
--    where table_schema = database() and table_name = 'ai_chat_session_agent'
--      and column_name = 'tool_call_count';
alter table ai_chat_session_agent drop column tool_call_count;

-- 校验:以下查询应全部返回空
--   show tables like 'ai_plan%';
--   select column_name from information_schema.columns
--    where table_schema = database() and table_name = 'ai_chat_session_agent'
--      and column_name = 'tool_call_count';
