-- 删除 ai_channel 表的 weight 字段
-- 2026-08-02: 渠道 weight 字段空壳无业务逻辑,真正路由走 ai_model_channel.weight
-- 后端/前端代码已全部清理,执行此脚本同步数据库 schema

ALTER TABLE ai_channel DROP COLUMN weight;
