-- ----------------------------
-- 模型表加列:可见范围(公开/私人)
-- PUBLIC=公共(所有用户可选择),PRIVATE=私人(仅归属用户可见/可选)
-- 默认 PUBLIC:历史导入的模型保持原有"人人可用"语义
-- ----------------------------
alter table ai_model
  add column visibility varchar(20) default 'PUBLIC' comment '可见范围(PUBLIC公共/PRIVATE私人)' after status,
  add column owner_user_id bigint(20) default null comment '私有模型的归属用户ID(PUBLIC为空)' after visibility;
alter table ai_model add index idx_model_visibility (visibility);
