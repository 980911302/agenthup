-- ai_agent 增加语音合成模型编码字段
-- 智能体可绑定一个 modelType=TTS 的模型，装配期自动获得 speak 工具
alter table ai_agent add column tts_model_code varchar(100) default null
    comment '绑定语音合成模型编码(关联ai_model.model_code,modelType=TTS)' after video_model_code;
