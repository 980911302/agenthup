-- ============================================================================
-- 08_ai_config_dict.sql — 系统配置与字典初始化
-- 合并了 ai_chat_context.sql(sys_config 100-102)、ai_job.sql(sys_config 103-105 + sys_dict)
-- ============================================================================

-- ----------------------------
-- 上下文策略参数(sys_config)
-- ----------------------------
delete from sys_config where config_id in (100, 101, 102, 103, 104, 105);

insert into sys_config values(100, 'AI上下文-压缩触发比例',   'ai.context.compactThreshold', '80', 'Y', 'admin', sysdate(), '', null, '历史token占模型可用输入预算的百分比,超过则触发压缩。填1-99的整数');
insert into sys_config values(101, 'AI上下文-压缩目标比例',   'ai.context.compactTarget',    '40', 'Y', 'admin', sysdate(), '', null, '压缩后要降到的百分比。必须明显小于触发比例');
insert into sys_config values(102, 'AI上下文-过程记录保留天数', 'ai.context.retainDays',     '90', 'Y', 'admin', sysdate(), '', null, '已结束会话超过该天数后清理THINKING/TOOL过程记录。填0不清理');

-- ----------------------------
-- 定时任务运营参数(sys_config)
-- ----------------------------
insert into sys_config values(103, 'AI定时任务-单用户任务上限', 'ai.job.maxPerUser', '20', 'Y', 'admin', sysdate(), '', null, '单用户可拥有的启用中定时任务数上限');
insert into sys_config values(104, 'AI定时任务-最小触发间隔分钟', 'ai.job.minIntervalMinutes', '5', 'Y', 'admin', sysdate(), '', null, 'cron最小触发间隔,防止模型生成每秒执行的表达式');
insert into sys_config values(105, 'AI定时任务-日志保留天数', 'ai.job.logRetainDays', '90', 'Y', 'admin', sysdate(), '', null, 'ai_job_log超过该天数自动清理,填0不清理');

-- ----------------------------
-- 知识库默认设置(sys_config)
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
('知识库-默认向量模型',     'kb.default.embeddingModel', '',    'N', 'admin', sysdate(), '新建知识库默认的EMBEDDING模型code;留空则建库后手动选'),
('知识库-默认抽取模型',     'kb.default.extractModel',   '',    'N', 'admin', sysdate(), '新建知识库默认的实体抽取模型code(CHAT类型,建议用便宜模型)'),
('知识库-默认分块策略',     'kb.default.chunkStrategy',  'P',   'N', 'admin', sysdate(), 'P=按章节段落 F=固定token'),
('知识库-默认分块大小',     'kb.default.chunkSize',      '800', 'N', 'admin', sysdate(), '分块目标token数,100~4000'),
('知识库-默认分块重叠',     'kb.default.chunkOverlap',   '100', 'N', 'admin', sysdate(), '相邻分块重叠token数,0~500且需小于分块大小'),
('知识库-默认启用图谱',     'kb.default.graphEnabled',   '0',   'N', 'admin', sysdate(), '新建知识库是否默认开启知识图谱抽取,0否 1是');

-- ----------------------------
-- 会话类型字典(sys_dict_type + sys_dict_data)
-- ----------------------------
delete from sys_dict_data where dict_type = 'ai_session_type';
delete from sys_dict_type where dict_type = 'ai_session_type';

insert into sys_dict_type values(11, '会话类型', 'ai_session_type', '0', 'admin', sysdate(), '', null, 'AI会话类型');
insert into sys_dict_data values(30, 1, '普通对话', 'chat', 'ai_session_type', '', 'primary', 'Y', '0', 'admin', sysdate(), '', null, '');
insert into sys_dict_data values(31, 2, '定时任务', 'job',  'ai_session_type', '', 'warning', 'N', '0', 'admin', sysdate(), '', null, '');

-- ----------------------------
-- 客户端形态字典
-- ----------------------------
delete from sys_dict_data where dict_type = 'ai_client_type';
delete from sys_dict_type where dict_type = 'ai_client_type';

insert into sys_dict_type values(12, '客户端形态', 'ai_client_type', '0', 'admin', sysdate(), '', null, 'AI会话客户端形态');
insert into sys_dict_data values(32, 1, '桌面端', 'desktop', 'ai_client_type', '', 'primary', 'Y', '0', 'admin', sysdate(), '', null, '');
insert into sys_dict_data values(33, 2, '移动端', 'mobile',  'ai_client_type', '', 'info',    'N', '0', 'admin', sysdate(), '', null, '');
insert into sys_dict_data values(34, 3, 'API',    'api',     'ai_client_type', '', 'warning', 'N', '0', 'admin', sysdate(), '', null, '');
insert into sys_dict_data values(38, 4, '浏览器插件', 'browser_ext', 'ai_client_type', '', 'success', 'N', '0', 'admin', sysdate(), '', null, '');
