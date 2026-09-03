-- vision_enabled 老数据回填：NULL 按不支持视觉处理，与代码判定口径对齐
-- 建表默认值已是 '0'（sql/init/02_ai_model_channel.sql），本脚本只修存量。
update ai_model set vision_enabled = '0' where vision_enabled is null;
