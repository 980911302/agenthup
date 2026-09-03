# ai-memory 记忆系统设计文档 v2

> 日期:2026-08-29
> 状态:**已落地**(批次1-4 全量合入:`65d5c60` 地基 → `03e10b3` 全链路 → `1e06cd2` 向量跟随全局配置 → `15e173e` 统一落库入口+位点同步+失败退避)
> 范围:跨会话长期记忆系统。**全自动:系统侧提炼 + 系统侧检索注入**,agent 无任何记忆工具;第一版只做向量通道,图预留接口不实现。
> 版本:v2(v1 为「纯工具级 + Neo4j 双写 + 独立 Maven 模块」,已推翻,变更理由见 §2)
> 实现位置:`ruoyi-system/.../ai/memory/longterm/` 包 + `ChatTurnRunner` 读侧注入 + `ContextCompactor` 写侧搭车 + `sql/init/10-12`;落地现状与差异见 `docs/上下文与记忆模块.md §7`。

---

## 0. 背景与目标

现有记忆只是**会话内**消息流(`ai_chat_message` + 三层上下文压缩),agent 跨会话即"失忆"。本设计新增**跨会话长期记忆**,让 agent 记住"关于用户的事实与偏好",并在后续对话中自动可用。

核心定位:

- **全自动**:记忆的写(提炼)与读(检索注入)都由系统在链路上完成,不依赖 agent 自觉。
- **对 agent 完全不可见**:没有任何记忆工具。agent 看到的只是注入进来的一段背景文字,像 system prompt 的一部分。**记忆不是 agent 的能力,是平台的能力。**
- **两层记忆**:**用户层**(跨 agent 复用的用户事实)+ **用户 × agent 层**(某个 agent 专属)。`userId` 永远强制,分层只在 agent 维度上放松 —— 跨用户串号始终是红线(§6)。
- **只增不删**:覆盖走 supersede,保留审计;物理删除只留给合规清理。

---

## 1. 关键决策记录(v2)

| # | 决策 | 选择 | 理由 |
|---|---|---|---|
| 1 | 记忆粒度 | **两层**:用户层(`agent_id=0`)+ 用户×agent 层 | 单层会让同一条用户事实在 N 个 agent 下存 N 份,冗余且不一致;新 agent 冷启动记忆为零 |
| 1b | 层级默认 | 提炼**默认落 agent 层**,判定为「换个 agent 也成立」才升用户层 | 错放 agent 层只是少共享一点,错放用户层污染所有 agent —— 保守方向明确 |
| 2 | 存取触发 | **全自动,零工具** | agent 不会自觉存也不会自觉搜;工具还要为一条几乎不走的路径长期付 schema token 与攻击面,见 §2 |
| 3 | 写侧时机 | **压缩搭车提炼 + 空闲会话兜底** | 边际成本≈0,且覆盖长短会话 |
| 4 | 读侧时机 | **每轮自动检索,注入本轮 user 前** | 不破前缀 KV-cache;零 LLM 调用 |
| 5 | 存储通道 | **第一版只做 pgvector**,图留接口 NoOp | 读侧链路一次都查不到图;可逆,以后能补 |
| 6 | 多租户范式 | 照抄知识库:租户键进接口签名第一参数 | 编译期防串号,比"记得加 where"可靠 |
| 7 | 向量表 | 按维度分表 + 运行时取 `embedding.length` | 不锁死 embedding 模型,同 KB |
| 8 | 模块落位 | `ruoyi-system/.../ai/memory` 包 | 见 §3.1 |
| 9 | 写权限 | **agent 无任何写权限**,全部系统侧 | 记忆层 LLM 完全够不到,提示词注入无路径 |
| 10 | 覆盖语义 | 事实并存(时间线) + 偏好 supersede | 去过北京→湖南并存;喜欢→不喜欢苹果失效 |

---

## 2. v1 → v2 变更与理由

保留 v1 的决策历史,避免以后重复讨论同样的问题。

| v1 决策 | v2 改成 | 为什么翻案 |
|---|---|---|
| 纯工具级,agent 自主存取 | 全自动,零工具 | **写侧失败不可逆**:agent 在任务中间不会分心做元认知,漏存没有任何错误反馈,而 `ContextCompactor` 一压缩原文就被摘要替换,事实永久蒸发。写不触发 → 库是空的 → 读怎么做都无所谓 → 整个模块变死代码 |
| 不做自动提炼(YAGNI) | 压缩搭车提炼为主路径 | 成本远低于 v1 设想:压缩本来就要读全量历史调一次 LLM,让它顺带吐事实清单,边际成本≈0 |
| 不做注入,agent 自主检索 | 每轮自动检索注入 | v1 担心注入破坏消息前缀 —— **该判断有误**。`ChatTurnRunner` 的顺序是 `[system] + [历史] + [本轮 user]`,拼进本轮 user 文本前面,前缀一字节不变,KV-cache 照常命中 |
| Neo4j 双写,图与向量并行 | 第一版只做向量,图留 NoOp 接口 | 读侧链路(每轮向量检索注入)**一次都不会查到图**。且此事可逆:正文留在台账,以后要图,全量重跑抽取即可 |
| 图与知识库图"独立 database"物理隔离 | 不适用(第一版无图);未来用独立标签 | **实测现网 Neo4j 是 5.26.29 Community**,只有 `neo4j` / `system` 两个库,社区版建不了第二个用户库。未来开图必须用 `:MemEntity` / `:Memory` 独立标签 —— 现网 `:Entity` 已有 26457 节点,其唯一约束 `(kbId, entityKey)` 会**跳过属性为 null 的节点**,记忆实体混进去等于完全不受约束,重复节点无限堆积 |
| 独立 Maven 模块 `ai-memory`,依赖 `ai-kb` | 落 `ruoyi-system` 包内 | `ai-kb` / `ai-tool` / `ai-runtime` 目前都是空壳(各只有 1 个文件),`EmbeddingModelFactory`、`KbEntityExtractor`、`PgVectorKbVectorStore` 全在 `ruoyi-system`。且 v2 的写侧要挂 `ContextCompactor`、读侧要改 `ChatTurnRunner`,耦合比 v1 更深,独立模块必然反向依赖 `ruoyi-system` 形成环 |
| `mem_vector` 单表 `vector(1536)` | 按维度分表 `mem_vector_{768,1024,1536,3072}` | 写死 1536 等于锁死一个 embedding 模型,换 bge-m3(1024)存量全废且无迁移路径 |
| 保留 `save_memory` / `search_memory` / `update_memory` 三工具作补充 | **三个工具全部移除** | 既然认定 agent 不会自觉调用,就没有理由为这条路径长期买单:(a) 工具 schema 出现在每一轮每一次请求里,永久占 token;(b) 工具是 LLM 唯一能碰到记忆层的路径,移除后「租户键不得进 schema」「update 归属校验」这类防护**不再是防住了,而是路径根本不存在**;(c) 少一套注册、预算、测试。缺口见 §11 |
| 单层 `agent × user` 隔离 | **两层:用户层 + 用户×agent 层** | 单层下"用户在北京工作"跟 10 个 agent 聊过就存 10 份,某份被 supersede 后其余 9 份仍是旧值;换新 agent 记忆从零开始。分层只放松 agent 维度,`userId` 强制不变 |
| `mem_vector` 带 `status` 列 | 不带,supersede 直接删向量行 | 台账保留历史即可;向量表少一个列、少一条级联更新,检索天然只见 active |
| `memory_id` 为 UUID VARCHAR | `bigint auto_increment` | 与 `ai_agent.agent_id`、`kb_knowledge.kb_id` 等现有约定一致;且 id 给 LLM 看,短 id 省 token。可枚举不构成风险 —— 归属校验挡住越权(§9) |

---

## 3. 架构与落位

### 3.1 模块落位

第一版落 `ruoyi-system/src/main/java/com/ruoyi/system/ai/memory/`,与 `ai/context`、`ai/metering` 平级。

> **这是一个可推翻的假设。** 选它是因为:(a) `ai-kb` 等模块目前是空壳,没有可复用的东西;(b) v2 的写侧挂在 `ContextCompactor`、读侧改 `ChatTurnRunner`,两者都在 `ruoyi-system`,拆成独立模块会形成循环依赖。等模块化整体推进时,记忆跟 kb 一起搬。

```
ai/memory/
├── MemoryService.java            业务门面:隔离、去重、supersede、命中回写
├── MemoryStore.java              台账读写(租户键第一参数)
├── MemoryVectorStore.java        接口(租户键第一参数)
├── PgMemoryVectorStore.java      pgvector 实现,按维度分表
├── MemoryGraphStore.java         接口,第一版不实现
├── NoOpMemoryGraphStore.java     占位实现(照 NoOpKbGraphStore)
├── MemoryExtractor.java          提炼:压缩搭车 + 兜底扫描共用
├── MemoryRetriever.java          读侧:检索 + 注入文本组装
├── MemoryInjectionBudget.java    注入 token 硬顶
└── IdleSessionExtractScheduler.java  空闲会话兜底扫描
```

> **实现差异(落地后)**:上图中的 `MemoryStore` 接口在实现时**未单独建**,台账读写直接由 `AiMemoryMapper`(MyBatis mapper)+ `MemoryServiceImpl` 承担;`MemoryInjectionBudget` 之外还实际新增了 `MemoryQueryVectorizer` / `EmbeddingMemoryQueryVectorizer` / `MemoryEmbeddingModelResolver` / `IdleSessionExtractProgressStore` / `InMemoryMemoryVectorStore`(单测兜底)。完整差异见 `docs/上下文与记忆模块.md §7.4`。

### 3.2 接入点

| 接入点 | 改动 |
|---|---|
| `ChatTurnRunner.buildInitialMessages` / `buildInitialMessagesForRun` | 读侧注入;**必须拆分发送版与落库版**(§7.1) |
| `ContextCompactor.compactIfNeeded` | 写侧搭车:PROMPT 改造 + 事实解析 |
| `EmbeddingModelFactory` | 复用现有 embedding 链路 |
| `AgentAssemblyCache` 同款 | 空库短路标志的 TTL 快照(按 `userId` 缓存,覆盖两层) |

---

## 4. 存储模型

### 4.1 `ai_memory`(台账,MySQL 主库)

唯一事实源,向量表从它派生。

```
memory_id          bigint(20)    auto_increment PK
user_id            bigint(20)    not null    隔离维度(永远强制)
agent_id           bigint(20)    not null    0=用户层;>0=该 agent 专属层
type               varchar(20)   fact|preference|event|goal|rule
content            text          记忆正文
status             varchar(20)   active|superseded
superseded_by      bigint(20)    null        被哪条覆盖
source             varchar(20)   提炼来源(当前恒为 auto,预留)
source_session_id  varchar(64)   来源会话(可溯源)
source_message_id  bigint(20)    提炼覆盖到的消息位点
content_hash       varchar(64)   正文归一化后哈希,精确去重
embedding_dim      int(11)       落在哪张向量表(删除/重建用)
embedding_model    varchar(100)  用了哪个模型
hit_count          int(11)       被检索命中次数
last_hit_time      datetime      最近命中时间
create_time        datetime      时间线语义基准
update_time        datetime
del_flag           char(1)       0存在 2删除(合规清理用)

key idx_mem_tenant     (user_id, agent_id, status, del_flag)
key idx_mem_hash       (user_id, agent_id, content_hash)
key idx_mem_superseded (superseded_by)
```

> **为什么用哨兵 `0` 而不是 `NULL` 表示用户层**:`ai_agent` 的 `auto_increment=100`,0 绝不会与真实 agent 冲突;而 NULL 在唯一索引与等值过滤里语义特殊(前面已在 Neo4j「唯一约束跳过 null 属性」上吃过一次亏,§2),哨兵值明确得多。索引把 `user_id` 放最左,因为它才是永远强制的那一维。

> `source` / `hit_count` / `last_hit_time` 三个字段是**可观测性的地基**:没有它们,你无法回答"提炼出来的记忆到底有没有被用上""哪些会话贡献了记忆",也就无法判断这套系统是否有效。

### 4.2 `mem_vector_{768,1024,1536,3072}`(PostgreSQL,pgvector)

照抄 `kb_vector_*` 的分表范式。

```sql
create table if not exists mem_vector_1024 (
  memory_id bigint primary key,
  user_id   bigint not null,          -- 永远强制
  agent_id  bigint not null,          -- 0=用户层;>0=agent 专属层
  embedding vector(1024) not null
);
create index if not exists idx_mem_vec_1024_hnsw
  on mem_vector_1024 using hnsw (embedding vector_cosine_ops);
create index if not exists idx_mem_vec_1024_tenant
  on mem_vector_1024 (user_id, agent_id);
```

- 维度**不配置**,运行时从 `embedding.length` 取,再路由到对应表 —— 同 `PgVectorKbVectorStore:55`。不在 `{768,1024,1536,3072}` 直接抛。
- **3072 维无 HNSW**(pgvector 索引上限 2000 维),只能全表扫,但带 `(agent_id,user_id)` 过滤后可控 —— 同 KB。
- 无 `status` 列:supersede 时直接删向量行,检索天然只见 active,历史留在台账。
- 删除(合规清理)遍历四个维度删 —— 同 `deleteByKbId`,因为用户可能换过 embedding 模型,向量散在多张表。

### 4.3 图(第一版不实现)

`MemoryGraphStore` 接口按 §6 范式定义好,装配 `NoOpMemoryGraphStore`。未来开图时:

- 标签 `:Memory` / `:MemEntity`,**不复用 `:Entity`**(理由见 §2)
- 复合唯一约束 `(agentId, userId, memoryId)`,自建全文索引,不蹭 `entity_name_ft`
- 边也带 `agentId` / `userId` —— 同 `MERGE (a)-[rel:RELATED {kbId: $kbId, ...}]->(b)`

---

## 5. 记忆类型

| 类型 | 含义 | 例子 | 生命周期 |
|---|---|---|---|
| `fact` | 用户/世界的稳定客观陈述 | "用户在北京工作" | 长期,时间线并存 |
| `preference` | 喜好/风格/约束 | "回复要简洁" | 长期,可被覆盖 |
| `event` | 已发生的具体事件 | "6月项目X上线" | 时间线 |
| `goal` | 进行中的事 | "在准备Q3融资" | 中短期,完成后由提炼器 supersede |
| `rule` | 做事指令 | "这类问题先给结论" | 长期 |

> v1 遗留问题修正:`goal` 的"完成即失活"在纯工具级下无落地路径(无 delete、无过期机制)。v2 由自动提炼解决 —— 提炼器看到"融资已完成"时直接 supersede 掉旧 goal,不需要额外的完成信号。

---

## 6. 多租户隔离范式(照抄知识库)

知识库面对的是同一个约束(社区版无法分库),它的答案是**逻辑隔离 + 编译期强制**。记忆系统照抄,把 `kbId` 换成分层租户键。

### 6.1 `MemoryTenant`:把分层收进一个值对象

```java
/** userId 永远强制;agentId=0 表示用户层。构造即校验,不给非法组合留缝。 */
public record MemoryTenant(Long userId, Long agentId) {
    public static final long USER_SCOPE = 0L;

    public MemoryTenant {
        Objects.requireNonNull(userId, "userId 不可为空 —— 跨用户隔离是红线");
        if (agentId == null) agentId = USER_SCOPE;
    }
    public static MemoryTenant ofUser(Long userId)            { return new MemoryTenant(userId, USER_SCOPE); }
    public static MemoryTenant ofAgent(Long userId, Long aid) { return new MemoryTenant(userId, aid); }
    public boolean isUserScope() { return agentId == USER_SCOPE; }
}
```

### 6.2 租户键仍然是每个 store 方法的第一参数

```java
public interface MemoryVectorStore {
    void upsert(MemoryTenant tenant, Long memoryId, float[] embedding);
    /** 同时检索用户层与该 agent 层:where user_id=? and agent_id in (0, ?) */
    List<MemoryHit> searchLayered(Long userId, Long agentId, float[] query, int topK, double minScore);
    void delete(MemoryTenant tenant, List<Long> memoryIds);
    /** 合规清理:抹掉该用户在所有层的全部记忆 */
    void deleteByUser(Long userId);
}
```

这样想写一个漏掉租户过滤的查询,**在类型层面就写不出来**。这是 `KbGraphStore` 的手法(它每个方法第一参数都是 `Long kbId`),比"记得加 where"可靠一个数量级。分层没有削弱它 —— `MemoryTenant` 构造时就挡掉了 `userId` 为空。

同一条规则适用于 `MemoryStore` / `MemoryGraphStore`。

### 6.3 三条分层规则

1. **检索一次 SQL 查两层**:`where user_id=? and agent_id in (0, ?)`,合并后按相似度统一排序取 top-k。不做两次查询再手工合并。
2. **冲突时 agent 层赢**。用户层说"回复简洁"、agent 层说"这个 agent 要详尽" —— 更具体的胜出。这是**遮蔽**,不删用户层那条:注入时同语义的只取 agent 层那份。
3. **agent 层不能 supersede 用户层**。否则单个 agent 能污染所有 agent 共享的记忆。supersede 只在同层内发生。

---

## 7. 读侧:每轮自动检索注入

### 7.1 发送版与落库版必须拆开(实现红线)

`ChatTurnRunner:316-327` 目前把**同一个 `userMessage` 对象**既发给模型、又落库:

```java
UserMessage userMessage = ub.build();
messages.add(userMessage);                              // 发给模型
chatMemory.add(context.conversationId(), userMessage);  // 落 ai_chat_message
```

直接把记忆拼进 `userText` 会有两层后果:

1. **审计流污染** —— 用户翻历史看到自己没说过的话,而保住 `ai_chat_message` 审计流正是设计决策 #2 的目的
2. **注入内容永久沉进历史** —— 下轮 `chatMemory.get()` 读回来,轮轮累积;记忆被 supersede 后,历史里那份过时版本还在跟新版本打架

**做法**:注入只进发送给模型的那份,落库存用户原话。`buildInitialMessages` / `buildInitialMessagesForRun` 两处都要改。

顺序不变(`[system] + [历史] + [本轮 user]`),`ChatTurnMessageOrderTest` 锁的前缀不受影响,KV-cache 照常命中。

### 7.2 五条规则

| # | 规则 | 理由 |
|---|---|---|
| 1 | **每轮都检索**,不做"注入一次"优化 | 注入不落库,下轮就消失了;好处是 query 跟着本轮话题走,比开头注入一批固定的更准 |
| 2 | **空库短路** | 绝大多数会话库是空的(新用户)。缓存 `userId → hasMemory` 标志(**按 userId 而非 agent —— 分层后用户层可能有货而该 agent 层为空**),空则整条链路跳过,连 embedding 都不发。照 `AgentAssemblyCache` 的 30s TTL + 事件失效 |
| 3 | **相似度阈值优先于 top_k** | 纯 top-k 会强行凑数把不相关记忆硬塞进去,那比不注入更糟 —— 会误导模型。低于阈值宁可返回空。top_k 取 3~5,阈值偏严 |
| 4 | **短消息不检索** | "嗯""继续""好的"的 embedding 无信息量,检索出的全是噪声。低于最小长度门槛直接跳过 |
| 5 | **注入要有边界和来源标注** | 否则模型会把它当成用户本轮说的话 |

注入格式:

```
<user_memory>
以下是系统检索到的该用户已知背景,非用户本轮输入:
- [preference] 回复要简洁
- [fact] 用户在北京工作
</user_memory>

{用户原话}
```

注入文本**不标注层级**。用户层还是 agent 层是系统的存储细节,模型不需要知道,标了反而诱导它去推理"哪条更权威"。层级冲突在检索侧就按 §6.3 规则 2 消解完了,送到模型眼前的应该是一份已经定稿的背景。

### 7.3 预算与观测

- 注入内容有 token 硬顶,超出按相似度截断;计入 `ContextBudget`(它占的是本轮预算)。
- 命中即回写 `hit_count` / `last_hit_time`(异步,不阻塞主链路)。

### 7.4 成本

每轮 **1 次 embedding + 1 次向量查询,零 LLM 调用**,几十毫秒;空库短路后基本为零。

---

## 8. 写侧:自动提炼

### 8.1 主力:压缩搭车

`ContextCompactor.compactIfNeeded` 本来就要 `renderHistory` 全量历史 + 调一次 LLM 出摘要(`:144-148`)。改造它的 `PROMPT`,让同一次调用**同时输出摘要与事实清单**:

```
<summary>
{前情提要正文}
</summary>
<facts>
[{"content":"用户在北京工作","type":"fact"},
 {"content":"回复要简洁","type":"preference"}]
</facts>
```

**关键约束:摘要优先解析,facts 失败不能影响摘要。**

压缩是关键路径(它同步阻塞在每轮首 token 之前),记忆是搭车的 —— **搭车的不能掀翻车**。解析必须按标签分别提取:`<facts>` 缺失或 JSON 畸形时,降级为"这次没提炼出事实",摘要照常落库。绝不允许一次解析失败同时丢掉摘要。

**时机天然正确**:压缩正是"这段历史即将被摘要替换、原文永久消失"的时刻,是抢救事实的最后窗口。

**落库必须走 `MemoryExtractor.persistFacts`,不许自己调 `MemoryService.add`。**

`MemoryService.add` 只写台账。绕开统一入口就会漏掉三样:**向量**(读侧是纯向量检索,没有向量的台账行永远查不出来)、**`content_hash`**(后续提炼认不出重复)、**去重与 supersede 判定**。第一版实现踩过这个坑 —— 搭车提炼的记忆全部不可检索,而测试只断言了台账内容所以没发现。修法不是补三行,是让两条写入路径共用同一个落库入口:两套并行的落库逻辑迟早再次分叉。

**提炼位点:复用压缩的 `boundaryId`,并且必须同步推进空闲扫描的位点。** 压缩自己有 `recorder.latestSummary()` → `fromId` 的位点机制,但那是摘要位点,与 `ai_memory_extract_progress` 是两套。搭车提炼后不推进后者,30 分钟后兜底扫描看到位点还是旧的,会把同一段历史再提炼一遍。

> 位点推进统一用 `greatest(旧值, 新值)`:两条路径都会推,搭车的 `boundaryId` 可能小于扫描已推到的 `latest`,直接覆盖会让位点**回退**,反而造成重复提炼。

**延迟代价(要如实记账)**:压缩调用是同步的,多出的 facts 输出 token 会加到首 token 延迟上,只在压缩触发的那些轮次。给开关 `ai.memory.extract.piggyback-compaction`,压力大时可关,退化为纯兜底扫描。

### 8.2 兜底:空闲会话扫描

压缩只在 `used > threshold` 时触发,**短会话可能一次都压不到**。所以兜底不是补充,是另一半主力。

- 扫描条件:N 分钟无新消息、提炼位点 < 最新 messageId、活跃未删除、普通对话、有主 agent
- "空闲"的基准是 `ai_chat_session.update_time` —— 每条消息落表(`addSessionMessageCount`)与每次 token 记账都会刷新它,所以不必额外维护 `last_message_time`。代价是改标题、异步记账落得晚也会刷新它,空闲判定相应推迟
- 提炼成功即推进位点、退出候选;**内容没变不会重扫**(判据是 `max(message_id) > 位点`)
- **实现照 `AiJobReconciler`**:`@PostConstruct` + 单线程 `ScheduledExecutorService` + daemon 线程,**不用 `@Scheduled`** —— 原因见该类注释:启用 `@EnableScheduling` 是全局开关,且基础设施行为不该出现在若依定时任务界面里让人误停

**失败必须退避,否则会队头阻塞。**

提炼失败(LLM 超时/畸形输出/取不到模型)时位点不推进、下次重试。但"下次重试"若不带退避,稳定失败的会话会每个扫描周期都被捞出来一次,**永远**。而候选是 `order by update_time asc limit N` —— 这些卡住的老会话会永久占据名额前排,把新的待提炼会话挤出候选。

`ai_memory_extract_progress` 因此带 `fail_count` + `next_retry_time`:

- 失败一次 → 计数 +1,`next_retry_time = now + base * 2^(n-1)`(封顶 `max-backoff-minutes`)
- 候选 SQL 排除退避中(`next_retry_time > now`)与已放弃(`fail_count >= max-failures`)的会话
- 成功即清零

> SQL 细节:MySQL 的 `ON DUPLICATE KEY UPDATE` 赋值**自左向右求值**,`next_retry_time` 必须排在 `fail_count` 自增**之前**才读得到本次之前的计数,否则退避整体偏一档。

### 8.3 层级判定:这条事实进哪一层

判据只有一句:**换个 agent 这条还成立吗?**

| 例子 | 层级 | 为什么 |
|---|---|---|
| "用户在北京工作" | 用户层 | 换哪个 agent 都成立 |
| "用户是 Java 后端" | 用户层 | 同上 |
| "回复要简洁" | 用户层 | 是用户对交流方式的偏好,与 agent 无关 |
| "让这个客服 agent 用更正式的语气" | agent 层 | 明确指向某个 agent |
| "用户在本 agent 里的工单号是 X" | agent 层 | 只在该 agent 语境下有意义 |

**默认落 agent 层。** 提炼器判不出、或判定置信度不足时一律进 agent 层,只有明确判定为"与 agent 无关的用户事实"才升到用户层。

> 保守方向是刻意的:错放进 agent 层的代价只是少共享一点(以后还能升层);错放进用户层会污染该用户的所有 agent,而且**记忆一旦流进用户层被别的 agent 读过就撤不回来**。

> **已接受的风险**:不做 per-agent 的用户层开关。这意味着敏感 agent(如心理咨询类)没有隔离出口 —— 它提炼出的用户层事实会被该用户的其他 agent 读到。判断依据是默认落 agent 层已经把多数敏感内容挡在本层。若将来平台上出现明确的敏感 agent,再加 `ai_agent` 开关;届时**已经流出的记忆收不回来**,需要人工清理用户层。

### 8.4 覆盖判定与去重(提炼质量的关键)

提炼器不能只会新增,否则库很快被近重复塞满。流程:

```
提炼出候选事实 f
  1. content_hash 精确命中已有 → 丢弃
  2. 分层检索(用户层 + 本 agent 层)相关的已有记忆 M
  3. f 与 M 中某条相似度 > 去重阈值 → 丢弃(视为同一事实)
  4. 提炼器判定 f 与 m 矛盾/是其更新 → m.status=superseded, m.superseded_by=f.id
  5. 否则 → 新增 active
```

第 2 步意味着**提炼的 prompt 要能看到已有的相关记忆**,否则它无法判断"覆盖还是新增"。检索结果一并喂给提炼器。

其他护栏:

- 每次提炼产出上限 N 条,防止一次塞几十条垃圾
- 条数上限**分层各算**:用户层一个上限、每个 agent 层一个上限;超限按 `last_hit_time` 最旧的先失活
- 提炼失败/超时:安静跳过,位点不推进,下次重试
- **supersede 只在同层内发生**(§6.3 规则 3),agent 层不得覆盖用户层

### 8.5 子 agent 归属

子 agent 触发的提炼一律记到**主 agent** 名下。子 agent 是主 agent 的执行细节,不是独立的记忆主体。

---

## 9. 隔离与安全(合规红线)

1. **LLM 完全够不到记忆层。** 没有记忆工具,agent 无法读取指定 id、无法写入、无法指定 `agentId` / `userId`。它只能被动接收系统注入的文本。v1 里"租户键不得进 tool schema""`update_memory` 必须校验归属"这两条防护在 v2 **不再是防住了,而是路径根本不存在** —— 这是移除工具最大的收益。
2. **租户键全程由服务端持有**:`agentId` 来自 `AgentContext`,`userId` 来自会话身份(`SessionAccessGuard` / `OperatorContext` 体系),不经过任何模型可影响的输入。
3. **分层只放松 agent 维度,`userId` 永远强制。** 用户层(`agent_id=0`)是同一用户跨自己 agent 的共享,**不是跨用户共享**。任何查询都必须带 `user_id` 等值条件;`MemoryTenant` 构造时即校验非空(§6.1)。这是分层设计里最容易写错、也最不能写错的一条 —— 漏掉 `user_id` 条件,用户层就变成全平台共享。
4. **所有 store 接口租户键在第一参数**(§6),编译期兜底。
5. **注入内容是数据不是指令**:检索出的记忆正文来自历史对话,可能含用户写的、看起来像指令的文本。注入模板必须显式声明它是背景资料(§7.2 规则 5),且注入位置在本轮 user 内容之前、不进 system。
6. **第一版无任何对外端点** —— v1 的 `GET /ai/memory/graph?agentId=&userId=` 已随图通道一起移除。该端点原本存在 IDOR(userId 来自 query 参数,改个数字就能看别人的记忆)。未来若加展示页,`userId` 只能从登录态取,不接受入参。
7. **合规清理**:`del_flag` + `deleteByUser` 遍历四个维度删向量,**抹掉该用户在所有层的全部记忆**(含用户层)。支撑"删除我的数据"类请求。这是系统能力,不对 agent 暴露。

---

## 10. 降级与错误处理

| 失败点 | 行为 |
|---|---|
| 提炼 facts 解析失败 | 摘要照常落库,本次不提炼(**绝不因此丢摘要**) |
| 提炼 LLM 超时 | 安静跳过,位点不推进,下次重试 |
| embedding 失败(写) | 台账已落,向量补偿任务重试;台账 `embedding_dim` 为空即待补 |
| embedding 失败(读) | 本轮不注入,正常对话继续 —— 记忆是增强,不是必需 |
| 向量检索失败 | 同上,不阻塞 |
| 记忆条数超限 | 按 `last_hit_time` 最旧的先失活 |

原则:**记忆全链路对主对话是旁路,任何环节失败都不许阻塞或拖死一轮对话。**

---

## 11. 为什么没有工具(以及缺口在哪)

v1 设计过 `save_memory` / `search_memory` / `update_memory` 三个工具,v2 全部移除。理由见 §2 变更表。这里只记录**移除带来的缺口**,免得以后有人当 bug 提。

### 缺口 1:显式"记住这个"不是即时生效

用户说"以后回复简短点",这条偏好要等到下次压缩或空闲扫描才入库(最坏约 5~35 分钟,取决于 `idle-sweep-minutes`)。

**为什么可以接受**:同一会话内,这句话就躺在对话历史里,模型本来就看得见,不存在功能缺失;只有"立刻新开一个会话"这个窄场景会短暂读不到。

**必须做的配套**:提炼 prompt 要把**用户显式的记忆请求**("记住…""以后都…""下次别…")列为最高优先级,确保它一定被提炼出来,而不是靠模型自由发挥。这是 §8 提炼质量的硬要求。

### 缺口 2:agent 答不了"你都记得我什么"

自动注入是按本轮 query 做相似度检索的。而"你都记得我什么"这句话的 embedding,跟"用户在北京工作"匹配不上 —— 恰恰是这个问题会检索失败。

**不用工具解决**。理由:靠意图识别去判断"这是不是在问记忆"很脆弱,误判成本高。正确的归宿是**只读展示页**——用户想知道系统记住了什么,应该去看一个列表,而不是问 agent 然后得到一个被 top-5 截断的答案。该页第一版不做(§15),但它才是这个需求的正确形态。

**若将来确实要在对话里回答**:加一个只读的 `list_memories` 比恢复三件套安全得多 —— 无写权限、无 id 入参、无归属校验需求。到那时再说。

### 缺口 3:agent 无法主动深挖历史记忆

比如"把用户所有 preference 类的记忆都拉出来"。第一版接受这个限制:自动注入的 top-k 覆盖绝大多数场景,深挖是低频需求。

---

## 12. 配置项

### 12.1 向量模型:跟随知识库全局配置

记忆**不单独维护一份向量模型配置**。平台的向量模型本来就是全局一份,存在 `sys_config` 的
`kb.default.embeddingModel`;知识库建库时由 `KbKnowledgeServiceImpl.applyPlatformEngineSnapshot`
把它**快照固化**到 `kb_knowledge.embedding_model_code`(固化是为了让已有库的向量不因平台改配置而作废,
不代表"每个库各配各的")。记忆没有建库动作,因此直接读全局值。

解析顺序由 `MemoryEmbeddingModelResolver` 实现:

```
ai.memory.embedding-model-code(显式覆盖,留空跳过)
  → sys_config 的 kb.default.embeddingModel(平台全局)
  → null(读侧不注入、写侧只剩 hash 去重,对话照常 —— 记忆是旁路)
```

保留显式覆盖是为了给"记忆想用比知识库更便宜/更小的模型"留口子,不填即跟随全局。

两个实现约束:

- **带 30s TTL 快照**:解析发生在每轮对话的读侧热路径上,不能每轮打一次 `sys_config`。TTL 与 `AgentAssemblyCache` / `ToolPolicyService` 同口径。
- **显式切 MASTER**:`sys_config` 在 MySQL 主库,而记忆读侧可能跑在 PG 数据源上下文里,取值须用 `DataSourceScope.runOn(MASTER, ...)` 包住(同 `KbKnowledgeServiceImpl`)。

### 12.2 完整配置

```yaml
ai:
  memory:
    enabled: true
    # 向量模型:留空 = 跟随 sys_config 的 kb.default.embeddingModel(推荐)
    # 填值 = 显式覆盖,仅在记忆要用不同于知识库的模型时才需要
    embedding-model-code: ""
    # 读侧
    retrieve:
      top-k: 5
      min-score: 0.75          # 阈值优先于 top-k,宁缺毋滥
      min-query-length: 8      # 低于此长度不检索("嗯""继续")
      max-inject-tokens: 500   # 注入硬顶,计入 ContextBudget
    # 写侧
    extract:
      piggyback-compaction: true   # 压缩搭车(主力),压力大时可关
      idle-sweep-minutes: 30       # 空闲多久触发兜底提炼
      sweep-interval-seconds: 300  # 扫描周期
      max-facts-per-run: 10        # 单次提炼产出上限
      dedup-threshold: 0.92        # 高于此相似度视为同一事实
      # 提炼失败退避(§8.2):不退避会让稳定失败的会话永久霸占候选名额
      max-failures: 5              # 连续失败几次后放弃,不再进候选
      retry-backoff-minutes: 10    # 第 n 次失败退避 base * 2^(n-1)
      max-backoff-minutes: 240     # 退避封顶
    # 容量(分层各算)
    max-per-user-scope: 300        # 用户层上限(agent_id=0)
    max-per-agent-scope: 300       # 每个 agent 层上限
```

> 读侧还有一个不走配置的进程内上限:`MemoryRetriever.HAS_MEMORY_MAX_ENTRIES`(空库短路缓存条数)。
> 该缓存只收「有记忆」的用户且无被动淘汰,长跑进程里只增不减,故加上限 + 惰性清理:
> 先清过期项,清完仍满则整体清空。不做 LRU —— 这是纯优化快照,清空的唯一后果是各用户下次各多探一次库。

---

## 13. 测试

- `MemoryTenantIsolationTest` —— 跨 `userId` 的读/写/更新全部拒绝,**用户层也不例外**(合规红线,优先级最高);`MemoryTenant` 拒绝空 `userId`
- `MemoryLayeringTest` —— 分层检索一次 SQL 命中两层;冲突时 agent 层遮蔽用户层且不删除;agent 层无法 supersede 用户层;条数上限分层各算
- `MemoryScopeDecisionTest` —— 判不出层级时默认落 agent 层(§8.3 保守方向);**未实现**(层级判定已收敛进 `MemoryExtractor.persistOne` 的 `scope` 字段)
- `MemoryVectorStoreTest` —— 按维度路由、不支持维度抛错、`deleteByUser` 遍历四维度且抹掉所有层
- `ContextCompactorMemoryPiggybackTest` —— **`<facts>` 畸形时摘要仍正常落库**(护住关键路径)
- `MemoryExtractorTest` —— 去重(hash + 相似度)、supersede 判定、产出条数上限;**`persistFacts` 必须写向量与 `content_hash`**(读侧纯向量检索,缺向量即不可检索)
- `ContextCompactorMemoryPiggybackTest` —— 除摘要降级外,还须断言**搭车走了统一入口**(`content_hash` 已回填)与**位点已推进到 `boundaryId`**
- `IdleSessionExtractSchedulerTest` —— 提炼跳过时必须记一次失败以触发退避(否则无限重试 + 队头阻塞)
- `MemoryRetrieverTest` —— 阈值过滤、短消息跳过、空库短路、注入 token 硬顶;**空库短路缓存受 `HAS_MEMORY_MAX_ENTRIES` 约束且清理后仍正确**
- `ChatTurnMemoryInjectionTest` —— **注入进发送版、不进 `ai_chat_message`**;消息顺序不变(与 `ChatTurnMessageOrderTest` 一致)
- `MemoryExplicitRequestTest` —— 用户显式说"记住…"时,提炼器必须产出对应记忆(§11 缺口 1 的配套);**未实现**(§11 缺口 1 目前靠提炼 prompt 里把显式记忆请求列为最高优先级,无独立测试锁定)
- 走真库集成测试(对齐 `ToolResultCapAlignmentTest` style);**未实现**(现有测试走 `ChatMessageMapperTestSupport` 内存/测试库,未建真库集成)

---

## 14. 实施顺序(已完成)

> 全部步骤已合入,本段保留为实施历史,不要按「待实施」再开工。

1. ✅ 建表(MySQL `ai_memory` + PG `mem_vector_*`)、`MemoryTenant` + `MemoryStore` / `MemoryVectorStore` + 租户隔离与分层测试 —— 批次1(`65d5c60`)
2. ✅ 读侧:`MemoryRetriever` + `ChatTurnRunner` 发送版/落库版拆分 + 注入 —— 批次2(`03e10b3`)
3. ✅ 写侧兜底:`IdleSessionExtractScheduler` + `MemoryExtractor`(去重与 supersede)—— 批次3(`03e10b3`)
4. ✅ 写侧主力:`ContextCompactor` 搭车改造(含畸形降级测试)—— 批次4(`03e10b3`)
5. ✅ 观测:`hit_count` / `source` 上报,回答"记忆到底有没有被用上" —— `MemoryRetriever.hit()` 异步回写(`03e10b3`)
6. ✅ 收口:向量模型跟随知识库全局配置 + 空库短路缓存上限(`1e06cd2`);统一落库入口 + 搭车位点同步 + 提炼失败退避(`15e173e`)

> 先做 2 再做 3/4 是刻意的:读侧先通,才能用真实数据验证提炼出的记忆有没有被检索命中。
> 落地现状与 spec 差异(如条数上限未实现、遮蔽语义未落地)见 `docs/上下文与记忆模块.md §7.4`。

---

## 15. 明确不做(YAGNI)

- ❌ 第一版写 Neo4j(读侧链路查不到图;可逆,以后全量重跑抽取即可)
- ❌ 图增强检索(等图通道启用后一起做)
- ❌ **任何 agent 可见的记忆工具**(save / search / update / delete 全不做,理由见 §2,缺口见 §11)
- ❌ per-agent 的用户层开关(敏感 agent 隔离出口)—— 已接受的风险,理由与代价见 §8.3
- ❌ 人工管理端点/界面
- ❌ 只读记忆列表页(§11 缺口 2 的正确归宿,但不在第一版)
- ❌ 每轮末提炼(多数轮次无可记之事,信噪比过低)
- ❌ 记忆过期/时间衰减(时间线 + 覆盖 + 条数上限已覆盖核心场景)
- ❌ 独立 Maven 模块(等模块化整体推进时跟 kb 一起搬)
