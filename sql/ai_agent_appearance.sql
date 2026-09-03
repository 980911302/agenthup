-- ----------------------------
-- 智能体外观：图标 + 主题色
-- 让每个智能体在列表/详情里有稳定可辨的视觉身份
-- ----------------------------

ALTER TABLE ai_agent
  ADD COLUMN icon  VARCHAR(64) DEFAULT NULL COMMENT '智能体图标(emoji)' AFTER agent_desc,
  ADD COLUMN theme VARCHAR(8)  DEFAULT NULL COMMENT '主题色索引(0-7,空则按编码自动取色)' AFTER icon;

-- 给已有数据补一个默认图标，避免全是首字方块
UPDATE ai_agent SET icon = '🤖' WHERE icon IS NULL OR icon = '';
