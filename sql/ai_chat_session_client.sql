-- 渠道工具:会话行增加客户端形态 + 工具清单快照 + 能力版本
-- 存量行 client_type 默认 desktop,行为不变
alter table ai_chat_session
  add column client_type      varchar(32)  default 'desktop' comment '客户端形态:desktop/mobile/api/browser_ext' after session_type,
  add column client_tools     text         default null      comment '客户端工具清单快照(规范化+排序后的JSON)' after client_type,
  add column client_tools_ver varchar(64)  default null      comment '客户端能力版本;变了才重写清单' after client_tools;

delete from sys_dict_data where dict_type = 'ai_client_type';
delete from sys_dict_type where dict_type = 'ai_client_type';

insert into sys_dict_type values(12, '客户端形态', 'ai_client_type', '0', 'admin', sysdate(), '', null, 'AI会话客户端形态');
insert into sys_dict_data values(32, 1, '桌面端', 'desktop', 'ai_client_type', '', 'primary', 'Y', '0', 'admin', sysdate(), '', null, '');
insert into sys_dict_data values(33, 2, '移动端', 'mobile',  'ai_client_type', '', 'info',    'N', '0', 'admin', sysdate(), '', null, '');
insert into sys_dict_data values(34, 3, 'API',    'api',     'ai_client_type', '', 'warning', 'N', '0', 'admin', sysdate(), '', null, '');
insert into sys_dict_data values(38, 4, '浏览器插件', 'browser_ext', 'ai_client_type', '', 'success', 'N', '0', 'admin', sysdate(), '', null, '');
