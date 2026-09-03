alter table ai_mcp_server modify column endpoint varchar(500) default '' not null;
insert into ai_mcp_server (server_name, server_code, transport, command, args, env, status, remark)
values ('演示-fetch', 'demo-fetch', 'STDIO', 'uvx', '["mcp-server-fetch"]', null, '0', '演示 MCP:uvx mcp-server-fetch,提供 fetch_url 工具');
