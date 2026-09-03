-- ============================================================================
-- 工具往返进上下文(跨轮保留)
-- 说明:本脚本是 ALTER 增量,不是新表。全新建库请用 sql/init/03_ai_chat.sql
-- ============================================================================

alter table ai_chat_message
  add column tool_calls longtext null
    comment 'ASSISTANT行专用:本轮tool_calls的JSON数组[{id,type,name,arguments}]。重建assistant(tool_calls)消息用'
    after attachments,
  add column pruned char(1) default '0'
    comment '是否已被上下文清理(0否 1是)。为1时装配给LLM出占位文本,原文保留供审计与前端时间线'
    after tool_success;

-- 按 tool_call_id 回写 pruned 标记用
create index idx_conv_tool_call on ai_chat_message (conversation_id, tool_call_id);
