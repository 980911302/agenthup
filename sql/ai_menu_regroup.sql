-- ============================================================
-- AI 中心菜单分组:应用 / 能力 / 模型
--
-- 背景:AI 中心(menu_id=2000)下当前 8 个菜单全平铺,菜单越多越难找;
--      按产品层次分成 3 大类更清晰,也是主流 AI Agent 平台常见拆法。
--
-- 改后结构:
--   AI 中心(2000)
--   ├── 应用(2100,M)
--   │   ├── AI 对话(2080)      ← 会话管理(perms=ai:session:list)挂这下面
--   │   ├── 智能体管理          ← 原本 parent=2000,改 parent=2100
--   │   └── 定时任务(2083)
--   ├── 能力(2110,M)
--   │   ├── 技能管理(2030)
--   │   ├── 工具管理(2050)
--   │   ├── MCP 服务(2040)
--   │   └── 知识库管理(2070)
--   │   └── 长期记忆(2090，仅管理员)
--   └── 模型(2120,M)
--       ├── 模型管理
--       ├── 渠道管理
--       └── 模型供应
--
-- 设计要点:
--   - 父菜单用固定 menu_id(2100/2110/2120),在 AI 号段之外(避免与已有 2030-2089 冲突)
--   - 子菜单用 perms 反查 parent,不要写死 menu_id(agent/model/channel/modelChannel 号段不确定)
--   - 按钮权限(type=F,perms=ai:xxx:query/add/...)不需要动:
--     它们的 parent_id 仍指向对应 type=C 的菜单,UI 渲染会自动跟随
--   - 会话管理用多表 UPDATE(避免 MySQL 同表 select+update 锁问题)
--   - 可重入:每个 UPDATE 都能跑多次(perms 唯一 + LIMIT 1 防御)
-- ============================================================

-- 1) 三个分类父菜单
DELETE FROM sys_menu WHERE menu_id IN (2100, 2110, 2120) AND menu_type = 'M';
DELETE FROM sys_role_menu WHERE menu_id IN (2100, 2110, 2120);

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
VALUES
(2100, '应用', 2000, 1, 'app',   '', 1, 0, 'M', '0', '0', '', 'dashboard', 'admin', sysdate(), 'AI 中心 / 应用(用户直接使用/配置的产物)'),
(2110, '能力', 2000, 2, 'cap',   '', 1, 0, 'M', '0', '0', '', 'tool',      'admin', sysdate(), 'AI 中心 / 能力(可复用资产:技能/工具/MCP/知识)'),
-- icon 必须是 ruoyi-ui/src/assets/icons/svg/ 下真实存在的文件名(无 .svg 后缀)
-- 之前写 cpu 但仓库里没有 cpu.svg,侧栏会空白;改用 component
(2120, '模型', 2000, 3, 'model', '', 1, 0, 'M', '0', '0', '', 'component', 'admin', sysdate(), 'AI 中心 / 模型(外部 LLM 基础设施)');

-- 2) 把 8 个 C 类型业务菜单的 parent_id 改到对应分类下
--    用 perms 反查 + LIMIT 1 防御:perms 在 sys_menu 上有唯一索引,正常情况只会命中一行
UPDATE sys_menu SET parent_id = 2100, order_num = 1
 WHERE perms = 'ai:chat:list'        AND menu_type = 'C' LIMIT 1;

UPDATE sys_menu SET parent_id = 2100, order_num = 3
 WHERE perms = 'ai:agent:list'       AND menu_type = 'C' LIMIT 1;

UPDATE sys_menu SET parent_id = 2100, order_num = 4
 WHERE perms = 'ai:job:list'         AND menu_type = 'C' LIMIT 1;

UPDATE sys_menu SET parent_id = 2110, order_num = 1
 WHERE perms = 'ai:skill:list'       AND menu_type = 'C' LIMIT 1;

UPDATE sys_menu SET parent_id = 2110, order_num = 2
 WHERE perms = 'ai:tool:list'        AND menu_type = 'C' LIMIT 1;

UPDATE sys_menu SET parent_id = 2110, order_num = 3
 WHERE perms = 'ai:mcpServer:list'   AND menu_type = 'C' LIMIT 1;

UPDATE sys_menu SET parent_id = 2110, order_num = 4
 WHERE perms = 'ai:kb:list'          AND menu_type = 'C' LIMIT 1;

UPDATE sys_menu SET parent_id = 2110, order_num = 5
 WHERE perms = 'ai:memory:list'      AND menu_type = 'C' LIMIT 1;

UPDATE sys_menu SET parent_id = 2120, order_num = 1
 WHERE perms = 'ai:model:list'       AND menu_type = 'C' LIMIT 1;

UPDATE sys_menu SET parent_id = 2120, order_num = 2
 WHERE perms = 'ai:channel:list'     AND menu_type = 'C' LIMIT 1;

UPDATE sys_menu SET parent_id = 2120, order_num = 3
 WHERE perms = 'ai:modelChannel:list' AND menu_type = 'C' LIMIT 1;

-- 3) 会话管理挂到 AI 对话下面(子菜单)
--    多表 UPDATE:同一张表不能 select+update 直接赋值(MySQL 锁),用 join 写法
UPDATE sys_menu child
   JOIN sys_menu parent ON parent.perms = 'ai:chat:list' AND parent.menu_type = 'C'
   SET child.parent_id = parent.menu_id,
       child.order_num = 2
 WHERE child.perms = 'ai:session:list'
   AND child.menu_type = 'C';

-- 4) 给 admin 角色(role_id=1)授权 3 个新父菜单
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, menu_id FROM sys_menu WHERE menu_id IN (2100, 2110, 2120) AND menu_type = 'M';

-- 5) 兜底:确保所有原本 perms='ai:xxx:list' 的菜单都还给 admin(防止手工改过 role_menu)
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 1, menu_id
  FROM sys_menu
 WHERE menu_type = 'C'
   AND perms IN (
     'ai:chat:list', 'ai:agent:list', 'ai:job:list',
     'ai:skill:list', 'ai:tool:list', 'ai:mcpServer:list', 'ai:kb:list',
     'ai:memory:list',
     'ai:model:list', 'ai:channel:list', 'ai:modelChannel:list',
     'ai:session:list'
   );

-- ============================================================
-- 验证(跑完后手工执行,确认层级正确)
-- ============================================================
-- SELECT menu_id, menu_name, parent_id, order_num, menu_type, perms
--   FROM sys_menu
--  WHERE menu_id IN (2000, 2100, 2110, 2120)
--     OR parent_id IN (2000, 2100, 2110, 2120)
--     OR perms LIKE 'ai:%'
--  ORDER BY parent_id, order_num;
-- ============================================================
