-- ----------------------------
-- 会话与上下文：会话元信息走表，agent 上下文走文件
-- 文件路径规则推导：sessions/{sessionId}/{agentId}.md，不在表中存储
-- ----------------------------

-- ----------------------------
-- 1、会话表(ai_chat_session)
--    一次对话 = 一个会话，聚合 token 消耗与上下文长度
-- ----------------------------
drop table if exists ai_chat_session;
create table ai_chat_session (
  session_id       varchar(64)    not null                   comment '会话ID(业务生成，如 uuid)',
  title            varchar(200)   default ''                 comment '会话标题(可由首条消息摘要生成)',
  user_id          bigint(20)     default null               comment '发起用户ID(关联sys_user)',
  status           char(1)        default '0'                comment '会话状态(0活跃 1已结束)',
  total_tokens     bigint(20)     default 0                   comment '会话累计 token(所有 agent 合计)',
  context_length  bigint(20)     default 0                   comment '当前会话总上下文长度(字符数)',
  create_by        varchar(64)    default ''                 comment '创建者',
  create_time      datetime                                  comment '创建时间',
  update_by        varchar(64)    default ''                 comment '更新者',
  update_time      datetime                                  comment '更新时间',
  remark           varchar(500)   default null               comment '备注',
  del_flag         char(1)        default '0'                comment '删除标志(0代表存在 2代表删除)',
  primary key (session_id),
  key idx_user_id (user_id),
  key idx_create_time (create_time)
) engine=innodb comment = '会话表';

-- ----------------------------
-- 2、会话-智能体关联表(ai_chat_session_agent)
--    一个会话里参与过哪些智能体、各自消耗多少 token
--    role 区分 supervisor / worker，便于成本归因
-- ----------------------------
drop table if exists ai_chat_session_agent;
create table ai_chat_session_agent (
  id                bigint(20)     not null auto_increment    comment '主键',
  session_id        varchar(64)    not null                   comment '会话ID',
  agent_id          bigint(20)     not null                   comment '智能体ID',
  role              varchar(20)   default 'worker'           comment '本会话中的角色(supervisor/worker)',
  tokens_used       bigint(20)     default 0                   comment '该智能体在本会话消耗的 token',
  turn_count        int(8)         default 0                  comment '该智能体在本会话被调用的轮数',
  first_active_time datetime                                  comment '首次接入时间',
  last_active_time  datetime                                  comment '最近活动时间',
  create_time       datetime                                  comment '创建时间',
  primary key (id),
  unique key uk_session_agent (session_id, agent_id),
  key idx_agent_id (agent_id)
) engine=innodb auto_increment=100 comment = '会话-智能体关联表';

-- ----------------------------
-- 3、菜单：会话管理挂在 AI 中心下
-- ----------------------------
-- 查询 AI 中心 menu_id
-- 会话管理作为 AI 中心下的子菜单
insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
values ('会话管理', 2000, 7, 'session', 'ai/session/index', 1, 0, 'C', '0', '0', 'ai:session:list', 'message', 'admin', sysdate(), 'AI 会话与上下文管理');
