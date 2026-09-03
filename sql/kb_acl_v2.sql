-- ============================================================
-- KB-PV2-08：知识库可见范围 + 成员 ACL（PostgreSQL，幂等）
-- ============================================================

-- 负责人与可见范围
alter table kb_knowledge add column if not exists owner_user_id bigint;
alter table kb_knowledge add column if not exists visibility varchar(20);

comment on column kb_knowledge.owner_user_id is '负责人用户ID（可转移）';
comment on column kb_knowledge.visibility is '可见范围 PRIVATE/MEMBERS/DEPT/ORG';

-- 回填负责人与默认范围（仅空值）。
-- 旧库若未明确配置共享范围，按私有处理；部门共享必须由负责人显式设置为 DEPT。
update kb_knowledge
   set owner_user_id = create_user_id
 where owner_user_id is null
   and create_user_id is not null;

update kb_knowledge
   set visibility = 'PRIVATE'
 where visibility is null
   and del_flag = '0';

create index if not exists idx_kb_owner on kb_knowledge (owner_user_id);
create index if not exists idx_kb_visibility on kb_knowledge (visibility);

-- 成员 ACL
create table if not exists kb_acl_member (
  id          bigserial primary key,
  kb_id       bigint       not null,
  user_id     bigint       not null,
  role        varchar(20)  not null default 'VIEWER',
  create_by   varchar(64)  default '',
  create_time timestamp    default current_timestamp,
  constraint uk_kb_acl_member unique (kb_id, user_id)
);
create index if not exists idx_kb_acl_kb on kb_acl_member (kb_id);
create index if not exists idx_kb_acl_user on kb_acl_member (user_id);
comment on table kb_acl_member is '知识库成员 ACL：VIEWER/EDITOR/QUALITY/OWNER';
