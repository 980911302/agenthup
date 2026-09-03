-- AI 对话菜单(挂在 AI 中心 = menu_id 2000 下)
-- 号段 2080(页面) / 2081(查询按钮);可重入:先清理再插入
delete from sys_menu where menu_id between 2080 and 2089;
delete from sys_role_menu where menu_id between 2080 and 2089;

-- 对话页面
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
values (2080, 'AI 对话', 2000, 7, 'chat', 'ai/chat/index', 1, 0, 'C', '0', '0', 'ai:chat:list', 'message', 'admin', sysdate(), 'AI 对话菜单');

-- 查询按钮(进入页面/加载模型列表)
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
values (2081, '对话查询', 2080, 1, '#', '', 1, 0, 'F', '0', '0', 'ai:chat:query', '#', 'admin', sysdate(), '');

-- 给 admin 角色(role_id=1)授权
insert into sys_role_menu (role_id, menu_id) select 1, menu_id from sys_menu where menu_id between 2080 and 2089;
