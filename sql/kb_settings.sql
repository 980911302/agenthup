-- ----------------------------
-- 知识库全局设置(新建知识库时的默认值)
--
-- 存 sys_config 而不是新建表:若依现成的键值配置,带 Redis 缓存与
-- selectConfigByKey(),零新表零新缓存。
--
-- 语义:这些只是「新建知识库时预填的默认值」,建完即固化到 kb_knowledge 的列上。
-- 之后改这里,已有知识库一律不受影响 —— 否则改一次向量模型就会让存量向量
-- 全部作废、静默触发全量重建(见 docs/ai/ai-kb-global-settings-plan.md §2.1)。
--
-- config_type='N' 表示非系统内置,允许删除。
-- 可重复执行。
-- ----------------------------

delete from sys_config where config_key in (
  'kb.default.embeddingModel',
  'kb.default.extractModel',
  'kb.default.chunkStrategy',
  'kb.default.chunkSize',
  'kb.default.chunkOverlap',
  'kb.default.graphEnabled'
);

insert into sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
values
('知识库-默认向量模型',     'kb.default.embeddingModel', '',    'N', 'admin', sysdate(), '新建知识库默认的 EMBEDDING 模型 code;留空则建库后手动选'),
('知识库-默认抽取模型',     'kb.default.extractModel',   '',    'N', 'admin', sysdate(), '新建知识库默认的实体抽取模型 code(CHAT 类型,建议用便宜模型)'),
('知识库-默认分块策略',     'kb.default.chunkStrategy',  'P',   'N', 'admin', sysdate(), 'P=按章节段落 F=固定token;分块为纯算法,不调用 LLM'),
('知识库-默认分块大小',     'kb.default.chunkSize',      '800', 'N', 'admin', sysdate(), '分块目标 token 数,100~4000'),
('知识库-默认分块重叠',     'kb.default.chunkOverlap',   '100', 'N', 'admin', sysdate(), '相邻分块重叠 token 数,0~500 且需小于分块大小'),
('知识库-默认启用图谱',     'kb.default.graphEnabled',   '0',   'N', 'admin', sysdate(), '新建知识库是否默认开启知识图谱抽取,0否 1是');
