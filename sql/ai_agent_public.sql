-- 公共智能体标识（MySQL 5.7+，幂等）
-- 0：私有/内部智能体；1：公共智能体。
-- 历史数据保持私有，需由管理员在智能体管理页显式公开。
set @agent_public_ddl = (
  select if(
    exists(
      select 1 from information_schema.columns
       where table_schema = database()
         and table_name = 'ai_agent'
         and column_name = 'is_public'
    ),
    'select 1',
    'alter table ai_agent add column is_public char(1) default ''0'' comment ''是否公共智能体(0否 1是)'' after load_local_doc'
  )
);
prepare agent_public_stmt from @agent_public_ddl;
execute agent_public_stmt;
deallocate prepare agent_public_stmt;

update ai_agent
   set is_public = '0'
 where is_public is null;
