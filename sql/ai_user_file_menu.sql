-- ----------------------------
-- 个人文件管理菜单与权限。仅授予管理员角色；可重复执行。
--
-- 挂在 2110「能力」下，与知识库管理(2070)、长期记忆(2090)同级 —— 都是「用户数据的
-- 管理视图」这一类。
--
-- ⚠️ 这里的权限点 ai:userfile:* 只作用于后台的 AiUserFileAdminController(/ai/userfile)。
--    desktop 走的是 C 端 AiUserFileController(/ai/files)，它没有权限点，靠 SQL 里的
--    user_id 约束保证只能碰自己的文件，两者不要混。
--
-- menu_id 取 2130-2132：执行前库里 max(menu_id)=2120，这一段是空的。
-- ----------------------------

DELETE FROM sys_role_menu WHERE menu_id IN (2130, 2131, 2132);
DELETE FROM sys_menu WHERE menu_id IN (2130, 2131, 2132);

INSERT INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache,
   menu_type, visible, status, perms, icon, create_by, create_time, remark)
VALUES
  (2130, '个人文件', 2110, 6, 'userfile', 'ai/userfile/index', 1, 0,
   'C', '0', '0', 'ai:userfile:list', 'documentation', 'admin', sysdate(),
   '管理员查看各用户个人文件空间占用并清理文件'),
  (2131, '个人文件查询', 2130, 1, '#', '', 1, 0,
   'F', '0', '0', 'ai:userfile:query', '#', 'admin', sysdate(), ''),
  (2132, '个人文件删除', 2130, 2, '#', '', 1, 0,
   'F', '0', '0', 'ai:userfile:remove', '#', 'admin', sysdate(),
   '软删台账并清理不再被引用的对象存储文件');

INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES
  (1, 2130), (1, 2131), (1, 2132);
