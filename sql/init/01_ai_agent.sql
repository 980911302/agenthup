-- ============================================================================
-- 01_ai_agent.sql — 智能体域初始化
-- 合并了 ai_agent_appearance.sql(icon/theme)、ai_agent_image_model.sql、ai_agent_video_model.sql、ai_agent_public.sql(注:原 kb_phase1.sql 的 ai_agent_kb 关联已废弃删除,知识库选择下沉到会话级 ai_chat_session_kb)
-- ============================================================================

-- 1. 智能体主表
drop table if exists ai_agent;
create table ai_agent (
  agent_id            bigint(20)    not null auto_increment  comment '智能体ID',
  agent_code          varchar(100)  not null                 comment '智能体编码(系统引用,唯一)',
  agent_name          varchar(100)  not null                 comment '智能体名称',
  agent_desc          varchar(500)  default null             comment '智能体描述',
  icon                varchar(64)   default null             comment '图标(emoji)',
  theme               varchar(8)    default null             comment '主题色索引(0-7,空则按编码自动取色)',
  agent_role          longtext      default null             comment '智能体角色/系统提示词',
  load_local_doc      char(1)       default '0'              comment '是否加载本地agents.md(0否 1是)',
  is_public           char(1)       default '0'              comment '是否公共智能体(0否 1是)',
  model_code          varchar(100)  default null             comment '绑定对话模型(关联ai_model.model_code)',
  image_model_code    varchar(100)  default null             comment '绑定生图模型编码(关联ai_model.model_code,modelType=IMAGE)',
  video_model_code    varchar(100)  default null             comment '绑定视频模型编码(关联ai_model.model_code,modelType=VIDEO)',
  tts_model_code      varchar(100)  default null             comment '绑定语音合成模型编码(关联ai_model.model_code,modelType=TTS)',
  sort                int(4)        default 0                comment '显示顺序',
  status              char(1)       default '0'              comment '状态(0正常 1停用)',
  del_flag            char(1)       default '0'              comment '删除标志(0存在 2删除)',
  create_by           varchar(64)   default ''               comment '创建者',
  create_time         datetime                               comment '创建时间',
  update_by           varchar(64)   default ''               comment '更新者',
  update_time         datetime                               comment '更新时间',
  remark              varchar(500)  default null             comment '备注',
  primary key (agent_id),
  unique key uk_agent_code (agent_code)
) engine=innodb auto_increment=100 comment = '智能体表';

-- 2. 智能体-技能关联
drop table if exists ai_agent_skill;
create table ai_agent_skill (
  agent_id   bigint(20) not null comment '智能体ID',
  skill_id   bigint(20) not null comment '技能ID',
  sort       int(11)    default 0 comment '显示顺序',
  primary key (agent_id, skill_id),
  key idx_aas_skill (skill_id)
) engine=innodb comment = '智能体-技能关联';

-- 3. 智能体-工具关联
drop table if exists ai_agent_tool;
create table ai_agent_tool (
  agent_id   bigint(20) not null comment '智能体ID',
  tool_id    bigint(20) not null comment '工具ID',
  sort       int(11)    default 0 comment '显示顺序',
  primary key (agent_id, tool_id),
  key idx_aat_tool (tool_id)
) engine=innodb comment = '智能体-工具关联';

-- 5. 子智能体关联
drop table if exists ai_agent_child;
create table ai_agent_child (
  parent_agent_id  bigint(20)  not null comment '父智能体ID',
  child_agent_id   bigint(20)  not null comment '子智能体ID',
  sort             int(11)     default 0 comment '排序(装配时决定工具定义顺序)',
  trigger_desc     varchar(500) default null comment '触发说明(给父模型的协作提示)',
  child_agent_name varchar(100) default null comment '子智能体名称快照(软删后仍可展示)',
  child_agent_code varchar(100) default null comment '子智能体编码快照',
  primary key (parent_agent_id, child_agent_id),
  key idx_child_agent (child_agent_id)
) engine=innodb comment = '子智能体关联表';
