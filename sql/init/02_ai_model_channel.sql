-- ============================================================================
-- 02_ai_model_channel.sql — 模型渠道域初始化
-- 合并了 ai_chat_attachment.sql(vision_enabled)、ai_model_supply_refactor.sql(retry_count)、
-- drop_ai_channel_weight.sql(去掉 weight)、ai_model_input_modalities.sql(input_modalities)
-- ============================================================================

-- 1. 上游渠道
drop table if exists ai_channel;
create table ai_channel (
  channel_id         bigint(20)    not null auto_increment  comment '渠道ID',
  channel_name       varchar(100)  not null                 comment '渠道名称',
  channel_code       varchar(50)   not null                 comment '渠道编码(系统引用,唯一)',
  channel_type       varchar(20)   not null                 comment '渠道类型(OPENAI/DASHSCOPE/ANTHROPIC/CUSTOM)',
  is_custom          char(1)       default '0'              comment '是否自定义渠道(0否 1是)。自定义=手动维护模型清单;非自定义=从上游/models同步',
  base_url           varchar(500)  default null             comment '上游base URL',
  api_key            varchar(500)  default null             comment '上游API Key(加密存储)',
  health_check_uri   varchar(255)  default null             comment '健康检查端点',
  health_status      char(1)       default '0'              comment '健康状态(0未知 1正常 2异常)',
  health_check_time  datetime      default null             comment '最近健康检查时间',
  health_fail_count  int(11)       default 0                comment '连续失败次数',
  status             char(1)       default '0'              comment '状态(0正常 1停用)',
  del_flag           char(1)       default '0'              comment '删除标志(0存在 2删除)',
  create_by          varchar(64)   default ''               comment '创建者',
  create_time        datetime                               comment '创建时间',
  update_by          varchar(64)   default ''               comment '更新者',
  update_time        datetime                               comment '更新时间',
  remark             varchar(500)  default null             comment '备注',
  primary key (channel_id),
  unique key uk_channel_code (channel_code)
) engine=innodb auto_increment=100 comment = '上游渠道表';

-- 2. 模型元数据
drop table if exists ai_model;
create table ai_model (
  model_id          bigint(20)    not null auto_increment  comment '模型ID',
  model_code        varchar(100)  not null                 comment '模型编码(如gpt-4o/deepseek-v3)',
  display_name      varchar(100)  default null             comment '模型展示名',
  model_type        varchar(20)   not null                 comment '类型(CHAT/EMBEDDING/IMAGE/VIDEO)',
  context_window    int(11)       default 0                comment '上下文窗口token数',
  max_output_tokens int(11)       default 0                comment '最大输出token',
  reasoning_enabled char(1)       default '0'              comment '是否开启推理/思考(0关闭 1开启)',
  vision_enabled    char(1)       default '0'              comment '是否支持视觉理解(0否 1是,已被 input_modalities 取代,保留兼容)',
  input_modalities  varchar(64)   default ''               comment '支持的输入模态(逗号分隔:image/file/video/audio,空为纯文本)',
  sort              int(4)        default 0                comment '显示顺序',
  status            char(1)       default '0'              comment '状态(0正常 1停用)',
  visibility        varchar(20)   default 'PUBLIC'         comment '可见范围(PUBLIC公共/PRIVATE私人)',
  owner_user_id     bigint(20)    default null             comment '私有模型的归属用户ID(PUBLIC为空)',
  del_flag          char(1)       default '0'              comment '删除标志(0存在 2删除)',
  create_by         varchar(64)   default ''               comment '创建者',
  create_time       datetime                               comment '创建时间',
  update_by         varchar(64)   default ''               comment '更新者',
  update_time       datetime                               comment '更新时间',
  remark            varchar(500)  default null             comment '备注',
  primary key (model_id),
  unique key uk_model_code (model_code),
  key idx_model_visibility (visibility)
) engine=innodb auto_increment=100 comment = '模型元数据表';

-- 3. 模型×渠道供应(多对多 + 权重路由 + 重试)
drop table if exists ai_model_channel;
create table ai_model_channel (
  id            bigint(20)     not null auto_increment  comment '主键',
  model_id      bigint(20)     not null                 comment '模型ID',
  channel_id    bigint(20)     not null                 comment '渠道ID',
  model_name    varchar(100)   not null                 comment '上游实际模型名(可能与model_code不同)',
  input_price   decimal(18,6)  default null             comment '输入单价(元/1K token)',
  output_price  decimal(18,6)  default null             comment '输出单价',
  weight        int(11)        default 100              comment '权重(用于多渠道负载均衡)',
  retry_count   int(11)        default 0                comment '失败重试次数(0=不重试)',
  status        char(1)        default '0'              comment '状态(0正常 1停用)',
  -- 无 del_flag:供应绑定走物理删除(见 sql/ai_model_channel_drop_del_flag.sql)。
  -- 软删行会占着 uk_model_channel 的唯一键位,逼出一套「复活」逻辑,
  -- 还会让「界面只挂 1 个渠道、查库却是 2 行」,排查路由问题时极易误判。
  create_by     varchar(64)    default ''               comment '创建者',
  create_time   datetime                                comment '创建时间',
  update_by     varchar(64)    default ''               comment '更新者',
  update_time   datetime                                comment '更新时间',
  remark        varchar(500)   default null             comment '备注',
  primary key (id),
  unique key uk_model_channel (model_id, channel_id, model_name),
  key idx_channel (channel_id)
) engine=innodb auto_increment=100 comment = '模型渠道供应表';

-- 4. 上游模型清单(渠道维度的候选池)
drop table if exists ai_upstream_model;
create table ai_upstream_model (
  id                 bigint(20)    not null auto_increment  comment '主键',
  channel_id         bigint(20)    not null                 comment '所属渠道ID',
  upstream_model_id  varchar(200)  not null                 comment '上游模型标识(调用时传给上游的model参数,如gpt-4o)',
  display_name       varchar(200)  default null             comment '展示名',
  owned_by           varchar(100)  default null             comment '上游归属方(openai/anthropic等)',
  source             char(1)       default '1'              comment '来源(0手动录入 1上游同步)',
  input_modalities   varchar(64)   default null             comment '上游声明的输入模态(逗号分隔;null=上游未提供)',
  create_by          varchar(64)   default ''               comment '创建者',
  create_time        datetime                               comment '创建时间',
  update_by          varchar(64)   default ''               comment '更新者',
  update_time        datetime                               comment '更新时间',
  remark             varchar(500)  default null             comment '备注',
  primary key (id),
  unique key uk_channel_model (channel_id, upstream_model_id),
  key idx_upstream_model_id (upstream_model_id)
) engine=innodb auto_increment=100 comment = '上游模型表(渠道可用模型清单)';
