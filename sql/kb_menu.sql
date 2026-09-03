-- 知识库管理菜单(挂在 AI 中心 = menu_id 2000 下)
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
values (2070, '知识库管理', 2000, 7, 'kb', 'ai/kb/index', 1, 0, 'C', '0', '0', 'ai:kb:list', 'book', 'admin', sysdate(), '知识库管理菜单');
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
values (2071, '知识库查询', 2070, 1, '#', '', 1, 0, 'F', '0', '0', 'ai:kb:query',  '#', 'admin', sysdate(), '');
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
values (2072, '知识库新增', 2070, 2, '#', '', 1, 0, 'F', '0', '0', 'ai:kb:add',    '#', 'admin', sysdate(), '');
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
values (2073, '知识库修改', 2070, 3, '#', '', 1, 0, 'F', '0', '0', 'ai:kb:edit',   '#', 'admin', sysdate(), '');
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
values (2074, '知识库删除', 2070, 4, '#', '', 1, 0, 'F', '0', '0', 'ai:kb:remove', '#', 'admin', sysdate(), '');

-- 给 admin 角色(role_id=1)授权
insert into sys_role_menu (role_id, menu_id) select 1, menu_id from sys_menu where menu_id between 2070 and 2079;