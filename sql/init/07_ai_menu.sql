-- ============================================================================
-- 07_ai_menu.sql — AI 模块菜单初始化(合并版)
-- 合并了 ai_chat_menu.sql、ai_skill_menu.sql、ai_tool_menu_v2.sql、
-- ai_mcp_tool_menu.sql、ai_model_import_menu.sql、ai_job.sql(菜单部分)、kb_menu.sql
-- 前提:ry_20260417.sql 已执行,AI 中心菜单(menu_id=2000)已存在
-- ============================================================================

-- 清理旧菜单(幂等)。2100-2120 是 ai_menu_regroup 的目录号段,这里不能动。
delete from sys_role_menu where menu_id between 2030 and 2099;
delete from sys_menu where menu_id between 2030 and 2099;
delete from sys_role_menu where menu_id between 2130 and 2149;
delete from sys_menu where menu_id between 2130 and 2149;

-- ----------------------------
-- 技能管理(2030-2039)
-- ----------------------------
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

-- ----------------------------
-- MCP 服务(2040-2049)
-- ----------------------------
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

-- ----------------------------
-- 工具管理(2050-2059)
-- ----------------------------
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
values (2050, '工具管理', 2000, 6, 'tool', 'ai/tool/index', 'AiTool', 1, 0, 'C', '0', '0', 'ai:tool:list', 'tool', 'admin', sysdate(), '工具管理菜单');
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
values (2051, '工具查询', 2050, 1, '#', '', 1, 0, 'F', '0', '0', 'ai:tool:query',  '#', 'admin', sysdate(), '');
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
values (2052, '同步工具', 2050, 2, '#', '', 1, 0, 'F', '0', '0', 'ai:tool:sync',   '#', 'admin', sysdate(), '');
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
values (2053, '启停工具', 2050, 3, '#', '', 1, 0, 'F', '0', '0', 'ai:tool:status', '#', 'admin', sysdate(), '');

-- ----------------------------
-- 知识库管理(2070-2079)
-- ----------------------------
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

-- ----------------------------
-- AI 对话(2080-2089)
-- ----------------------------
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
values (2080, 'AI 对话', 2000, 8, 'chat', 'ai/chat/index', 1, 0, 'C', '0', '0', 'ai:chat:list', 'message', 'admin', sysdate(), 'AI 对话菜单');
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
values (2081, '对话查询', 2080, 1, '#', '', 1, 0, 'F', '0', '0', 'ai:chat:query', '#', 'admin', sysdate(), '');

-- ----------------------------
-- 定时任务(2083-2088)
-- ----------------------------
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
values (2083, '定时任务', 2000, 9, 'job', 'ai/job/index', 'AiJob', 1, 0, 'C', '0', '0', 'ai:job:list', 'job', 'admin', sysdate(), '智能体定时任务');
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
values (2084, '任务查询', 2083, 1, '#', '', 1, 0, 'F', '0', '0', 'ai:job:query', '#', 'admin', sysdate(), '');
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
values (2085, '任务新增', 2083, 2, '#', '', 1, 0, 'F', '0', '0', 'ai:job:add', '#', 'admin', sysdate(), '');
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
values (2086, '任务修改', 2083, 3, '#', '', 1, 0, 'F', '0', '0', 'ai:job:edit', '#', 'admin', sysdate(), '');
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
values (2087, '任务删除', 2083, 4, '#', '', 1, 0, 'F', '0', '0', 'ai:job:remove', '#', 'admin', sysdate(), '');
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
values (2088, '任务启停', 2083, 5, '#', '', 1, 0, 'F', '0', '0', 'ai:job:changeStatus', '#', 'admin', sysdate(), '');

-- ----------------------------
-- 长期记忆管理(2090-2092，仅管理员)
-- ----------------------------
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
values (2090, '长期记忆', 2000, 10, 'memory', 'ai/memory/index', 1, 0, 'C', '0', '0', 'ai:memory:list', 'documentation', 'admin', sysdate(), '管理员查看与删除跨会话长期记忆');
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
values (2091, '长期记忆查询', 2090, 1, '#', '', 1, 0, 'F', '0', '0', 'ai:memory:query', '#', 'admin', sysdate(), '');
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
values (2092, '长期记忆删除', 2090, 2, '#', '', 1, 0, 'F', '0', '0', 'ai:memory:remove', '#', 'admin', sysdate(), '同步清理关联的 PostgreSQL 向量');

-- ----------------------------
-- 模型导入按钮(动态查找父菜单)
-- ----------------------------
insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
select '模型导入', menu_id, 5, '#', '', 1, 0, 'F', '0', '0', 'ai:model:import', '#', 'admin', sysdate(), '模型导入按钮'
from sys_menu where perms = 'ai:model:list' and menu_type = 'C';

-- ----------------------------
-- 给 admin 角色(role_id=1)授权
-- ----------------------------
insert into sys_role_menu (role_id, menu_id) select 1, menu_id from sys_menu where menu_id between 2030 and 2099;
insert into sys_role_menu (role_id, menu_id) select 1, menu_id from sys_menu where perms = 'ai:model:import';
