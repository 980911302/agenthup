-- ----------------------------
-- 项目表(ai_project):项目级共享工作空间及其会话容器
-- ----------------------------
drop table if exists ai_project;
create table ai_project (
  project_id    bigint(20)     not null auto_increment comment '项目ID',
  project_name  varchar(100)   not null                comment '项目名称',
  description   varchar(500)   default null            comment '项目描述',
  user_id       bigint(20)     default null            comment '归属用户ID(关联sys_user)',
  create_by     varchar(64)    default ''              comment '创建者',
  create_time   datetime                               comment '创建时间',
  update_by     varchar(64)    default ''              comment '更新者',
  update_time   datetime                               comment '更新时间',
  remark        varchar(500)   default null            comment '备注',
  del_flag      char(1)        default '0'             comment '删除标志(0存在 2删除)',
  primary key (project_id),
  key idx_user_id (user_id),
  key idx_create_time (create_time)
) engine=innodb comment = '项目工作空间';

-- ----------------------------
-- 会话表加列:所属项目工作空间(空=普通会话独立工作区)
-- ----------------------------
alter table ai_chat_session
  add column project_id bigint(20) default null comment '所属项目工作空间ID(空=独立工作区)' after source_job_id;
alter table ai_chat_session add index idx_project_id (project_id);
