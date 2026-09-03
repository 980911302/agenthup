-- AI 中心菜单图标补齐
-- 对应前端 svg: ruoyi-ui/src/assets/icons/svg/{icon}.svg
-- 按钮权限(F) 保持 icon='#'，不在侧边栏展示
-- 注意:icon 名必须与 svg 文件名一致,仓库里没有的(如旧版 cpu)会显示空白

UPDATE sys_menu SET icon = 'star'       WHERE menu_id = 2000 AND menu_type = 'M'; -- AI 中心
UPDATE sys_menu SET icon = 'dashboard'  WHERE menu_id = 2100 AND menu_type = 'M'; -- 应用
UPDATE sys_menu SET icon = 'tool'       WHERE menu_id = 2110 AND menu_type = 'M'; -- 能力
UPDATE sys_menu SET icon = 'component'  WHERE menu_id = 2120 AND menu_type = 'M'; -- 模型(勿用 cpu,无 svg)

UPDATE sys_menu SET icon = 'link'       WHERE menu_id = 2001 AND menu_type = 'C'; -- 上游渠道
UPDATE sys_menu SET icon = 'component'  WHERE menu_id = 2010 AND menu_type = 'C'; -- 模型管理
UPDATE sys_menu SET icon = 'tree-table' WHERE menu_id = 2020 AND menu_type = 'C'; -- 模型渠道绑定
UPDATE sys_menu SET icon = 'skill'      WHERE menu_id = 2030 AND menu_type = 'C'; -- 技能管理
UPDATE sys_menu SET icon = 'server'     WHERE menu_id = 2040 AND menu_type = 'C'; -- MCP 服务
UPDATE sys_menu SET icon = 'tool'       WHERE menu_id = 2050 AND menu_type = 'C'; -- 工具管理
UPDATE sys_menu SET icon = 'robot'      WHERE menu_id = 2060 AND menu_type = 'C'; -- 智能体管理
UPDATE sys_menu SET icon = 'message'    WHERE menu_id = 2080 AND menu_type = 'C'; -- AI 对话
UPDATE sys_menu SET icon = 'job'        WHERE menu_id = 2083 AND menu_type = 'C'; -- 定时任务

-- 按 perms 兜底(menu_id 可能因环境不同而不固定)
UPDATE sys_menu SET icon = 'component'
 WHERE menu_type = 'M' AND menu_name = '模型' AND parent_id = 2000;

UPDATE sys_menu SET icon = 'component'
 WHERE menu_type = 'C' AND perms = 'ai:model:list';

UPDATE sys_menu SET icon = 'link'
 WHERE menu_type = 'C' AND perms = 'ai:channel:list';

UPDATE sys_menu SET icon = 'tree-table'
 WHERE menu_type = 'C' AND perms = 'ai:modelChannel:list';
