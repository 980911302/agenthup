-- 输入模态多选:vision_enabled(布尔) → input_modalities(集合)
--
-- 背景:输入模态之间不是包含关系,不能用一个开关表达。实测 OpenRouter 417 个模型
-- 跑出 12 种组合,反例明确:gpt-audio 支持音频但不支持图片,o3-mini 支持文档但不支持
-- 图片,kimi-k3 支持视频但不支持文档。
--
-- 取值:image / file / video / audio,逗号分隔;空串表示纯文本模型。
-- text 是所有模型的前提,不入库。
--
-- vision_enabled 保留不删:迁移期内由代码同步双写,存量报表和外部查询不受影响。
-- 判定一律以 input_modalities 为准(见 ModelInputModalities)。

-- 幂等:重复执行前先确认列是否已存在
-- select column_name from information_schema.columns
--  where table_schema = database()
--    and table_name = 'ai_model' and column_name = 'input_modalities';

alter table ai_model
  add column input_modalities varchar(64) default '' comment '支持的输入模态(逗号分隔:image/file/video/audio,空为纯文本)' after vision_enabled;

-- 存量回填:原来标了支持视觉的,等价于「支持图片」
update ai_model set input_modalities = 'image' where vision_enabled = '1';
update ai_model set input_modalities = ''      where vision_enabled = '0' or vision_enabled is null;

-- 回填结果自查
-- select model_code, vision_enabled, input_modalities from ai_model where del_flag = '0';

-- ---------------------------------------------------------------------------
-- 上游清单也存一份模态:同步时从 architecture.input_modalities 取真值。
-- 聚合网关(OpenRouter / OneAPI 等)会给,官方 OpenAI 的 /models 不给 —— 给不出时
-- 存 null,导入界面回退按模型名推测并提示人工核对。
-- null(上游未提供)与 ''(上游明确说纯文本)含义不同,不要合并。
-- ---------------------------------------------------------------------------
alter table ai_upstream_model
  add column input_modalities varchar(64) default null comment '上游声明的输入模态(逗号分隔;null=上游未提供)' after source;

-- 已有清单没有这个信息,重新同步一次渠道即可回填:
--   渠道管理 → 对应渠道 → 同步模型
