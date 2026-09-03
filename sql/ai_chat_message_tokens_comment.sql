-- ----------------------------
-- ai_chat_message.tokens / usage_source 语义注释更新
-- 配合 docs/ai/ai-token-accounting-unification.md Task A
--
-- tokens 列:永远是 TokenEstimator 估算值(环图占比/预算),不再回填上游真实 usage
-- usage_source:只描述 prompt_tokens / completion_tokens 归因字段是否来自上游
-- ----------------------------

alter table ai_chat_message
  modify column tokens int(11) default 0
    comment '消息token数(TokenEstimator估算,供上下文占比/预算;真实用量见ai_llm_call)';

alter table ai_chat_message
  modify column usage_source char(1) default '1'
    comment 'prompt/completion归因字段来源(0上游真实 1本地估算);不描述tokens列';
