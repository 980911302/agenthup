-- Keycloak / OpenID Connect 外部账号绑定表
-- 使用 issuer + subject 作为身份唯一键，不能只以 email 作为账号绑定依据。
create table if not exists sys_user_oauth_account (
  oauth_id    bigint(20)   not null auto_increment comment '主键',
  user_id     bigint(20)   not null comment '本地用户 ID',
  provider    varchar(32)  not null comment '客户端注册名，例如 keycloak',
  issuer      varchar(255) not null comment 'OIDC issuer',
  subject     varchar(255) not null comment 'OIDC sub',
  user_name   varchar(100) default '' comment '最近一次外部用户名',
  email       varchar(255) default '' comment '最近一次外部邮箱',
  avatar      varchar(500) default '' comment '最近一次外部头像',
  create_time datetime     not null comment '创建时间',
  update_time datetime     not null comment '更新时间',
  primary key (oauth_id),
  unique key uk_sys_user_oauth_issuer_subject (issuer, subject),
  key idx_sys_user_oauth_user_id (user_id)
) engine=innodb comment='外部 OAuth/OIDC 账号绑定';
