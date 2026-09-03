-- =============================================================================
-- ai_chat_message.tokens 历史污染重估（方案①）
-- 配合 docs/ai/ai-token-accounting-unification.md Task C
--
-- 背景：累计记账 bug 修复前，ASSISTANT.tokens 被回填了虚高的 completion 累计值，
--       出现 tokens >> char_length(content) 的行（实测 68 行），污染环图「消息」段。
--
-- 做法：对受影响行用 TokenEstimator(cl100k) 按 content 重算并覆盖 tokens。
--       tokens 本就是展示/预算用的派生值，重算不丢原始信息（content 仍在）。
--
-- ⚠️ 本脚本本身 **不执行 UPDATE**（TokenEstimator 是 Java 侧）。
--    实际回填请运行：
--      mvn -q -pl ruoyi-system -DskipTests test-compile \
--        exec:java -Dexec.classpathScope=test \
--        -Dexec.mainClass=com.ruoyi.system.ai.memory.ReestimatePollutedTokensMain
--
-- 限定条件（与 Java 工具一致，精确匹配，禁止全表更新）：
--   visible_to_llm='0' AND message_type='ASSISTANT' AND tokens > char_length(content)
--
-- 幂等性：重跑无害（按 content 再估一次结果相同），但无必要。
-- 执行前务必先跑下面的 SELECT 确认范围。
-- =============================================================================

-- ① 受影响行数
select count(1) as bad_count
  from ai_chat_message
 where visible_to_llm = '0'
   and message_type = 'ASSISTANT'
   and tokens > char_length(ifnull(content, ''));

-- ② 样本（按虚高程度排序）
select message_id,
       tokens as old_tokens,
       char_length(ifnull(content, '')) as content_chars,
       round(tokens / nullif(char_length(ifnull(content, '')), 0), 2) as ratio,
       left(ifnull(content, ''), 60) as content_preview,
       create_time
  from ai_chat_message
 where visible_to_llm = '0'
   and message_type = 'ASSISTANT'
   and tokens > char_length(ifnull(content, ''))
 order by tokens desc
 limit 20;

-- ③ 回填后验收
-- cl100k 对中文本身可能 tokens > chars，不能再用该条件当失败。
-- 用「极端虚高」tokens > 3 * chars，应返回 0。
select count(1) as extreme_bad
  from ai_chat_message
 where visible_to_llm = '0'
   and message_type = 'ASSISTANT'
   and char_length(ifnull(content, '')) > 0
   and tokens > 3 * char_length(content);

-- ④ 抽查原先最虚高的几行
select message_id, tokens, char_length(content) as chars,
       round(tokens / char_length(content), 2) as ratio
  from ai_chat_message
 where message_id in (1022, 828, 861);
