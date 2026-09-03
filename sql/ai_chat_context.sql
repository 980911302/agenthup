-- ----------------------------
-- 上下文与消息存储(配合 docs/ai/ai-agent-graph.md §4)
--
-- 背景：ai_chat_session.sql 建表时的设计是「会话元信息走表、agent 上下文全走文件」，
-- 表里没有任何消息级记录。多智能体 + 工具调用落地后这个分工不够用了：
--   1. LLM 实际吃的上下文需要并发安全 + 结构化，文件的 read-modify-write 会丢消息；
--   2. 工具调用要能按 session/agent 查询与统计，文件里的 JSONL 只能全量读回再过滤；
--   3. 前端要还原「思考 → 工具调用 → 回答」的完整时间线，需要可分页查询 + 类型标记。
--
-- 只用一张表 ai_chat_message，同一份数据两个查询视图：
--   给 LLM 看  ->  where visible_to_llm = '0'（再叠加压缩边界，见下）
--   给前端看  ->  全量 order by message_id
-- ContextFileStore 的 .md 降级为人读快照，不再是权威源。
--
-- 为什么能用一张表：本方案的 ChatMemory 是直接实现 org.springframework.ai.chat.memory.ChatMemory
-- （参考官方 spring-ai-alibaba-admin 的 ConversationChatMemory），add 是「真追加」语义。
-- 若改成实现 ChatMemoryRepository 则不行——MessageWindowChatMemory 的 saveAll 是覆盖语义
-- （参考实现 InMemoryChatMemoryRepository.saveAll 就是一个 Map.put），
-- 会把超出窗口的历史物理删掉，审计就没了。详见文档 §4.5。
-- ----------------------------

-- ----------------------------
-- 对话消息表(ai_chat_message)
--
-- message_type 取值：
--   USER      用户输入
--   ASSISTANT 模型输出（含中间文本与最终回答，靠 visible_to_llm 区分）
--   SYSTEM    系统提示词留痕
--   TOOL      工具调用（含子智能体调用，见 sub_agent_id）
--   THINKING  思考过程 / reasoning content（只给人看）
--   SUMMARY   上下文压缩摘要（见 summary_to_id）
--
-- visible_to_llm 是「本质属性」，写入时确定、永不变更：
--   '0' 参与 LLM 上下文  —— USER、最终 ASSISTANT、SUMMARY
--   '1' 只给前端看       —— THINKING、TOOL、以及"我先查一下知识库"这类中间文本
-- 注意中间文本与最终回答同为 ASSISTANT 类型但待遇不同，所以必须独立标记，
-- 不能靠 message_type 反推。
--
-- 「是否已被压缩替代」是另一个正交维度，不写进 visible_to_llm，
-- 而是由最新一条 SUMMARY 的 message_id 动态界定（见文档 §4.7）。
-- 这样原始记录保持 immutable，压缩策略随时可以重来。
-- ----------------------------
drop table if exists ai_chat_message;
create table ai_chat_message (
  message_id       bigint(20)     not null auto_increment    comment '消息ID(自增，同时作为会话内顺序)',
  session_id       varchar(64)    not null                   comment '会话ID(关联 ai_chat_session)',
  agent_id         bigint(20)     default null               comment '产生该消息的智能体ID(旧路径未绑定 agent 时为空)',
  conversation_id  varchar(128)   not null                   comment 'LLM记忆键(= sessionId:agentId)，压缩与窗口都按它独立进行',
  sub_agent_id     bigint(20)     default null               comment '被调用的子智能体ID(agent-as-tool 时，配合 tool_source=agent)',

  message_type     varchar(20)    not null                   comment 'USER/ASSISTANT/SYSTEM/TOOL/THINKING/SUMMARY',
  content          longtext                                  comment '消息正文(SUMMARY 行存摘要文本)',
  visible_to_llm   char(1)        default '0'                comment '是否参与LLM上下文(0是 1否)，本质属性，写入即定',
  summary_to_id    bigint(20)     default null               comment 'SUMMARY 行专用：本摘要覆盖了 message_id <= 此值的消息',
  attachments      json           default null               comment '富媒体附件[{type,url,name,size}]，图片等只存URL不存base64',

  tool_call_id     varchar(64)    default null               comment 'TOOL 消息回指的调用ID',
  tool_name        varchar(100)   default null               comment '工具名(子智能体调用时为 agentCode)',
  tool_args        longtext                                  comment '工具入参',
  tool_result      longtext                                  comment '工具返回(超过内联上限时截断，全文见 tool_result_path)',
  tool_result_path varchar(255)   default null               comment '工具返回超过内联上限(默认2KB)时的文件路径，前端点开时按需读取',
  tool_source      varchar(20)    default null               comment '工具来源(builtin/mcp/agent)',
  tool_duration_ms bigint(20)     default null               comment '工具执行耗时(毫秒)',
  tool_success     char(1)        default null               comment '工具是否成功(0成功 1失败)',

  -- 所有消息写入时都估算并存 token(ASSISTANT 用 ChatResponse 的真实 usage，其余用 JTokkitTokenCountEstimator)。
  -- 目的是让上下文预算检查退化成一条 sum() 查询，不必每轮对全部历史重跑 tokenizer。见文档 §4.7。
  tokens           int(11)        default 0                  comment '该消息的 token 数(真实 usage 或估算值)',
  create_time      datetime                                  comment '创建时间',

  primary key (message_id),
  -- 同一个覆盖范围只允许一条 SUMMARY，并发触发压缩时后插的直接冲突失败，天然幂等。
  -- 非 SUMMARY 行的 summary_to_id 为 null，MySQL 唯一索引允许多个 null，不受影响。
  unique key uk_conv_summary_to (conversation_id, summary_to_id),
  key idx_session_msg (session_id, message_id),
  key idx_conv_llm (conversation_id, visible_to_llm, message_id),
  key idx_session_agent (session_id, agent_id),
  key idx_tool_name (tool_name),
  key idx_sub_agent (sub_agent_id)
) engine=innodb auto_increment=100 comment = 'AI对话消息表(只增不删，LLM上下文与前端时间线共用)';

-- ----------------------------
-- 会话表补充字段：消息计数
-- total_tokens / context_length 已有，补一个消息数便于列表页直接展示，
-- 避免每次 count(*) 扫 ai_chat_message。
-- ----------------------------
alter table ai_chat_session
  add column message_count int(11) default 0 comment '会话累计消息条数' after context_length;

-- ----------------------------
-- 会话-智能体表补充字段：工具调用计数
-- 便于成本归因时看到「哪个 agent 调工具最多」。
-- ----------------------------
alter table ai_chat_session_agent
-- 【已废弃 2026-08-26】tool_call_count 加进表后从未进入 ORM 层,全代码零引用、恒为 0,
-- 已由 sql/drop_unused_ai_plan_and_columns.sql 删除。此行仅存历史,新库不要执行。
-- add column tool_call_count int(11) default 0 comment '该智能体在本会话的工具调用次数' after turn_count;

-- ----------------------------
-- 上下文策略参数(走 RuoYi 参数配置，后台「参数设置」可改，改完实时生效不用重启)
--
-- 放这里而不是 application.yml 的原因：这三个是上线后可能要按实际效果调的运营参数。
-- 纯技术参数(内联上限 / 兜底窗口 / 条数硬上限)在 application.yml 的 ai.chat.* 下。
-- 模型自身的 context_window / max_output_tokens 在 ai_model 表，每个模型不同。
--
-- 用整数百分比而不是小数：后台输入框填 80 比填 0.8 直观，也不会把 0.8 误填成 8。
-- ----------------------------
insert into sys_config values(100, 'AI上下文-压缩触发比例',   'ai.context.compactThreshold', '80', 'Y', 'admin', sysdate(), '', null, '历史 token 占模型可用输入预算(context_window 减 max_output_tokens)的百分比，超过则触发压缩。填 1-99 的整数');
insert into sys_config values(101, 'AI上下文-压缩目标比例',   'ai.context.compactTarget',    '40', 'Y', 'admin', sysdate(), '', null, '压缩后要降到的百分比。必须明显小于触发比例，否则压完马上又超，会反复触发压缩白烧 token');
insert into sys_config values(102, 'AI上下文-过程记录保留天数', 'ai.context.retainDays',     '90', 'Y', 'admin', sysdate(), '', null, '已结束会话超过该天数后清理 THINKING/TOOL 过程记录，对话本身(USER/ASSISTANT/SUMMARY)保留。填 0 表示不清理');

-- ----------------------------
-- 常用查询参考
-- ----------------------------

-- 1) LLM 上下文(ChatMemory.get 用)：先取最新压缩点，再取其后的增量
--    select message_id, content from ai_chat_message
--     where conversation_id = #{convId} and message_type = 'SUMMARY'
--     order by message_id desc limit 1;
--
--    select message_type, content, attachments from ai_chat_message
--     where conversation_id = #{convId}
--       and visible_to_llm = '0'
--       and message_type <> 'SUMMARY'
--       and message_id > #{summaryMessageId}   -- 无摘要时传 0
--     order by message_id asc
--     limit 40;
--    最终拼成：[SystemMessage(摘要), ...增量消息]

-- 2) 前端时间线：全量，不过滤，SUMMARY 行渲染成"以上 N 条已压缩"分隔线
--    select message_id, message_type, content, attachments, sub_agent_id,
--           tool_name, tool_args, left(tool_result, 500) as tool_result_preview,
--           tool_source, tool_duration_ms, tool_success, create_time
--      from ai_chat_message
--     where session_id = #{sessionId}
--     order by message_id asc;

-- 3) 某条工具调用的完整返回(点开时才查；有 tool_result_path 时改读文件)
--    select tool_result, tool_result_path from ai_chat_message where message_id = #{messageId};

-- 3.5) 上下文预算检查(每轮一次，判断是否触发压缩，见文档 §4.7)
--     阈值来自 ai_model.context_window 与 max_output_tokens，不是固定条数
--     select ifnull(sum(tokens), 0) from ai_chat_message
--      where conversation_id = #{convId}
--        and visible_to_llm = '0'
--        and message_id &gt; #{lastSummaryMessageId};   -- 无摘要时传 0

-- 4) 工具调用历史(审计/统计)
--    select tool_name, tool_source, count(1) cnt, avg(tool_duration_ms) avg_ms,
--           sum(case when tool_success = '1' then 1 else 0 end) fail_cnt
--      from ai_chat_message
--     where session_id = #{sessionId} and message_type = 'TOOL'
--     group by tool_name, tool_source;
