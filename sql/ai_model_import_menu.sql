-- 模型导入按钮权限(挂在「模型管理」菜单下,parent 按 perms 动态查找,无需关心具体 menu_id)
insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
select '模型导入', menu_id, 5, '#', '', 1, 0, 'F', '0', '0', 'ai:model:import', '#', 'admin', sysdate(), '模型导入按钮'
from sys_menu where perms = 'ai:model:list' and menu_type = 'C';

-- 给 admin 角色(role_id=1)授权
insert into sys_role_menu (role_id, menu_id) select 1, menu_id from sys_menu where perms = 'ai:model:import';
