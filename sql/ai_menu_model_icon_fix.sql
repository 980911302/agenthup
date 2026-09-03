-- 修复「模型」父菜单图标空白
-- 原因:ai_menu_regroup.sql 写入 icon='cpu'，但 ruoyi-ui 无 cpu.svg
-- 图标必须对应 src/assets/icons/svg/{name}.svg

UPDATE sys_menu SET icon = 'component'
 WHERE menu_type = 'M'
   AND menu_name = '模型'
   AND parent_id = 2000;

-- 同步固定 id（若环境仍用 2120）
UPDATE sys_menu SET icon = 'component'
 WHERE menu_id = 2120 AND menu_type = 'M';
