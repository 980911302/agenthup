-- ----------------------------
-- 1、MCP 服务器表(ai_mcp_server)
--    存 MCP 服务端连接配置
-- ----------------------------
drop table if exists ai_mcp_server;
create table ai_mcp_server (
  mcp_server_id     bigint(20)      not null auto_increment    comment 'MCP服务ID',
  server_name       varchar(100)    not null                   comment 'MCP服务名称',
  server_code       varchar(50)     not null                   comment 'MCP服务编码(系统引用,唯一)',
  transport         varchar(20)     not null                   comment '传输方式(STDIO/SSE/HTTP)',
  command           varchar(200)    default null               comment '启动命令(STDIO模式,如 node/uv/python)',
  args              text            default null               comment '命令参数JSON数组,如 ["mcp-server-fs","--root","/data"]',
  endpoint          varchar(500)    default null               comment '连接端点URL(SSE/HTTP模式填写)',
  env               text            default null               comment '环境变量JSON对象(加密存储),密钥/token 放这里',
  health_status     char(1)         default '0'                comment '健康状态(0未知 1正常 2异常)',
  health_check_time datetime        default null               comment '最近健康检查时间',
  status            char(1)         default '0'                comment '状态(0正常 1停用)',
  remark            varchar(500)    default null               comment '备注',
  create_by         varchar(64)     default ''                 comment '创建者',
  create_time       datetime                                   comment '创建时间',
  update_by         varchar(64)     default ''                 comment '更新者',
  update_time       datetime                                   comment '更新时间',
  del_flag          char(1)         default '0'                comment '删除标志(0代表存在 2代表删除)',
  primary key (mcp_server_id),
  unique key uk_mcp_server_code (server_code)
) engine=innodb auto_increment=100 comment = 'MCP服务器表';

-- ----------------------------
-- 2、工具统一表(ai_tool)
--    工具 = LLM 可调用的能力,来源分内置(Java @Tool)和 MCP
-- ----------------------------
drop table if exists ai_tool;
create table ai_tool (
  tool_id           bigint(20)      not null auto_increment    comment '工具ID',
  tool_code         varchar(100)    not null                   comment '工具编码(系统引用,唯一)',
  tool_name         varchar(100)    not null                   comment '工具名称',
  description       text            default null               comment '工具描述(给LLM看的功能说明)',
  tool_type         char(1)         not null                   comment '工具类型(1内置 2MCP)',
  category          varchar(50)     default ''                 comment '工具分类(如:搜索/计算/数据库)',
  -- 内置工具字段(tool_type=1)
  bean_name         varchar(100)    default null               comment 'Spring Bean名称(tool_type=1时填写)',
  method_name       varchar(100)    default null               comment '方法名(tool_type=1时填写)',
  -- MCP 工具字段(tool_type=2)
  mcp_server_id     bigint(20)      default null               comment '所属MCP服务ID(tool_type=2时填写)',
  remote_tool_name  varchar(100)    default null               comment 'MCP远端工具名(tool_type=2时填写)',
  -- 通用
  input_schema      text            default null               comment 'JSON Schema,工具入参定义',
  return_desc       text            default null               comment '返回值说明(给LLM看的)',
  sort              int(4)          default 0                  comment '显示顺序',
  status            char(1)         default '0'                comment '状态(0正常 1停用)',
  create_by         varchar(64)     default ''                 comment '创建者',
  create_time       datetime                                   comment '创建时间',
  update_by         varchar(64)     default ''                 comment '更新者',
  update_time       datetime                                   comment '更新时间',
  del_flag          char(1)         default '0'                comment '删除标志(0代表存在 2代表删除)',
  primary key (tool_id),
  unique key uk_tool_code (tool_code),
  key idx_mcp_server (mcp_server_id)
) engine=innodb auto_increment=100 comment = '工具表';
