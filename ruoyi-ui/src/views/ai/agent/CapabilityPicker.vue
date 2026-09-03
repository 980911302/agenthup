<template>
  <!-- 完全没有可选数据时不摆出三栏空壳，给一句可执行的引导就够了 -->
  <div class="cap-picker cap-picker--void" v-if="!items.length">
    <div class="cap-void">
      <span class="cap-void__icon">📭</span>
      <p class="cap-void__text">{{ emptyText }}</p>
    </div>
  </div>

  <div class="cap-picker" v-else>
    <!-- 顶部：搜索 + 统计 -->
    <div class="cap-bar">
      <div class="cap-search">
        <svg class="cap-search__icon" width="14" height="14" viewBox="0 0 15 15" fill="none">
          <circle cx="6.5" cy="6.5" r="5.5" stroke="currentColor" stroke-width="1.4"/>
          <path d="M10.5 10.5L14 14" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"/>
        </svg>
        <input v-model="keyword" class="cap-search__input" :placeholder="searchPlaceholder" />
        <button v-if="keyword" type="button" class="cap-search__clear" aria-label="清除搜索" @click="keyword = ''">✕</button>
      </div>
      <div class="cap-bar__right">
        <span class="cap-stat"><b>{{ selected.length }}</b><i>/</i>{{ items.length }}</span>
        <button type="button" class="cap-toggle" :class="{ 'is-on': onlySelected }" @click="onlySelected = !onlySelected">仅看已选</button>
        <button type="button" class="cap-toggle" :disabled="!selected.length" @click="clearAll">清空</button>
      </div>
    </div>

    <div class="cap-main">
      <!-- 左：分组导航 -->
      <aside class="cap-rail">
        <button type="button" class="cap-rail__item" :class="{ 'is-active': activeGroup === ALL }" @click="activeGroup = ALL">
          <span class="cap-rail__name">全部</span>
          <span class="cap-rail__num">{{ items.length }}</span>
        </button>
        <button
          v-for="g in groups"
          :key="g.name"
          type="button"
          class="cap-rail__item"
          :class="{ 'is-active': activeGroup === g.name }"
          @click="activeGroup = g.name"
        >
          <i class="cap-dot" :style="{ background: g.color }"></i>
          <span class="cap-rail__name" :title="g.name">{{ g.name }}</span>
          <span class="cap-rail__num">{{ g.total }}</span>
          <em v-if="g.chosen" class="cap-rail__chosen">{{ g.chosen }}</em>
        </button>
      </aside>

      <!-- 右：可选列表 -->
      <div class="cap-list">
        <div class="cap-list__head">
          <span class="cap-list__title">{{ activeGroup === ALL ? '全部' : activeGroup }} · {{ visible.length }} 项</span>
          <span class="cap-list__ops" v-if="selectableVisible.length">
            <button type="button" class="cap-link" @click="setVisible(true)">全选</button>
            <span class="cap-list__div"></span>
            <button type="button" class="cap-link" @click="setVisible(false)">取消</button>
          </span>
        </div>

        <div class="cap-list__scroll">
          <label
            v-for="it in paged"
            :key="it.id"
            class="cap-item"
            :class="{ 'is-on': isSelected(it.id), 'is-locked': it.disabled && !isSelected(it.id) }"
          >
            <input
              type="checkbox"
              class="cap-item__input"
              :checked="isSelected(it.id)"
              :disabled="it.disabled && !isSelected(it.id)"
              @change="toggle(it.id)"
            />
            <span class="cap-item__check"></span>
            <span class="cap-item__body">
              <span class="cap-item__head">
                <i class="cap-dot cap-item__dot" :style="{ background: it.color }" :title="it.group"></i>
                <span class="cap-item__name" :title="it.name">{{ it.name }}</span>
                <em v-if="it.code" class="cap-item__code">{{ it.code }}</em>
                <em v-if="it.tag" class="cap-item__tag" :style="{ background: it.tagSoft, color: it.tagColor }">{{ it.tag }}</em>
                <em v-if="it.disabled" class="cap-item__off">已停用</em>
              </span>
              <span class="cap-item__desc" :title="it.desc">{{ it.desc || '暂无描述' }}</span>
            </span>
          </label>

          <div v-if="!visible.length" class="cap-blank">
            <span class="cap-blank__icon">{{ keyword || onlySelected ? '🔍' : '📭' }}</span>
            <p>{{ blankText }}</p>
          </div>

          <div v-if="visible.length > paged.length" class="cap-more">
            已显示前 {{ paged.length }} 项，共 {{ visible.length }} 项
            <button type="button" class="cap-link" @click="limit += PAGE">加载更多</button>
          </div>
        </div>
      </div>
    </div>

    <!-- 底部：已选清单 -->
    <div class="cap-chosen" v-if="selectedItems.length">
      <div class="cap-chosen__head">
        <span class="cap-chosen__label">已选 {{ selectedItems.length }} 项</span>
        <span class="cap-chosen__hint" v-if="orderHint">{{ orderHint }}</span>
      </div>
      <div class="cap-chosen__list">
        <span class="cap-chip" v-for="(s, i) in selectedItems" :key="s.id" :style="{ borderColor: s.color, background: s.soft }">
          <em class="cap-chip__idx" v-if="orderHint">{{ i + 1 }}</em>
          <span class="cap-chip__name">{{ s.name }}</span>
          <button type="button" class="cap-chip__x" :aria-label="'移除 ' + s.name" @click="toggle(s.id)">✕</button>
        </span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { colorOf, softOf } from '@/utils/ai-palette'

const ALL = '__all__'
const PAGE = 120

const props = defineProps({
  modelValue: { type: Array, default: () => [] },
  items: { type: Array, default: () => [] },
  idKey: { type: String, required: true },
  nameKey: { type: String, required: true },
  codeKey: { type: String, default: '' },
  descKey: { type: String, default: 'description' },
  /** item => 分组名 */
  groupBy: { type: Function, default: it => it.category || '未分类' },
  /** item => 角标文案，返回空则不显示 */
  tagBy: { type: Function, default: () => '' },
  /** item => 是否不可新增（例如已停用），已选中的仍可展示和移除 */
  disabledBy: { type: Function, default: () => false },
  searchPlaceholder: { type: String, default: '搜索名称、编码或描述…' },
  emptyText: { type: String, default: '暂无可选项' },
  /** 有值时在已选清单上显示序号和顺序说明 */
  orderHint: { type: String, default: '' }
})

const emit = defineEmits(['update:modelValue'])

const keyword = ref('')
const onlySelected = ref(false)
const activeGroup = ref(ALL)
const limit = ref(PAGE)

const selected = computed(() => props.modelValue || [])

// 归一化：把外部的任意字段名收敛成统一结构，顺便算好颜色
const normalized = computed(() =>
  (props.items || []).map(it => {
    const group = props.groupBy(it) || '未分类'
    const tag = props.tagBy(it) || ''
    const name = it[props.nameKey] || ''
    return {
      id: it[props.idKey],
      name,
      code: props.codeKey ? it[props.codeKey] || '' : '',
      desc: props.descKey ? it[props.descKey] || '' : '',
      group,
      tag,
      tagColor: colorOf(tag),
      tagSoft: softOf(tag),
      color: colorOf(group),
      soft: softOf(group),
      disabled: !!props.disabledBy(it)
    }
  })
)

const byId = computed(() => {
  const m = new Map()
  normalized.value.forEach(it => m.set(it.id, it))
  return m
})

function isSelected(id) {
  return selected.value.includes(id)
}

// 分组：按「已选数 -> 总数 -> 名称」排，常用分组自然浮到上面
const groups = computed(() => {
  const map = new Map()
  normalized.value.forEach(it => {
    if (!map.has(it.group)) map.set(it.group, { name: it.group, color: it.color, total: 0, chosen: 0 })
    const g = map.get(it.group)
    g.total++
    if (isSelected(it.id)) g.chosen++
  })
  return [...map.values()].sort(
    (a, b) => b.chosen - a.chosen || b.total - a.total || a.name.localeCompare(b.name)
  )
})

const visible = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return normalized.value.filter(it => {
    if (activeGroup.value !== ALL && it.group !== activeGroup.value) return false
    if (onlySelected.value && !isSelected(it.id)) return false
    if (!kw) return true
    return (
      it.name.toLowerCase().includes(kw) ||
      it.code.toLowerCase().includes(kw) ||
      it.desc.toLowerCase().includes(kw)
    )
  })
})

// 大列表下只渲染前 N 条，避免几百个 DOM 节点拖慢面板
const paged = computed(() => visible.value.slice(0, limit.value))

const selectableVisible = computed(() => visible.value.filter(it => !it.disabled || isSelected(it.id)))

const selectedItems = computed(() =>
  selected.value
    .map(id => byId.value.get(id) || { id, name: '已失效 #' + id, color: '#C7C7CC', soft: 'var(--ai-fill-2)' })
)

const blankText = computed(() => {
  if (onlySelected.value && !selected.value.length) return '还没有选择任何项'
  if (keyword.value) return '没有匹配「' + keyword.value + '」的结果'
  return props.emptyText
})

// 搜索/切分组时把分页重置，否则会停在上一次的 limit
watch([keyword, activeGroup, onlySelected], () => { limit.value = PAGE })

function emitValue(next) {
  emit('update:modelValue', next)
}

function toggle(id) {
  const cur = [...selected.value]
  const i = cur.indexOf(id)
  if (i > -1) cur.splice(i, 1)
  else cur.push(id)
  emitValue(cur)
}

function setVisible(on) {
  const ids = selectableVisible.value.map(it => it.id)
  if (on) {
    const cur = [...selected.value]
    ids.forEach(id => { if (!cur.includes(id)) cur.push(id) })
    emitValue(cur)
  } else {
    const drop = new Set(ids)
    emitValue(selected.value.filter(id => !drop.has(id)))
  }
}

function clearAll() {
  emitValue([])
}
</script>

<style scoped lang="scss">
@use '../../../assets/styles/ai-tokens.scss' as *;


.cap-picker {
  display: flex; flex-direction: column; min-height: 0; font-family: $font;
  border: 1px solid var(--ai-border-2); border-radius: 14px; background: var(--ai-card-bg); overflow: hidden;
  &--void {
    align-items: center; justify-content: center; flex: none; min-height: 160px;
    border-style: dashed; background: var(--ai-fill-1);
  }
}
.cap-void {
  text-align: center; padding: 30px 20px;
  &__icon { font-size: 26px; display: block; margin-bottom: 8px; opacity: 0.5; }
  &__text { margin: 0; font-size: 13px; color: $gray; }
}

/* 顶部条 */
.cap-bar {
  display: flex; align-items: center; gap: 12px; padding: 10px 12px; flex-shrink: 0;
  border-bottom: 1px solid var(--ai-fill-3);
  background: linear-gradient(180deg, var(--ai-block-bg), var(--ai-block-bg-2));
  &__right { display: flex; align-items: center; gap: 8px; margin-left: auto; flex-shrink: 0; }
}
.cap-search {
  position: relative; flex: 1; min-width: 0;
  &__icon { position: absolute; left: 11px; top: 50%; transform: translateY(-50%); color: $gray3; pointer-events: none; }
  &__input {
    width: 100%; height: 32px; padding: 0 28px 0 32px; border: 1px solid transparent; border-radius: 8px;
    background: var(--ai-fill-2); font-size: 13px; font-family: $font; color: $text; outline: none; transition: all 0.2s $ease;
    &::placeholder { color: $gray3; }
    &:focus { background: var(--ai-card-bg); border-color: $blue; box-shadow: 0 0 0 3px rgba(10,132,255,0.12); }
  }
  &__clear {
    position: absolute; right: 8px; top: 50%; transform: translateY(-50%); width: 16px; height: 16px;
    border: none; border-radius: 50%; background: $gray3; color: #fff; font-size: 8px; cursor: pointer;
    display: flex; align-items: center; justify-content: center; &:hover { background: $gray; }
  }
}
.cap-stat {
  font-size: 12px; color: $gray; white-space: nowrap;
  b { font-size: 14px; font-weight: 700; color: $blue; }
  i { font-style: normal; margin: 0 2px; color: $gray3; }
}
.cap-toggle {
  border: 1px solid var(--ai-hover-strong); background: var(--ai-card-bg); color: $text2; font-family: $font;
  font-size: 12px; padding: 5px 10px; border-radius: 7px; cursor: pointer; transition: all 0.2s $ease; white-space: nowrap;
  &:hover:not(:disabled) { border-color: $blue; color: $blue; }
  &.is-on { background: rgba(10,132,255,0.1); border-color: rgba(10,132,255,0.35); color: $blue; font-weight: 500; }
  &:disabled { opacity: 0.4; cursor: not-allowed; }
}

/* 主体 */
.cap-main { flex: 1; min-height: 0; display: flex; }

/* 左侧分组 */
.cap-rail {
  width: 158px; flex-shrink: 0; border-right: 1px solid var(--ai-fill-3);
  padding: 8px; overflow-y: auto; background: var(--ai-block-bg);
  &::-webkit-scrollbar { width: 4px; }
  &::-webkit-scrollbar-thumb { background: var(--ai-border-4); border-radius: 2px; }
  &__item {
    position: relative; width: 100%; display: flex; align-items: center; gap: 7px;
    padding: 7px 9px; border: none; border-radius: 8px; background: transparent;
    font-family: $font; font-size: 13px; color: $text2; cursor: pointer; text-align: left;
    transition: all 0.18s $ease;
    &:hover { background: var(--ai-fill-2); color: $text; }
    &.is-active { background: var(--ai-card-bg); color: $text; font-weight: 600; box-shadow: 0 1px 4px var(--ai-fill-4); }
  }
  &__name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  &__num { font-size: 11px; color: $gray3; font-variant-numeric: tabular-nums; }
  &__chosen {
    font-style: normal; font-size: 10px; font-weight: 700; color: #fff; background: $blue;
    min-width: 16px; height: 16px; padding: 0 4px; border-radius: 980px;
    display: inline-flex; align-items: center; justify-content: center;
  }
  @media (max-width: 640px) { width: 118px; }
}
.cap-dot { width: 7px; height: 7px; border-radius: 50%; flex-shrink: 0; display: inline-block; }

/* 右侧列表 */
.cap-list { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.cap-list__head {
  display: flex; align-items: center; justify-content: space-between; gap: 10px;
  padding: 8px 14px; border-bottom: 1px solid var(--ai-border); flex-shrink: 0;
}
.cap-list__title { font-size: 12px; font-weight: 600; color: $text2; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.cap-list__ops { display: flex; align-items: center; gap: 8px; flex-shrink: 0; }
.cap-list__div { width: 1px; height: 10px; background: var(--ai-border-4); }
.cap-link {
  border: none; background: none; color: $blue; font-family: $font; font-size: 12px;
  cursor: pointer; padding: 0; &:hover { text-decoration: underline; }
}
.cap-list__scroll {
  flex: 1; min-height: 0; overflow-y: auto; padding: 6px;
  &::-webkit-scrollbar { width: 5px; }
  &::-webkit-scrollbar-thumb { background: var(--ai-border-4); border-radius: 3px; }
}

/* 单项 */
.cap-item {
  position: relative; display: flex; align-items: flex-start; gap: 10px;
  padding: 7px 11px 7px 9px; border-radius: 9px; cursor: pointer; transition: background 0.15s $ease;
  border: 1px solid transparent;
  &:hover { background: rgba(0,0,0,0.028); }
  &.is-on { background: rgba(10,132,255,0.06); border-color: rgba(10,132,255,0.22); }
  &.is-locked { opacity: 0.45; cursor: not-allowed; }
  &__input { position: absolute; opacity: 0; width: 0; height: 0; }
  &__check {
    width: 16px; height: 16px; border-radius: 5px; border: 1.5px solid $gray3; flex-shrink: 0;
    margin-top: 2px; transition: all 0.18s $ease; position: relative; background: var(--ai-card-bg);
  }
  &.is-on &__check {
    background: $blue; border-color: $blue;
    &::after {
      content: ''; position: absolute; left: 4.5px; top: 1.5px; width: 4px; height: 8px;
      border: solid var(--ai-card-bg); border-width: 0 2px 2px 0; transform: rotate(45deg);
    }
  }
  /* 分类色点：比整块头像轻，长列表里不会变成一条重复的色带 */
  &__dot { margin-right: 1px; }
  &__body { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 1px; }
  &__head { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
  &__name { font-size: 13.5px; font-weight: 500; color: $text; }
  &__code { font-style: normal; font-family: $mono; font-size: 10.5px; color: $gray; background: var(--ai-fill-2); padding: 1px 5px; border-radius: 4px; }
  &__tag { font-style: normal; font-size: 10px; font-weight: 600; padding: 1px 6px; border-radius: 4px; }
  &__off { font-style: normal; font-size: 10px; font-weight: 600; padding: 1px 6px; border-radius: 4px; background: rgba(142,142,147,0.14); color: $gray; }
  &__desc {
    font-size: 12px; color: $text2; line-height: 1.45;
    display: -webkit-box; -webkit-line-clamp: 1; -webkit-box-orient: vertical; overflow: hidden;
  }
}

.cap-blank {
  text-align: center; padding: 44px 20px; color: $gray;
  &__icon { font-size: 28px; display: block; margin-bottom: 8px; opacity: 0.55; }
  p { margin: 0; font-size: 13px; }
}
.cap-more { text-align: center; padding: 10px; font-size: 12px; color: $gray; display: flex; align-items: center; justify-content: center; gap: 8px; }

/* 底部已选 */
.cap-chosen {
  flex-shrink: 0; border-top: 1px solid var(--ai-fill-3); padding: 9px 12px 11px;
  background: linear-gradient(180deg, var(--ai-block-bg), var(--ai-block-bg-2));
  &__head { display: flex; align-items: baseline; gap: 8px; margin-bottom: 7px; }
  &__label { font-size: 11.5px; font-weight: 600; color: $text2; }
  &__hint { font-size: 11px; color: $gray; }
  &__list { display: flex; flex-wrap: wrap; gap: 6px; max-height: 76px; overflow-y: auto;
    &::-webkit-scrollbar { width: 4px; }
    &::-webkit-scrollbar-thumb { background: var(--ai-border-4); border-radius: 2px; } }
}
.cap-chip {
  display: inline-flex; align-items: center; gap: 5px; font-size: 12px; color: $text;
  border: 1px solid; border-radius: 980px; padding: 3px 5px 3px 9px; max-width: 100%;
  &__idx { font-style: normal; font-size: 10px; font-weight: 700; color: $gray; font-variant-numeric: tabular-nums; }
  &__name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  &__x {
    width: 15px; height: 15px; flex-shrink: 0; border: none; border-radius: 50%;
    background: var(--ai-fill-4); color: $text2; font-size: 8px; cursor: pointer;
    display: flex; align-items: center; justify-content: center; transition: all 0.15s;
    &:hover { background: #FF3B30; color: #fff; }
  }
}
</style>
