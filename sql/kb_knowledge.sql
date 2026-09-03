-- ----------------------------
-- 1、知识库表(kb_knowledge)
--    部门级知识库,创建时记录创建者与归属部门
--    (当前列表全量展示,部门过滤后续再加)
-- ----------------------------
drop table if exists kb_knowledge;
create table kb_knowledge (
  kb_id            bigint(20)      not null auto_increment    comment '知识库ID',
  kb_name          varchar(100)    not null                   comment '知识库名称',
  description      varchar(500)    default null               comment '知识库描述',
  status           char(1)         default '0'                comment '知识库状态(0正常 1停用)',
  create_user_id   bigint(20)      not null                   comment '创建者用户ID',
  dept_id          bigint(20)      not null                   comment '归属部门ID(创建时冗余写入,部门过滤用)',
  create_by        varchar(64)     default ''                 comment '创建者',
  create_time      datetime                                   comment '创建时间',
  update_by        varchar(64)     default ''                 comment '更新者',
  update_time      datetime                                   comment '更新时间',
  remark           varchar(500)    default null               comment '备注',
  del_flag         char(1)         default '0'                comment '删除标志(0代表存在 2代表删除)',
  primary key (kb_id),
  key idx_kb_dept (dept_id),
  key idx_kb_creator (create_user_id)
) engine=innodb auto_increment=100 comment = '知识库表';