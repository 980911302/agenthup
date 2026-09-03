-- =====================================================================
-- 10_ai_memory.sql — 跨会话长期记忆 台账表(MySQL 主库)
--
-- 唯一事实源:向量表(mem_vector_*)与未来的图谱都从本表派生。
-- 分层:user_id 永远强制;agent_id=0 表示用户层(跨 agent 共享),>0 为该 agent 专属层。
-- 只增不删:覆盖走 superseded,物理删除只留给合规清理(del_flag=2)。
-- =====================================================================

drop table if exists ai_memory;

create table ai_memory (
  memory_id          bigint(20)    not null auto_increment comment '记忆主键',
  user_id            bigint(20)    not null comment '隔离维度(永远强制)',
  agent_id           bigint(20)    not null comment '0=用户层;>0=该agent专属层',
  type               varchar(20)   not null comment 'fact|preference|event|goal|rule',
  content            text          not null comment '记忆正文',
  status             varchar(20)   not null default 'active' comment 'active|superseded',
  superseded_by      bigint(20)    null comment '被哪条覆盖(仅同层内)',
  source             varchar(20)   not null default 'auto' comment '提炼来源(当前恒为auto,预留)',
  source_session_id  varchar(64)   null comment '来源会话(可溯源)',
  source_message_id  bigint(20)    null comment '提炼覆盖到的消息位点',
  content_hash       varchar(64)   null comment '正文归一化后哈希,精确去重',
  embedding_dim      int(11)       null comment '落在哪张向量表(删除/重建用),空=待补向量',
  embedding_model    varchar(100)  null comment '用了哪个 embedding 模型',
  hit_count          int(11)       not null default 0 comment '被检索命中次数',
  last_hit_time      datetime      null comment '最近命中时间',
  create_time        datetime      not null comment '时间线语义基准',
  update_time        datetime      null,
  del_flag           char(1)       not null default '0' comment '0存在 2删除(合规清理用)',
  primary key (memory_id),
  key idx_mem_tenant (user_id, agent_id, status, del_flag),
  key idx_mem_hash (user_id, agent_id, content_hash),
  key idx_mem_superseded (superseded_by)
) engine=InnoDB auto_increment=1000 comment='跨会话长期记忆台账';
