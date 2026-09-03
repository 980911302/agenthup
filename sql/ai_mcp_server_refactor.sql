-- 重构 ai_mcp_server:
-- 1) 删除 api_key 字段(密钥放 env JSON 里)
-- 2) endpoint 字段语义改为:仅 SSE/HTTP 模式下的 URL
-- 3) 增加 command / args / env 字段(STDIO 模式需要)

alter table ai_mcp_server
    drop column api_key,
    add column command varchar(200) default null  comment '启动命令(STDIO模式,如 node/uv/python)' after transport,
    add column args    text          default null  comment '命令参数JSON数组,如 ["mcp-server-fs","--root","/data"]' after command,
    add column env     text          default null  comment '环境变量JSON对象(加密存储),密钥/token 放这里' after args;
