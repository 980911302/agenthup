-- 工具策略字段:按工具限额 + 危险操作人工确认
-- 见 docs/ai/ai-borrowed-capabilities.md §1.1 / §1.3

ALTER TABLE ai_tool
  ADD COLUMN max_calls_per_run int(11) DEFAULT NULL COMMENT '单次运行该工具最多调用次数(空=不单独限制)' AFTER sort,
  ADD COLUMN require_confirm  char(1)  DEFAULT '0'  COMMENT '危险操作需人工确认(0否 1是)' AFTER max_calls_per_run;

-- 内置危险工具默认要求确认
UPDATE ai_tool SET require_confirm = '1'
 WHERE tool_code IN ('deleteFile', 'runShell') AND del_flag = '0';

-- writeFile 入参大、易刷量:单轮默认最多 20 次(可后台改)
UPDATE ai_tool SET max_calls_per_run = 20
 WHERE tool_code = 'writeFile' AND del_flag = '0' AND max_calls_per_run IS NULL;
