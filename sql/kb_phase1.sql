-- ----------------------------
-- 知识库第一期:表结构扩展 + kb_chunk
-- 见 docs/ai/ai-kb-phase1-plan.md §3
-- ----------------------------

-- 3.1 kb_document 扩展
alter table kb_document
  add column ir_path        varchar(255) default null comment '解析产物(IR)文件路径',
  add column parse_status   varchar(20)  default 'PENDING' comment '处理状态,见 KbDocStatus',
  add column parse_step     varchar(40)  default null comment '当前步骤(细粒度)',
  add column progress       int          default 0    comment '整体进度百分比',
  add column chunk_count    int          default 0    comment '分块数',
  add column error_type     varchar(50)  default null comment '失败分类',
  add column error_stage    varchar(20)  default null comment '失败阶段(parse/chunk/embed)',
  add column error_msg      text         default null comment '失败全文,不截断',
  add column parser_version varchar(20)  default null comment '解析器版本,重建时判定用',
  add column content_hash   varchar(64)  default null comment '文件内容hash,kb内判重用';

-- 重复上传判重:同一知识库内相同内容只处理一次
alter table kb_document
  add key idx_kd_kb_hash (kb_id, content_hash);

-- 3.2 kb_chunk
create table if not exists kb_chunk (
  chunk_id          bigint(20)   not null auto_increment comment '分块ID',
  kb_id             bigint(20)   not null comment '知识库ID',
  doc_id            bigint(20)   not null comment '文档ID',
  chunk_index       int          not null comment '在文档内的序号,用于还原顺序',
  content           mediumtext   not null comment '分块原文(不含清洗版本)',
  heading_path      varchar(500) default null comment '章节面包屑,用 → 分隔',
  block_type        varchar(20)  default null comment '来源块类型(paragraph/heading/table/...)',
  token_count       int          default 0 comment '估算 token 数',
  embedding         blob         default null comment '向量(float32 紧凑编码)',
  embedding_dim     int          default 0 comment '向量维度',
  chunker_strategy  varchar(20)  default null comment '分块策略(F/P)',
  chunk_params_hash varchar(64)  default null comment '分块参数指纹',
  embedding_model   varchar(100) default null comment '嵌入模型 code',
  source_page_from  int          default null comment '来源起始页,1-based',
  source_page_to    int          default null comment '来源结束页,1-based',
  source_label      varchar(500) default null comment '工作表/幻灯片/结构路径等来源标签',
  chunk_level       varchar(20)  default 'LEAF' comment '层级分块类型',
  parent_chunk_id   bigint(20)   default null comment '父分块ID',
  create_time       datetime     default null,
  primary key (chunk_id),
  key idx_chunk_doc (doc_id),
  key idx_chunk_kb (kb_id),
  key idx_chunk_parent (parent_chunk_id)
) engine=innodb comment = '知识库分块表';

-- 3.3 kb_knowledge 扩展
alter table kb_knowledge
  add column embedding_model_code varchar(100) default null comment '嵌入模型(换模型需重建全部向量)',
  add column chunk_strategy       varchar(20)  default 'P'  comment '分块策略',
  add column chunk_size           int          default 800  comment '分块目标 token 数',
  add column chunk_overlap        int          default 100  comment '重叠 token 数';
