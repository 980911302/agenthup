-- MCP 服务
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
values (2040, 'MCP 服务', 2000, 5, 'mcpServer', 'ai/mcpServer/index', 1, 0, 'C', '0', '0', 'ai:mcpServer:list', 'server', 'admin', sysdate(), 'MCP 服务菜单');
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
values (2041, 'MCP查询',  2040, 1, '#', '', 1, 0, 'F', '0', '0', 'ai:mcpServer:query',  '#', 'admin', sysdate(), '');
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
values (2042, 'MCP新增',  2040, 2, '#', '', 1, 0, 'F', '0', '0', 'ai:mcpServer:add',    '#', 'admin', sysdate(), '');
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
values (2043, 'MCP修改',  2040, 3, '#', '', 1, 0, 'F', '0', '0', 'ai:mcpServer:edit',   '#', 'admin', sysdate(), '');
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
values (2044, 'MCP删除',  2040, 4, '#', '', 1, 0, 'F', '0', '0', 'ai:mcpServer:remove', '#', 'admin', sysdate(), '');

-- 工具管理
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
values (2050, '工具管理', 2000, 6, 'tool', 'ai/tool/index', 'AiTool', 1, 0, 'C', '0', '0', 'ai:tool:list', 'tool', 'admin', sysdate(), '工具管理菜单');
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
values (2051, '工具查询', 2050, 1, '#', '', 1, 0, 'F', '0', '0', 'ai:tool:query',  '#', 'admin', sysdate(), '');
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
values (2052, '工具新增', 2050, 2, '#', '', 1, 0, 'F', '0', '0', 'ai:tool:add',    '#', 'admin', sysdate(), '');
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
values (2053, '工具修改', 2050, 3, '#', '', 1, 0, 'F', '0', '0', 'ai:tool:edit',   '#', 'admin', sysdate(), '');
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
values (2054, '工具删除', 2050, 4, '#', '', 1, 0, 'F', '0', '0', 'ai:tool:remove', '#', 'admin', sysdate(), '');

-- 给 admin 角色(role_id=1)授权
insert into sys_role_menu (role_id, menu_id) select 1, menu_id from sys_menu where menu_id between 2040 and 2059;
