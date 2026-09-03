# SQL 初始化脚本说明

本目录包含 agent-java 项目的**全新环境初始化** SQL 脚本。所有增量补丁(ALTER TABLE)已合并进 CREATE TABLE,可直接在全新数据库上执行。

## 执行顺序

### MySQL(主库)

```bash
# 1. RuoYi 原生表(用户/角色/权限/部门/字典等)
mysql -u root -p ry-vue < ../ry_20260417.sql

# 2. Quartz 调度表
mysql -u root -p ry-vue < ../quartz.sql

# 3. AI 业务表(按编号顺序执行)
mysql -u root -p ry-vue < 01_ai_agent.sql
mysql -u root -p ry-vue < 02_ai_model_channel.sql
mysql -u root -p ry-vue < 03_ai_chat.sql
mysql -u root -p ry-vue < 04_ai_llm_call.sql
mysql -u root -p ry-vue < 05_ai_tool_skill.sql
mysql -u root -p ry-vue < 06_ai_job.sql
mysql -u root -p ry-vue < 07_ai_menu.sql
mysql -u root -p ry-vue < 08_ai_config_dict.sql
mysql -u root -p ry-vue < 10_ai_memory.sql
mysql -u root -p ry-vue < 12_ai_memory_extract_progress.sql
mysql -u root -p ry-vue < 13_ai_user_file.sql
```

> 编号跳过 09:那是 PostgreSQL 脚本(见下),不在 MySQL 这一串里。

### PostgreSQL(知识库)

```bash
# 需要先安装 pgvector 扩展
psql -U postgres -d kb_db < 09_kb_pg.sql
psql -U postgres -d kb_db < 11_mem_pg.sql
```

## 脚本清单

| 文件 | 域 | 包含表 |
|------|-----|--------|
| `01_ai_agent.sql` | 智能体 | ai_agent, ai_agent_skill, ai_agent_tool, ai_agent_child |
| `02_ai_model_channel.sql` | 模型渠道 | ai_channel, ai_model, ai_model_channel |
| `03_ai_chat.sql` | 聊天 | ai_chat_session, ai_chat_session_agent, ai_chat_session_kb, ai_chat_message, ai_chat_run, ai_trace_span, ai_chat_special_event, ai_project |
| `04_ai_llm_call.sql` | 计量 | ai_llm_call |
| `05_ai_tool_skill.sql` | 工具生态 | ai_mcp_server, ai_tool, ai_skill |
| `06_ai_job.sql` | 定时任务 | ai_job, ai_job_log |
| `07_ai_menu.sql` | 菜单 | sys_menu, sys_role_menu(AI模块菜单) |
| `08_ai_config_dict.sql` | 配置 | sys_config, sys_dict_type, sys_dict_data |
| `09_kb_pg.sql` | 知识库(PG) | kb_knowledge, kb_document, kb_chunk, kb_vector_*, kb_acl_member, kb_index_policy*, kb_graph_* |
| `10_ai_memory.sql` | 记忆(MySQL) | ai_memory |
| `11_mem_pg.sql` | 记忆(PG) | mem_vector_{768,1024,1536,3072} |
| `12_ai_memory_extract_progress.sql` | 记忆(MySQL) | ai_memory_extract_progress |
| `13_ai_user_file.sql` | 个人文件 | ai_user_file |

## 与旧脚本的对应关系

本目录脚本是 `sql/` 根目录下多个增量脚本的**合并版**:

| 新脚本 | 合并了哪些旧脚本 |
|--------|-----------------|
| 01 | ai_agent_appearance.sql, ai_agent_image_model.sql, kb_phase1.sql(agent列部分) |
| 02 | ai_chat_attachment.sql, ai_model_supply_refactor.sql, drop_ai_channel_weight.sql, ai_model_visibility.sql(模型可见范围列) |
| 03 | ai_chat_session.sql, ai_chat_context.sql, ai_chat_run.sql, ai_trace_span.sql, ai_chat_message_tokens_comment.sql, ai_chat_session_kb.sql(会话-知识库关联), ai_project.sql(项目表+会话 project_id 列) |
| 04 | ai_llm_call.sql, ai_llm_call_cache_tokens.sql |
| 05 | ai_mcp_tool.sql, ai_tool_policy.sql, ai_remark.sql, fix_ai_tool_description_length.sql |
| 06 | ai_job.sql(表结构部分) |
| 07 | ai_chat_menu.sql, ai_skill_menu.sql, ai_tool_menu_v2.sql, ai_mcp_tool_menu.sql, ai_model_import_menu.sql, kb_menu.sql |
| 08 | ai_chat_context.sql(sys_config), ai_job.sql(sys_config+sys_dict), kb_settings.sql |
| 09 | kb_pg.sql, kb_acl_v2.sql, kb_index_policy_v1.sql, kb_chunk_v2.sql, kb_graph_index_previous.sql |

## 注意事项

1. **已有环境升级**:本脚本仅适用于全新初始化。已有环境请使用 `sql/` 根目录下的增量脚本。
2. **菜单 ID 冲突**:07_ai_menu.sql 会先删除 menu_id 2030-2099 的旧菜单再插入,确保幂等。
3. **pgvector**:09_kb_pg.sql 需要 PostgreSQL 14+ 且已安装 pgvector 扩展(`CREATE EXTENSION vector`)。
4. **运维脚本**:数据清理类脚本(如 cleanup_orphan_stat.sql)不在本目录,仍使用 `sql/` 根目录下的原版。
