-- ============================================================================
-- 05_ai_tool_skill.sql — 工具生态域初始化
-- 合并了 ai_mcp_tool.sql、ai_tool_policy.sql(max_calls_per_run/require_confirm)、
-- ai_remark.sql(remark)、fix_ai_tool_description_length.sql(description/return_desc改text)
-- ============================================================================

-- 1. MCP服务器表
drop table if exists ai_mcp_server;
create table ai_mcp_server (
  mcp_server_id     bigint(20)      not null auto_increment  comment 'MCP服务ID',
  server_name       varchar(100)    not null                 comment 'MCP服务名称',
  server_code       varchar(50)     not null                 comment 'MCP服务编码(系统引用,唯一)',
  transport         varchar(20)     not null                 comment '传输方式(STDIO/SSE/HTTP)',
  command           varchar(200)    default null             comment '启动命令(STDIO模式,如node/uv/python)',
  args              text            default null             comment '命令参数JSON数组',
  endpoint          varchar(500)    default null             comment '连接端点URL(SSE/HTTP模式填写)',
  env               text            default null             comment '环境变量JSON对象(加密存储)',
  health_status     char(1)         default '0'              comment '健康状态(0未知 1正常 2异常)',
  health_check_time datetime        default null             comment '最近健康检查时间',
  status            char(1)         default '0'              comment '状态(0正常 1停用)',
  remark            varchar(500)    default null             comment '备注',
  create_by         varchar(64)     default ''               comment '创建者',
  create_time       datetime                                 comment '创建时间',
  update_by         varchar(64)     default ''               comment '更新者',
  update_time       datetime                                 comment '更新时间',
  del_flag          char(1)         default '0'              comment '删除标志(0存在 2删除)',
  primary key (mcp_server_id),
  unique key uk_mcp_server_code (server_code)
) engine=innodb auto_increment=100 comment = 'MCP服务器表';

-- 2. 工具统一表(内置Java @Tool + MCP远端工具)
drop table if exists ai_tool;
create table ai_tool (
  tool_id            bigint(20)     not null auto_increment  comment '工具ID',
  tool_code          varchar(100)   not null                 comment '工具编码(系统引用,唯一)',
  tool_name          varchar(100)   not null                 comment '工具名称',
  description        text           default null             comment '工具描述(给LLM看的功能说明)',
  tool_type          char(1)        not null                 comment '工具类型(1内置 2MCP)',
  category           varchar(50)    default ''               comment '工具分类(如:搜索/计算/数据库)',
  -- 内置工具字段(tool_type=1)
  bean_name          varchar(100)   default null             comment 'Spring Bean名称(tool_type=1时填写)',
  method_name        varchar(100)   default null             comment '方法名(tool_type=1时填写)',
  -- MCP工具字段(tool_type=2)
  mcp_server_id      bigint(20)     default null             comment '所属MCP服务ID(tool_type=2时填写)',
  remote_tool_name   varchar(100)   default null             comment 'MCP远端工具名(tool_type=2时填写)',
  -- 通用
  input_schema       text           default null             comment 'JSON Schema,工具入参定义',
  return_desc        text           default null             comment '返回值说明(给LLM看的)',
  sort               int(4)         default 0                comment '显示顺序',
  max_calls_per_run  int(11)        default null             comment '单次运行该工具最多调用次数(空=不单独限制)',
  require_confirm    char(1)        default '0'              comment '危险操作需人工确认(0否 1是)',
  status             char(1)        default '0'              comment '状态(0正常 1停用)',
  create_by          varchar(64)    default ''               comment '创建者',
  create_time        datetime                                comment '创建时间',
  update_by          varchar(64)    default ''               comment '更新者',
  update_time        datetime                                comment '更新时间',
  del_flag           char(1)        default '0'              comment '删除标志(0存在 2删除)',
  remark             varchar(500)   default null             comment '备注',
  primary key (tool_id),
  unique key uk_tool_code (tool_code),
  key idx_mcp_server (mcp_server_id)
) engine=innodb auto_increment=100 comment = '工具表';

-- 3. 技能表
drop table if exists ai_skill;
create table ai_skill (
  skill_id         bigint(20)      not null auto_increment  comment '技能ID',
  skill_code       varchar(100)    not null                 comment '技能编码(系统引用,唯一)',
  skill_name       varchar(100)    not null                 comment '技能名称',
  category         varchar(50)     default ''               comment '技能分类(写作/编程/分析等)',
  description      varchar(500)    default null             comment '技能描述',
  prompt_template  text            not null                 comment '技能提示词模板(支持{var}占位符)',
  sort             int(4)          default 0                comment '显示顺序',
  status           char(1)         default '0'              comment '技能状态(0正常 1停用)',
  visibility       varchar(16)     not null default 'PUBLIC' comment 'PUBLIC公共/PRIVATE仅所属用户',
  owner_user_id    bigint(20)      default null             comment '私有技能所属用户ID',
  create_by        varchar(64)     default ''               comment '创建者',
  create_time      datetime                                 comment '创建时间',
  update_by        varchar(64)     default ''               comment '更新者',
  update_time      datetime                                 comment '更新时间',
  remark           varchar(500)    default null             comment '备注',
  del_flag         char(1)         default '0'              comment '删除标志(0存在 2删除)',
  primary key (skill_id),
  unique key uk_skill_code (skill_code),
  key idx_skill_owner_visibility (owner_user_id, visibility)
) engine=innodb auto_increment=100 comment = '技能表';

-- ============================================================================
-- 技能附件表(ai_skill_file):技能目录里的参考文件,详见 sql/ai_skill_file.sql
-- ============================================================================
drop table if exists ai_skill_file;
create table ai_skill_file (
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
