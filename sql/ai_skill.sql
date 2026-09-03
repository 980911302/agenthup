-- ----------------------------
-- 1、技能表(ai_skill)
--    技能 = 提示词模板,智能体可绑定使用
-- ----------------------------
drop table if exists ai_skill;
create table ai_skill (
  skill_id         bigint(20)      not null auto_increment    comment '技能ID',
  skill_code       varchar(100)    not null                   comment '技能编码(系统引用,唯一)',
  skill_name       varchar(100)    not null                   comment '技能名称',
  category         varchar(50)     default ''                 comment '技能分类(写作/编程/分析等)',
  description      varchar(500)    default null               comment '技能描述',
  prompt_template  text            not null                   comment '技能提示词模板(支持 {var} 占位符)',
  sort             int(4)          default 0                  comment '显示顺序',
  status           char(1)         default '0'                comment '技能状态(0正常 1停用)',
  visibility       varchar(16)     not null default 'PUBLIC'  comment 'PUBLIC公共/PRIVATE仅所属用户',
  owner_user_id    bigint(20)      default null               comment '私有技能所属用户ID',
  create_by        varchar(64)     default ''                 comment '创建者',
  create_time      datetime                                   comment '创建时间',
  update_by        varchar(64)     default ''                 comment '更新者',
  update_time      datetime                                   comment '更新时间',
  remark           varchar(500)    default null               comment '备注',
  del_flag         char(1)         default '0'                comment '删除标志(0代表存在 2代表删除)',
  primary key (skill_id),
  unique key uk_skill_code (skill_code),
  key idx_skill_owner_visibility (owner_user_id, visibility)
) engine=innodb auto_increment=100 comment = '技能表';
