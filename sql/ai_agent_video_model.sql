-- ai_agent 增加视频模型编码字段
-- 智能体可绑定一个 modelType=VIDEO 的模型，装配期自动获得 drawVideo 工具
-- 与 sql/ai_agent_image_model.sql 平行，不改动生图列
alter table ai_agent add column video_model_code varchar(100) default null
    comment '绑定视频模型编码(关联ai_model.model_code,modelType=VIDEO)' after image_model_code;
