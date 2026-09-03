-- 客户端形态字典补浏览器插件。已执行过 ai_chat_session_client.sql 的存量库跑这一条即可。
insert into sys_dict_data
select 38, 4, '浏览器插件', 'browser_ext', 'ai_client_type', '', 'success', 'N', '0', 'admin', sysdate(), '', null, ''
from dual
where not exists (select 1 from sys_dict_data where dict_type = 'ai_client_type' and dict_value = 'browser_ext');
