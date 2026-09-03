-- ----------------------------
-- 用户个人文件表(ai_user_file)
--
-- desktop「文件」菜单的落库表。与现有四条文件路径全部并列、互不干扰:
--
--   ai_user_file        个人网盘      S3 对象存储    ← 本表
--   会话工作区沙箱       AI 读写       本地磁盘(bash/grep 需要 POSIX 语义,不能上对象存储)
--   ai_skill_file       技能参考文件   本地磁盘
--   kb_document         知识库原文     本地磁盘
--
-- 为什么单独一张表而不是复用工作区:工作区按 sessionId/projectId 隔离且随会话删除而级联清空,
-- 个人文件的生命周期是「跟人走」,与任何一次会话无关。挂在会话上等于让用户的资料随手一删会话
-- 就没了。
--
-- 文件正文不落库也不落盘,只在 S3 兼容存储里;本表存元数据 + object_key 指针。
--
-- 全新环境初始化脚本;与 sql/ai_user_file.sql 内容一致(该文件是给已有环境的增量脚本)。
-- ----------------------------

create table if not exists ai_user_file (
  file_id       bigint(20)     not null auto_increment    comment '文件ID',
  user_id       bigint(20)     not null                   comment '归属用户ID(sys_user.user_id),强制,不允许无主文件',
  file_name     varchar(255)   not null                   comment '原始文件名(含扩展名),用户看到的名字',
  object_key    varchar(512)   not null                   comment '对象键 user/{userId}/{yyyyMM}/{uuid}.{ext};不含部署级 keyPrefix,换前缀不用改库',
  file_size     bigint(20)     not null default 0         comment '字节数',
  content_type  varchar(128)   default null               comment 'MIME 类型',
  content_hash  char(64)       default null               comment '正文 SHA-256,同一用户内秒传与重复检测用',
  del_flag      char(1)        default '0'                comment '删除标志(0存在 2删除)',
  create_by     varchar(64)    default ''                 comment '创建者',
  create_time   datetime                                  comment '创建时间',
  update_time   datetime                                  comment '更新时间',
  primary key (file_id),
  -- 列表页固定按 user_id + 未删 过滤再按时间倒序,做成联合索引可全覆盖排序,避免 filesort
  key idx_user_file_owner (user_id, del_flag, create_time),
  -- 秒传查重走这条。刻意不做唯一约束:软删的行 content_hash 仍在,
  -- 建唯一键会导致「删掉再传同一个文件」撞键失败
  key idx_user_file_hash (user_id, content_hash),
  -- 删除时按 object_key 反查引用计数,判断对象能否真删
  key idx_user_file_object (object_key)
) engine=innodb auto_increment=100 comment = '用户个人文件表(desktop 文件菜单)';
