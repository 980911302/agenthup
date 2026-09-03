-- ============================================================================
-- KB-PV3：历史知识库默认收紧为私有（PostgreSQL，幂等）
-- ============================================================================
-- 背景：早期 ACL 迁移把带 dept_id 的旧库默认设为 DEPT，导致同部门所有用户
-- 都能在知识库列表中看到这些库。现在空范围和新建库都应默认 PRIVATE。
--
-- 本脚本只处理 visibility 仍为空的旧记录；已经显式标记为 DEPT / ORG 的库不会
-- 被修改，以免破坏负责人主动配置的共享范围。

update kb_knowledge
   set visibility = 'PRIVATE'
 where del_flag = '0'
   and visibility is null;

-- 若某次旧版 kb_acl_v2.sql 已将“默认部门可见”写成了 DEPT，请先审计，再由
-- 知识库负责人逐个调整这些记录，避免把真正需要部门共享的知识库误收紧：
--
-- select kb_id, kb_name, owner_user_id, dept_id, create_time
--   from kb_knowledge
--  where del_flag = '0' and visibility = 'DEPT'
--  order by kb_id;
