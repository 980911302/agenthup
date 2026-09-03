-- ============================================================================
-- 上游模型表 + 渠道自定义开关
-- 说明:本脚本含 1 张新表 + 1 处 ALTER。全新建库请用 sql/init/02_ai_model_channel.sql
-- ============================================================================

-- 1. 渠道加「是否自定义」开关
alter table ai_channel
  add column is_custom char(1) default '0'
    comment '是否自定义渠道(0否 1是)。自定义=手动维护模型清单;非自定义=从上游/models同步'
    after channel_type;

-- 2. 上游模型表:渠道维度的模型候选池
drop table if exists ai_upstream_model;
create table ai_upstream_model (
  id                 bigint(20)    not null auto_increment  comment '主键',
  channel_id         bigint(20)    not null                 comment '所属渠道ID',
  upstream_model_id  varchar(200)  not null                 comment '上游模型标识(调用时传给上游的model参数,如gpt-4o)',
  display_name       varchar(200)  default null             comment '展示名',
  owned_by           varchar(100)  default null             comment '上游归属方(openai/anthropic等)',
  source             char(1)       default '1'              comment '来源(0手动录入 1上游同步)',
  create_by          varchar(64)   default ''               comment '创建者',
  create_time        datetime                               comment '创建时间',
  update_by          varchar(64)   default ''               comment '更新者',
  update_time        datetime                               comment '更新时间',
  remark             varchar(500)  default null             comment '备注',
  primary key (id),
  unique key uk_channel_model (channel_id, upstream_model_id),
  key idx_upstream_model_id (upstream_model_id)
) engine=innodb auto_increment=100 comment = '上游模型表(渠道可用模型清单)';
