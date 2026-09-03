-- ----------------------------
-- 2、知识库文档表(kb_document)
--    文档挂在知识库下,一条文档 = 一个上传文件
--    (当前仅管理元数据与文件,解析/向量化后续再加)
-- ----------------------------
drop table if exists kb_document;
create table kb_document (
  doc_id         bigint(20)      not null auto_increment    comment '文档ID',
  kb_id          bigint(20)      not null                   comment '所属知识库ID',
  doc_name       varchar(255)    not null                   comment '文档名称(原始文件名)',
  file_path      varchar(500)    not null                   comment '文件存储路径(/profile/upload/kb/...)',
  file_size      bigint(20)      default 0                  comment '文件大小(字节)',
  file_type      varchar(32)     default ''                 comment '文件类型(扩展名,小写)',
  status         char(1)         default '0'                comment '文档状态(0正常 1停用)',
  create_by      varchar(64)     default ''                 comment '创建者',
  create_time    datetime                                   comment '创建时间',
  update_by      varchar(64)     default ''                 comment '更新者',
  update_time    datetime                                   comment '更新时间',
  remark         varchar(500)    default null               comment '备注',
  del_flag       char(1)         default '0'                comment '删除标志(0代表存在 2代表删除)',
  primary key (doc_id),
  key idx_kd_kb (kb_id)
) engine=innodb auto_increment=100 comment = '知识库文档表';