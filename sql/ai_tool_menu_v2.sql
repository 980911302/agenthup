-- 替换工具管理菜单权限:删 add/edit/remove,加 sync
delete from sys_menu where menu_id between 2050 and 2059;
delete from sys_role_menu where menu_id between 2050 and 2059;

insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
values (2050, '工具管理', 2000, 6, 'tool', 'ai/tool/index', 'AiTool', 1, 0, 'C', '0', '0', 'ai:tool:list', 'tool', 'admin', sysdate(), '工具管理菜单');
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
values (2051, '工具查询', 2050, 1, '#', '', 1, 0, 'F', '0', '0', 'ai:tool:query',  '#', 'admin', sysdate(), '');
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
values (2052, '同步工具', 2050, 2, '#', '', 1, 0, 'F', '0', '0', 'ai:tool:sync',   '#', 'admin', sysdate(), '');
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
values (2053, '启停工具', 2050, 3, '#', '', 1, 0, 'F', '0', '0', 'ai:tool:status', '#', 'admin', sysdate(), '');

insert into sys_role_menu (role_id, menu_id) select 1, menu_id from sys_menu where menu_id between 2050 and 2059;
