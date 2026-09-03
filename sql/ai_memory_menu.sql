-- 长期记忆管理菜单与权限。仅授予管理员角色；可重复执行。
-- 删除接口会先清理 PostgreSQL mem_vector_* 中的关联向量，再软删 MySQL ai_memory 台账。

DELETE FROM sys_role_menu WHERE menu_id IN (2090, 2091, 2092);
DELETE FROM sys_menu WHERE menu_id IN (2090, 2091, 2092);

INSERT INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache,
   menu_type, visible, status, perms, icon, create_by, create_time, remark)
VALUES
  (2090, '长期记忆', 2110, 5, 'memory', 'ai/memory/index', 1, 0,
   'C', '0', '0', 'ai:memory:list', 'documentation', 'admin', sysdate(),
   '管理员查看与删除跨会话长期记忆'),
  (2091, '长期记忆查询', 2090, 1, '#', '', 1, 0,
   'F', '0', '0', 'ai:memory:query', '#', 'admin', sysdate(), ''),
  (2092, '长期记忆删除', 2090, 2, '#', '', 1, 0,
   'F', '0', '0', 'ai:memory:remove', '#', 'admin', sysdate(),
   '同步清理关联的 PostgreSQL 向量');

INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES
  (1, 2090), (1, 2091), (1, 2092);
