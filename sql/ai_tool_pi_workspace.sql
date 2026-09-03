-- 工作区工具对齐 Pi:改名保留绑定,删除碎工具。
update ai_tool set tool_code = 'read',  tool_name = '读取文件' where tool_code = 'readFile'  and del_flag = '0';
update ai_tool set tool_code = 'write', tool_name = '写入文件' where tool_code = 'writeFile' and del_flag = '0';
update ai_tool set tool_code = 'edit',  tool_name = '编辑文件' where tool_code = 'editor'    and del_flag = '0';
update ai_tool set tool_code = 'bash',  tool_name = '执行命令' where tool_code = 'runShell'  and del_flag = '0';
update ai_tool set tool_code = 'grep',  tool_name = '搜索文件内容' where tool_code = 'grepFiles' and del_flag = '0';
update ai_tool set tool_code = 'find',  tool_name = '匹配文件路径' where tool_code = 'globFiles' and del_flag = '0';
update ai_tool set tool_code = 'ls',    tool_name = '列出目录' where tool_code = 'listDir'   and del_flag = '0';

update ai_tool set require_confirm = '1' where tool_code = 'bash' and del_flag = '0' and require_confirm is null;

-- 已下线的碎工具:先解绑再逻辑删除
delete from ai_agent_tool where tool_id in (
  select tool_id from ai_tool where tool_code in (
    'createDirectory','deleteFile','pathExists','moveFile','copyFile',
    'shellSessionExec','shellSessionView','shellSessionWait','shellSessionWrite','shellSessionKill',
    'listInstalledPythonVersions','listInstalledNodeVersions'
  )
);

update ai_tool set del_flag = '2', status = '1'
 where tool_code in (
    'createDirectory','deleteFile','pathExists','moveFile','copyFile',
    'shellSessionExec','shellSessionView','shellSessionWait','shellSessionWrite','shellSessionKill',
    'listInstalledPythonVersions','listInstalledNodeVersions'
  ) and del_flag = '0';
