<template>
  <!-- 统一 16 viewBox / 1.25 描边，与 step__icon 的 15px 容器和既有图标风格对齐 -->
  <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
    <!-- 读:文档 -->
    <template v-if="kind === 'read'">
      <path d="M9.2 1.8H4.6A1.6 1.6 0 0 0 3 3.4v9.2a1.6 1.6 0 0 0 1.6 1.6h6.8a1.6 1.6 0 0 0 1.6-1.6V5.6z"
            stroke="currentColor" stroke-width="1.25" stroke-linejoin="round"/>
      <path d="M9.2 1.8v3.8H13" stroke="currentColor" stroke-width="1.25" stroke-linejoin="round"/>
    </template>

    <!-- 写:铅笔 -->
    <template v-else-if="kind === 'write'">
      <path d="M11.3 2.2l2.5 2.5-7.4 7.4-3.1.6.6-3.1z"
            stroke="currentColor" stroke-width="1.25" stroke-linejoin="round"/>
      <path d="M10 3.5l2.5 2.5" stroke="currentColor" stroke-width="1.25" stroke-linecap="round"/>
    </template>

    <!-- 执行:终端 -->
    <template v-else-if="kind === 'shell'">
      <rect x="2" y="3" width="12" height="10" rx="1.6"
            stroke="currentColor" stroke-width="1.25"/>
      <path d="M4.8 6.6L6.9 8.2 4.8 9.8M8.8 10.2h2.6"
            stroke="currentColor" stroke-width="1.25" stroke-linecap="round" stroke-linejoin="round"/>
    </template>

    <!-- 搜索:放大镜 -->
    <template v-else-if="kind === 'search'">
      <circle cx="7.2" cy="7.2" r="4.1" stroke="currentColor" stroke-width="1.25"/>
      <path d="M10.3 10.3l3 3" stroke="currentColor" stroke-width="1.25" stroke-linecap="round"/>
    </template>

    <!-- 查询/清单:列表 -->
    <template v-else-if="kind === 'list'">
      <path d="M6.2 4.2h7M6.2 8h7M6.2 11.8h7"
            stroke="currentColor" stroke-width="1.25" stroke-linecap="round"/>
      <path d="M3.2 4.2h.01M3.2 8h.01M3.2 11.8h.01"
            stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/>
    </template>

    <!-- 定时任务:时钟 -->
    <template v-else-if="kind === 'schedule'">
      <circle cx="8" cy="8" r="5.6" stroke="currentColor" stroke-width="1.25"/>
      <path d="M8 4.8V8l2.2 1.6" stroke="currentColor" stroke-width="1.25" stroke-linecap="round" stroke-linejoin="round"/>
    </template>

    <!-- MCP:插头(沿用改造前的图形，用户已建立认知) -->
    <template v-else-if="kind === 'mcp'">
      <path d="M6 2v4M10 2v4M4.5 6h7v3.2a3.5 3.5 0 0 1-7 0z"
            stroke="currentColor" stroke-width="1.25" stroke-linecap="round" stroke-linejoin="round"/>
      <path d="M8 12.7V14.5" stroke="currentColor" stroke-width="1.25" stroke-linecap="round"/>
    </template>

    <!-- 兜底:通用工具(沿用改造前的图形) -->
    <template v-else>
      <path d="M9.8 2.6a2.9 2.9 0 0 0 3.6 3.6l-7 7a1.7 1.7 0 0 1-2.4-2.4z"
            stroke="currentColor" stroke-width="1.25" stroke-linejoin="round"/>
    </template>
  </svg>
</template>

<script setup>
defineProps({
  /** 图标类型，由 useStepDisplay.toolIconKind 推导 */
  kind: { type: String, default: 'default' }
})
</script>
