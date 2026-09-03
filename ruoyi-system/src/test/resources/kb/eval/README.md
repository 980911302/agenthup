# GraphRAG 评测基线（阶段 0 / KB-GR-01）

本目录提供**可重复、不依赖付费 LLM** 的解析与检索评测资产。

## 目录

| 路径 | 说明 |
|------|------|
| `corpus/` | 脱敏文本语料（md/html/csv/json）；office/pdf 由测试确定性物化 |
| `expected-ir/` | 期望 IR 结构摘要（标题/类型/子串/页码规则，非整段快照） |
| `queries.json` | ≥40 条人工可核对问题（local/multi-hop/global/table/negative） |

## 运行

```bash
mvn -pl ruoyi-system -am test -Dtest=ParserCorpusTest,RetrievalMetricTest,EvalMetricsTest
```

报告输出到 `ruoyi-system/target/kb-eval/`（勿提交）。

## 指标

离线检索使用**词法余弦（确定性）** 作为 `vector` 代理基线；`graph` 使用语料内实体-chunk 映射扩展；`mix` 为 RRF 融合。

记录：

- Recall@5 / Recall@10（有 gold 的查询）
- MRR、nDCG@10
- 引用命中率（top1 命中任一 gold 锚点）
- p50/p95 延迟（纳秒计时）
- embedding/LLM 调用次数（离线路径恒为 0）

同一代码 + 同一语料重复跑，指标应完全一致。

## 语料虚构设定

虚构公司「星河科技 / Xinghe Tech」，不含真实隐私数据。
