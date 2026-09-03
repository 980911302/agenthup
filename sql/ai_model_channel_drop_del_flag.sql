-- ----------------------------
-- ai_model_channel:取消逻辑删除,改为物理删除
--
-- 背景:ai_model / ai_channel 的删除本来就是物理删除(delete from),
-- 只有 ai_model_channel 还在写 del_flag='2'。唯一索引 uk_model_channel(model_id, channel_id)
-- 不含 del_flag,软删行会一直占着唯一键位,于是又长出一套 reactivate「复活」逻辑。
-- 后果:模型详情页显示只挂 1 个渠道,直接查库却是 2 行,排查路由/缓存问题时极易误判。
--
-- 执行前请备份;本脚本只能执行一次(alter drop column 不幂等)。
-- 若不确定是否已执行过,先运行:
--
--   select column_name from information_schema.columns
--    where table_schema = database()
--      and table_name = 'ai_model_channel'
--      and column_name = 'del_flag';
--
--   无结果 = 已执行过,请跳过。
--
-- ⚠️ 两步顺序不可颠倒:必须先删软删行,再 drop 列。
--    先 drop 列的话,原本 del_flag='2' 的行会立刻"复活"成正常供应,
--    模型会凭空多出已被删掉的渠道 —— 正是本次要根除的现象。
-- ----------------------------

-- 第 1 步:清掉历史软删行(它们在业务上早已被删除)
-- 执行前可先看一眼将被删除的内容:
--   select id, model_id, channel_id, model_name from ai_model_channel where del_flag = '2';
delete from ai_model_channel where del_flag = '2';

-- 第 2 步:去掉 del_flag 列
alter table ai_model_channel drop column del_flag;
