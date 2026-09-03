<template>
  <div class="search-panel">
    <div class="search-panel__head">
      <h2 class="search-panel__title">测试知识库</h2>
      <p class="search-panel__hint">用自然语言提问，查看命中的文件与原文片段。</p>
    </div>

    <div class="search-panel__form">
      <el-input
        v-model="query"
        type="textarea"
        :rows="3"
        placeholder="输入问题，例如：如何重置密码？"
        maxlength="500"
        show-word-limit
        @keydown.ctrl.enter="runSearch"
      />
      <div class="search-panel__actions">
        <button
          type="button"
          class="search-panel__run"
          :disabled="searching || !query.trim()"
          @click="runSearch"
        >{{ searching ? '测试中…' : '测试' }}</button>
        <button
          v-if="canManage"
          type="button"
          class="search-panel__adv-toggle"
          @click="showAdvanced = !showAdvanced"
        >{{ showAdvanced ? '收起高级设置' : '高级设置' }}</button>
      </div>

      <div v-if="canManage && showAdvanced" class="search-panel__advanced">
        <div class="search-panel__ctrl">
          <label>模式</label>
          <el-radio-group v-model="mode" size="small">
            <el-radio-button value="auto">Auto</el-radio-button>
            <el-radio-button value="basic">Basic</el-radio-button>
            <el-radio-button value="local">Local</el-radio-button>
            <el-radio-button value="hybrid">Hybrid</el-radio-button>
            <el-radio-button value="global">Global</el-radio-button>
            <el-radio-button value="drift">DRIFT</el-radio-button>
          </el-radio-group>
        </div>
        <div class="search-panel__ctrl">
          <label>topK · {{ topK }}</label>
          <el-slider v-model="topK" :min="1" :max="20" :step="1" />
        </div>
        <div class="search-panel__ctrl">
          <label>最低分 · {{ minScore.toFixed(2) }}</label>
          <el-slider v-model="minScore" :min="0" :max="1" :step="0.05" />
        </div>
      </div>
    </div>

    <div v-if="result && canManage" class="search-panel__result-meta">
      模式 <b>{{ result.mode || mode }}</b> · 命中 <b>{{ result.total }}</b> 条 · 耗时 <b>{{ result.took }}</b> ms
    </div>
    <div v-else-if="result" class="search-panel__result-meta">
      命中 <b>{{ result.total }}</b> 条<span v-if="result.took != null"> · 耗时 <b>{{ result.took }}</b> ms</span>
    </div>

    <div v-loading="searching" class="search-panel__hits">
      <article
        v-for="(h, i) in (result && result.hits) || []"
        :key="h.chunkId || i"
        class="hit-card"
        :class="{ 'is-clickable': !!h.docId }"
        @click="onHitClick(h)"
      >
        <div class="hit-card__head">
          <span class="hit-card__rank">{{ productRankLabel(i) }}</span>
          <span
            class="hit-card__doc"
            :class="{ 'is-link': !!h.docId }"
          >《{{ h.docName || '未知文档' }}》</span>
          <span v-if="h.headingPath" class="hit-card__path">{{ h.headingPath }}</span>
          <span v-if="canManage && h.channel" class="hit-card__channel">{{ h.channel }}</span>
          <span v-if="canManage" class="hit-card__score">{{ formatScore(h.score) }}</span>
        </div>
        <div class="hit-card__body">{{ h.content }}</div>
      </article>

      <div v-if="result && result.total === 0" class="search-panel__empty">
        <p>没有找到相关内容</p>
        <ul>
          <li>检查文件是否已处理为「可用」</li>
          <li>补充更相关的资料后再试</li>
          <li>换一种问法或更具体的问题</li>
        </ul>
      </div>
    </div>
  </div>
</template>

<script setup>
import { searchKb } from '@/api/ai/kb'

const props = defineProps({
  kbId: { type: [Number, String], required: true },
  access: {
    type: Object,
    default: () => ({})
  }
})

const emit = defineEmits(['open-document'])
const { proxy } = getCurrentInstance()

const canManage = computed(() => !!props.access?.canManage)

const query = ref('')
const topK = ref(5)
const minScore = ref(0.3)
const mode = ref('auto')
const showAdvanced = ref(false)
const searching = ref(false)
const result = ref(null)

function formatScore(s) {
  if (s == null) return '—'
  return Number(s).toFixed(3)
}

function productRankLabel(i) {
  if (i === 0) return '最佳匹配'
  return '相关内容'
}

function runSearch() {
  if (!query.value.trim()) return
  searching.value = true

  const payload = canManage.value
    ? {
        query: query.value.trim(),
        topK: topK.value,
        minScore: minScore.value,
        mode: mode.value,
        debug: false
      }
    : {
        query: query.value.trim(),
        mode: 'auto',
        debug: false
      }

  searchKb(props.kbId, payload).then(res => {
    result.value = res.data || { hits: [], took: 0, total: 0 }
    searching.value = false
  }).catch(err => {
    searching.value = false
    proxy.$modal.msgError(err?.msg || err?.message || '测试失败')
  })
}

function onHitClick(h) {
  if (!h?.docId) return
  emit('open-document', h.docId)
}

watch(() => props.kbId, () => {
  result.value = null
})
</script>

<style scoped lang="scss">
@use '../../../../assets/styles/ai-tokens.scss' as *;

.search-panel {
  border: 1px solid var(--ai-border);
  border-radius: 16px;
  background: var(--ai-card-bg);
  padding: 20px 22px;
  min-height: 480px;
  font-family: $font;
  box-shadow: 0 1px 2px var(--ai-fill-2);
}
.search-panel__head { margin-bottom: 14px; }
.search-panel__title {
  margin: 0 0 4px;
  font-size: 16px;
  font-weight: 700;
  color: $text;
}
.search-panel__hint {
  margin: 0;
  font-size: 13px;
  color: $text2;
  line-height: 1.45;
}
.search-panel__form {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-bottom: 16px;
}
.search-panel__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
}
.search-panel__run {
  height: 38px;
  padding: 0 22px;
  border: none;
  border-radius: 980px;
  background: $blue;
  color: #fff;
  font-size: 14px;
  font-weight: 500;
  font-family: $font;
  cursor: pointer;
  box-shadow: 0 2px 10px rgba(10,132,255,0.32);
  transition: all 0.2s $ease;
  &:hover:not(:disabled) { background: #0071e3; }
  &:disabled { opacity: 0.5; cursor: not-allowed; }
}
.search-panel__adv-toggle {
  border: none;
  background: transparent;
  color: $blue;
  font-size: 13px;
  cursor: pointer;
  padding: 0 4px;
  &:hover { text-decoration: underline; }
}
.search-panel__advanced {
  display: grid;
  grid-template-columns: 1fr;
  gap: 12px;
  padding: 12px 14px;
  border-radius: 12px;
  background: var(--ai-fill-1);
  border: 1px solid var(--ai-border);
}
.search-panel__ctrl {
  label {
    display: block;
    font-size: 12px;
    font-weight: 600;
    color: $text2;
    margin-bottom: 4px;
  }
}
.search-panel__result-meta {
  font-size: 13px;
  color: $gray;
  margin-bottom: 12px;
  b { color: $text; font-variant-numeric: tabular-nums; }
}
.search-panel__hits {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 120px;
}
.search-panel__empty {
  padding: 36px 20px;
  text-align: left;
  color: $gray;
  font-size: 13.5px;
  p {
    margin: 0 0 8px;
    font-weight: 600;
    color: $text;
    text-align: center;
  }
  ul {
    margin: 0 auto;
    max-width: 360px;
    padding-left: 1.2em;
    line-height: 1.7;
  }
}

.hit-card {
  border: 1px solid var(--ai-border-2);
  border-radius: 12px;
  padding: 12px 14px;
  background: var(--ai-fill-1);
  &.is-clickable {
    cursor: pointer;
    &:hover { border-color: rgba(10, 132, 255, 0.35); background: rgba(10, 132, 255, 0.04); }
  }
  &__head {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 8px;
    margin-bottom: 8px;
  }
  &__rank {
    font-size: 12px;
    font-weight: 700;
    color: $blue;
  }
  &__doc {
    font-size: 13px;
    font-weight: 600;
    color: $text;
    &.is-link { color: $blue; }
  }
  &__path {
    font-size: 12px;
    color: $gray;
  }
  &__channel {
    font-size: 11px;
    padding: 1px 6px;
    border-radius: 4px;
    background: var(--ai-fill-2);
    color: $gray;
  }
  &__score {
    margin-left: auto;
    font-size: 12px;
    font-variant-numeric: tabular-nums;
    color: $gray;
  }
  &__body {
    font-size: 13px;
    line-height: 1.55;
    color: $text2;
    white-space: pre-wrap;
    word-break: break-word;
  }
}
</style>
