# 知识库引用 UI 产物 Implementation Plan

> **落地状态(2026-08-25)**:功能已落地,随后被「特殊事件通道」实施计划改成独立表 `ai_chat_special_event`。`ChatRunProjectionService` 对 `type=ui` 直接 return,不再写 `ai_chat_run_step`。载荷现为 schema v2。
>
> **For agentic workers:** 本会话按 A 方案直接落地（用户已确认实施）。图片仍走 `AttachmentAware`，不改。

**Goal:** 知识库引用不再靠前端扒 `searchKnowledge` 的 `tool_end.result`，改为正式 `ui` 事件（`kb.references`），实时与 Run 快照可恢复；旧历史仍可回退解析。

**Architecture:** 在现有 `ChatRunEventBroker` 上新增封闭信封 `type=ui`。工具通过 `UiArtifactAware` 声明产物，`RecordingToolCallback` 在 `tool_end` 之后发出。投影成独立 `stepType=ui` 步骤（不进时间线）。前端按 `eventId` 幂等合并到 `turn.citations`。

**Tech Stack:** Spring Boot 3 / JUnit 5 / Vue 3 composables / 现有 ChatEventJson + ChatRunProjectionService

## Global Constraints

- 不进 `DbChatMemory` / 不改 `formatForModel`（LLM 仍读带 `[n]` 的文本）
- `name` 白名单，第一期只有 `kb.references`
- 锚点：`eventId = producerStepId + ":" + name`，`parentStepId = 工具 stepId`
- 图片继续 `AttachmentAware` + `tool_end.attachments`
- 旧会话无 ui 步骤时，`collectKbCitations` 仍 `parseKbHits`

---

### Task 1: `ui` 事件信封

**Files:**
- Modify: `ruoyi-system/src/main/java/com/ruoyi/system/ai/sse/ChatEventJson.java`
- Test: `ruoyi-system/src/test/java/com/ruoyi/system/ai/sse/ChatEventJsonTest.java`

- [x] ChatEventJson.ui + 单测

### Task 2: 工具声明 UI 产物

**Files:**
- Create: `UiArtifact` / `UiArtifactAware` / `UiArtifactNames`
- Modify: `KnowledgeSearchToolCallback`
- Test: `KnowledgeSearchToolCallbackTest` / `KbReferencesUiPayloadTest`

- [x] 命中后 `lastArtifacts()` 给出 `kb.references`
- [x] 空命中 / 未选库不声明

### Task 3: RecordingToolCallback 发出

**Files:**
- Modify: `RecordingToolCallback.java`
- Test: `RecordingToolCallbackUiArtifactTest.java`

- [x] `tool_end` 之后 emit `ui`，未知 name 丢弃

### Task 4: Run 投影

**Files:**
- Modify: `ChatRunProjectionService.java`
- Test: `ChatRunProjectionServiceTest.java`

- [x] `type=ui` 落 `stepType=ui`，`outputData=payload JSON`，不覆盖工具步骤

### Task 5: 前端消费

**Files:**
- `types/chat.js`, `useChatRun.js`, `useTurnBuilder.js`

- [x] `EVENT_TYPES.UI`；实时不再扒 `tool_end`
- [x] 快照里 ui 步骤挂回工具，不进时间线
- [x] 无产物时回退 `parseKbHits`

### Task 6: 文档

- [x] `docs/流式与事件模块.md`、`docs/知识库模块.md`
