-- 修复 MCP 工具描述过长导致同步失败(Data truncation: Data too long for column 'description')
--
-- 背景:MCP 远端工具 description 可能远超 500 字符(英文长描述),
-- 原 ai_tool.description varchar(500) 在插入时直接被 MySQL 截断报错,
-- 导致 ToolSyncService.syncAll 整体失败,所有工具(含内置)都同步不了。
--
-- 改为 text:工具描述是给 LLM 看的说明文本,长度不固定,text 最合适,
-- 且 utf8mb4 下 text 可存约 65535 字节,足够。
-- return_desc 同理也可能被远端返回值说明撑长,一并放宽。
alter table ai_tool modify column description text default null comment '工具描述(给LLM看的功能说明)';
alter table ai_tool modify column return_desc  text default null comment '返回值说明(给LLM看的)';
