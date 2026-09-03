-- 修复 AI「工具管理 / 定时任务」与系统菜单的路由名称冲突
--
-- 根因：RuoYi 前端路由 name = capitalize(route_name 或 path)，必须全局唯一。
--   - 系统监控-定时任务 path=job  → name=Job
--   - AI 中心-定时任务   path=job  → name=Job  ← 冲突，后注册的打不开
--   - 系统工具           path=tool → name=Tool
--   - AI 中心-工具管理   path=tool → name=Tool  ← 冲突
--
-- 处理：
--   1) 恢复系统菜单 path 为官方值（若曾被改成 /job、/tool/）
--   2) 给 AI 菜单设置独立 route_name，URL 仍保持 /ai/job、/ai/tool

UPDATE sys_menu SET path = 'tool' WHERE menu_id = 3 AND menu_name = '系统工具';
UPDATE sys_menu SET path = 'job'  WHERE menu_id = 110 AND menu_name = '定时任务';

UPDATE sys_menu SET route_name = 'AiTool' WHERE menu_id = 2050;
UPDATE sys_menu SET route_name = 'AiJob'  WHERE menu_id = 2083;
