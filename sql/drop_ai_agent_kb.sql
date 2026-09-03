-- 删除 智能体-知识库关联表(ai_agent_kb)
-- 2026-08-11: 知识库选择从"智能体固定绑定"改为"会话级多选",ai_agent_kb 废弃。
-- 后端(AgentContextFactory 装配改为按 sessionId 查 ai_chat_session_kb)、前端(agent 配置页/知识库侧绑定 UI)已清理。
-- 配套先执行 sql/ai_chat_session_kb.sql 建新表,再执行本脚本。

DROP TABLE IF EXISTS ai_agent_kb;
