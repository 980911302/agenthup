-- ai_agent 增加生图模型编码字段
-- 智能体可绑定一个 modelType=IMAGE 的模型，装配期自动获得 drawImage 工具
alter table ai_agent add column image_model_code varchar(100) default null
    comment '绑定生图模型编码(关联ai_model.model_code,modelType=IMAGE)' after model_code;
