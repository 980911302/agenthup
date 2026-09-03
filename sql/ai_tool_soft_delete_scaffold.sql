-- =============================================================================
-- 软删未启用/脚手架孤儿工具（不含定时任务四件套）
-- 配合 docs/ai/ai-tool-layer-cleanup.md Task D
--
-- 代码侧已取消注册:脚手架示例 + 业务示例。
-- 定时任务四件套已恢复注册,不要软删它们。
-- 可重跑:已 del_flag=1 的行再次 update 无害。
-- =============================================================================

-- ① 执行前确认
select tool_id, tool_code, tool_name, status, del_flag
  from ai_tool
 where tool_code in (
       'add', 'calculator', 'toUpperCase',
       'queryDeptList', 'queryUserBrief'
     );

-- ② 软删
update ai_tool
   set del_flag = '1',
       update_time = now()
 where tool_code in (
       'add', 'calculator', 'toUpperCase',
       'queryDeptList', 'queryUserBrief'
     )
   and del_flag = '0';

-- ③ 验收
select tool_id, tool_code, del_flag
  from ai_tool
 where tool_code in (
       'add', 'calculator', 'toUpperCase',
       'queryDeptList', 'queryUserBrief',
       'createScheduledJob', 'deleteScheduledJob',
       'listScheduledJobs', 'toggleScheduledJob'
     );
