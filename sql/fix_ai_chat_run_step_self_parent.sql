-- 存量修复：子智能体的输出 chunk 曾把 agent 步骤的 parent_step_id 覆写成它自己的 step_id。
--
-- 成因：事件里 owner 表示「这段输出归属哪个步骤」，子智能体自己的 chunk 归属的就是它本身，
-- 投影落库时被当成父指针写进 ai_chat_run_step，节点自成环。刷新/重进会话时前端按父指针
-- 建树，该节点进不了根集合，子智能体连同它下面的工具一起从时间线消失。
--
-- 写入侧已在 ChatEventJson.putStep 与 ChatRunProjectionService.base 收口，本脚本只清历史行。
-- 幂等，可重复执行；置 null 即恢复「顶层」，嵌套子智能体的真实父由 agent_start 写的值保留。
update ai_chat_run_step
   set parent_step_id = null
 where parent_step_id = step_id;

-- 说明：ai_chat_message 里子智能体内部工具行的 run_id 曾落成 NULL（同一批缺陷的另一半，
-- 已在 SubAgentToolCallback 用 Reactor 上下文下传 runId 修复）。历史行不做回填 ——
-- 终态恢复会用 ai_chat_run_step 快照兜底，上面这条修完后旧会话即可正常渲染。
-- 若确需回填审计字段，可按下面的方式对齐（先 select 确认影响行数再执行）：
--   update ai_chat_message t
--     join ai_chat_message a
--       on a.session_id = t.session_id and a.step_id = t.parent_step_id and a.run_id is not null
--      set t.run_id = a.run_id
--    where t.run_id is null and t.parent_step_id is not null;
