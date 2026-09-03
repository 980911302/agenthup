-- ----------------------------
-- 技能附件表(ai_skill_file)
--
-- 技能从「一段提示词」升级为「一个目录」:prompt_template 是 SKILL.md 正文,
-- 本表存它的参考文件(渐进披露的第三层)。文件本身落盘在技能资源根
-- ruoyi.ai.tool.skill-root 下的 {skillId}/ 里,这里只存元数据。
--
-- 为什么不内联进提示词:参考文档动辄几千 token,常驻上下文压缩不掉。
-- loadSkill 只把「有哪些文件、各是干嘛的」告诉模型,内容用 read 按需取。
--
-- ⚠️ 本脚本只能执行一次(create table 无幂等保护)。已有环境重复执行会报表已存在。
-- ----------------------------

create table if not exists ai_skill_file (
  file_id       bigint(20)     not null auto_increment    comment '附件ID',
  skill_id      bigint(20)     not null                   comment '所属技能ID(ai_skill.skill_id)',
  rel_path      varchar(255)   not null                   comment '技能目录内相对路径(如 REFERENCE.md)',
  file_size     bigint(20)     default 0                  comment '字节数',
  content_type  varchar(100)   default null               comment 'MIME 类型',
  summary       varchar(500)   default null               comment '一句话说明,进 loadSkill 的文件清单给模型看',
  create_by     varchar(64)    default ''                 comment '创建者',
  create_time   datetime                                  comment '创建时间',
  del_flag      char(1)        default '0'                comment '删除标志(0存在 2删除)',
  primary key (file_id),
  unique key uk_skill_file_path (skill_id, rel_path),
  key idx_skill_file_skill (skill_id, del_flag)
) engine=innodb auto_increment=100 comment = '技能附件表(技能目录里的参考文件)';
