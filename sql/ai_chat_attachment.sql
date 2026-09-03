-- ----------------------------
-- 会话附件上传：模型视觉能力标记
--
-- ⚠️ 本脚本只能执行一次
-- alter table ... add column 不具备幂等性，重复执行会报 Duplicate column name。
-- 若不确定是否已执行过，先运行下面的检查语句：
--
--   select table_name, column_name from information_schema.columns
--    where table_schema = database()
--      and table_name = 'ai_model' and column_name = 'vision_enabled';
--
--   有结果 = 已执行过，跳过本脚本。
-- ----------------------------

-- ----------------------------
-- 模型是否支持视觉理解(看图)。
--
-- 与 reasoning_enabled 同一模式：能力标记存在模型上，运行时据此决定
-- 图片走多模态 Media 通道还是降级成「只告诉路径，让模型用 readFile 读」。
--
-- 为什么不靠 model_type 判断：model_type=IMAGE 指的是「图像生成模型」
-- (文生图)，与「能看懂图的对话模型」是两回事，不能混用。
-- ----------------------------
alter table ai_model
  add column vision_enabled char(1) default '0' comment '是否支持视觉理解(0否 1是)' after reasoning_enabled;

-- ----------------------------
-- 常见视觉模型预置(按需调整；model_code 以各家实际标识为准)
-- 没匹配上的模型保持 '0'，上传图片时会自动降级并在界面提示。
-- ----------------------------
update ai_model set vision_enabled = '1'
 where lower(model_code) like '%vl%'
    or lower(model_code) like '%vision%'
    or lower(model_code) like 'gpt-4o%'
    or lower(model_code) like 'gpt-4.1%'
    or lower(model_code) like 'gpt-5%'
    or lower(model_code) like 'claude-3%'
    or lower(model_code) like 'claude-4%'
    or lower(model_code) like 'gemini%';

-- ----------------------------
-- 附件本身不需要新表：
--   - 文件落在会话工作区 {workspaceRoot}/{sessionId}/uploads/ 下，
--     复用 WorkspaceSandbox 的路径穿越防护与按会话隔离；
--   - 消息级元数据存 ai_chat_message.attachments(json)，建表时已预留，
--     格式 [{"name":"x.png","path":"uploads/x.png","mime":"image/png","size":1234}]。
-- ----------------------------
