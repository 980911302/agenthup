-- 客户端运行时覆盖：智能体仍是默认能力基座；模型与 @ 技能按轮快照。
-- MySQL 8.4 不支持 ADD COLUMN IF NOT EXISTS；本脚本为一次性迁移，重复执行前请先检查字段是否已存在。

alter table ai_skill
  add column visibility varchar(16) not null default 'PUBLIC' comment 'PUBLIC公共/PRIVATE仅所属用户' after status,
  add column owner_user_id bigint(20) default null comment '私有技能所属用户ID' after visibility;

-- 旧技能默认升级为公共技能，避免上线后已有预设突然对客户端不可见。
update ai_skill set visibility = 'PUBLIC' where visibility is null or visibility = '';

alter table ai_chat_run
  add column selected_model_id bigint(20) default null comment '本轮客户端选择的聊天模型ID，空=智能体默认' after agent_id,
  add column selected_model_code varchar(100) default null comment '本轮实际模型编码快照' after selected_model_id,
  add column effective_skill_ids text default null comment '本轮生效技能ID JSON（默认+@技能）' after selected_model_code;
