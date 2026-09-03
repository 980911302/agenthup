-- 技能管理菜单(挂在 AI 中心 = menu_id 2000 下)
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
values (2030, '技能管理', 2000, 4, 'skill', 'ai/skill/index', 1, 0, 'C', '0', '0', 'ai:skill:list', 'skill', 'admin', sysdate(), '技能管理菜单');
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
values (2031, '技能查询', 2030, 1, '#', '', 1, 0, 'F', '0', '0', 'ai:skill:query',  '#', 'admin', sysdate(), '');
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
values (2032, '技能新增', 2030, 2, '#', '', 1, 0, 'F', '0', '0', 'ai:skill:add',    '#', 'admin', sysdate(), '');
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
values (2033, '技能修改', 2030, 3, '#', '', 1, 0, 'F', '0', '0', 'ai:skill:edit',   '#', 'admin', sysdate(), '');
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
values (2034, '技能删除', 2030, 4, '#', '', 1, 0, 'F', '0', '0', 'ai:skill:remove', '#', 'admin', sysdate(), '');

-- 给 admin 角色(role_id=1)授权
insert into sys_role_menu (role_id, menu_id) select 1, menu_id from sys_menu where menu_id between 2030 and 2039;
