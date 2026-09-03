<template>
  <div class="model-page">
    <!-- 页面标题 -->
    <header class="model-header">
      <div class="model-header__left">
        <h1 class="model-header__title">模型管理</h1>
        <span class="model-header__count">{{ total }} 个</span>
      </div>
      <div class="model-header__actions">
        <button type="button" class="apple-btn apple-btn--add" @click="handleImportOpen" v-hasPermi="['ai:model:import']">
          <svg width="14" height="14" viewBox="0 0 14 14" fill="none"><path d="M7 1v12M1 7h12" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/></svg>
          导入模型
        </button>
      </div>
    </header>

    <!-- 搜索栏 -->
    <div class="model-search">
      <div class="model-search__field">
        <svg class="model-search__icon" width="15" height="15" viewBox="0 0 15 15" fill="none">
          <circle cx="6.5" cy="6.5" r="5.5" stroke="currentColor" stroke-width="1.4"/>
          <path d="M10.5 10.5L14 14" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"/>
        </svg>
        <input v-model="queryParams.modelCode" class="model-search__input" placeholder="搜索模型编码…" @keyup.enter="handleQuery" />
        <button type="button" v-if="queryParams.modelCode" class="model-search__clear" @click="queryParams.modelCode = ''; handleQuery()">✕</button>
      </div>
      <input v-model="queryParams.displayName" class="model-search__input model-search__input--mid" placeholder="按展示名称" @keyup.enter="handleQuery" />
      <select v-model="queryParams.modelType" class="model-select" @change="handleQuery">
        <option value="">全部类型</option>
        <option v-for="t in MODEL_TYPES" :key="t.value" :value="t.value">{{ t.label }}</option>
      </select>
      <select v-model="queryParams.status" class="model-select" @change="handleQuery">
        <option value="">全部状态</option>
        <option v-for="dict in sys_normal_disable" :key="dict.value" :value="dict.value">{{ dict.label }}</option>
      </select>
      <button type="button" class="apple-btn apple-btn--ghost" @click="resetQuery">重置</button>
    </div>

    <!-- 卡片列表 -->
    <div v-loading="loading" class="model-grid">
      <article
        v-for="item in modelList"
        :key="item.modelId"
        class="model-card"
        :class="{ 'is-off': item.status !== '0' }"
        :style="{ '--accent': colorOf(item.modelType || item.modelCode) }"
        @click="handleDetail(item)"
      >
        <span class="model-card__rail"></span>
        <div class="model-card__head">
          <div class="model-card__ident">
            <h3 class="model-card__name" :title="item.displayName">{{ item.displayName }}</h3>
            <div class="model-card__sub">
              <span class="model-card__code">{{ item.modelCode }}</span>
              <span class="model-card__status" :class="item.status === '0' ? 'is-on' : 'is-off'">
                <i></i>{{ item.status === '0' ? '已启用' : '已停用' }}
              </span>
            </div>
          </div>
          <div class="model-card__actions">
            <button type="button" class="model-card__action" title="编辑" @click.stop="handleUpdate(item)" v-hasPermi="['ai:model:edit']">
              <svg width="15" height="15" viewBox="0 0 16 16" fill="none"><path d="M11.5 2.5l2 2L5 13H3v-2l8.5-8.5z" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/></svg>
            </button>
            <button type="button" class="model-card__action model-card__action--danger" title="删除" @click.stop="handleDelete(item)" v-hasPermi="['ai:model:remove']">
              <svg width="15" height="15" viewBox="0 0 16 16" fill="none"><path d="M3 4.5h10M6.5 2.5h3M4.5 4.5l.5 9h6l.5-9M6.5 7v4M9.5 7v4" stroke="currentColor" stroke-width="1.2" stroke-linecap="round" stroke-linejoin="round"/></svg>
            </button>
          </div>
        </div>

        <div class="model-card__props">
          <span class="model-card__chip" :style="{ background: softOf(item.modelType || ''), color: colorOf(item.modelType || '') }">
            <i class="model-card__chip-dot" :style="{ background: colorOf(item.modelType || '') }"></i>
            {{ typeLabel(item.modelType) }}
          </span>
          <span
            class="model-card__chip model-card__chip--scope"
            :class="item.visibility === 'PRIVATE' ? 'is-private' : 'is-public'"
          >
            {{ item.visibility === 'PRIVATE' ? '私人' : '公开' }}
          </span>
          <span class="model-card__prop"><span class="model-card__prop-k">上下文</span><b>{{ formatTokens(item.contextWindow) }}</b></span>
          <span class="model-card__prop"><span class="model-card__prop-k">输出</span><b>{{ formatTokens(item.maxOutputTokens) }}</b></span>
          <span class="model-card__prop" v-if="item.reasoningEnabled === '1'">
            <span class="model-card__prop-k">推理</span><b class="model-card__reason">支持</b>
          </span>
          <span class="model-card__prop" v-if="item.modelType === 'CHAT' && modalityLabels(item.inputModalities).length">
            <span class="model-card__prop-k">输入</span><b class="model-card__vision">{{ modalityLabels(item.inputModalities).join('·') }}</b>
          </span>
        </div>

        <p v-if="item.remark" class="model-card__remark" :title="item.remark">备注：{{ item.remark }}</p>

        <!-- 底部供应入口：常驻可见的实色按钮，从卡片直接进供应管理（双层） -->
        <button type="button" class="model-card__supply-btn" @click.stop="openSupplyFromCard(item)" v-hasPermi="['ai:model:edit']">
          <svg width="14" height="14" viewBox="0 0 16 16" fill="none"><path d="M2.5 4.5h11M4 4.5l.7 8.2a1 1 0 0 0 1 .93h4.6a1 1 0 0 0 1-.93L13 4.5M6.5 4.5V3a1 1 0 0 1 1-1h1a1 1 0 0 1 1 1v1.5" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/></svg>
          管理供应
        </button>
      </article>

      <!-- 空态 -->
      <div v-if="!loading && modelList.length === 0" class="model-empty">
        <div class="model-empty__icon">🧠</div>
        <p class="model-empty__text">还没有模型，先从上游渠道拉一个</p>
        <button type="button" class="apple-btn apple-btn--add" @click="handleImportOpen" v-hasPermi="['ai:model:import']">导入第一个模型</button>
      </div>
    </div>

    <!-- 分页 -->
    <div v-show="total > 0" class="model-pagination">
      <pagination :total="total" v-model:page="queryParams.pageNum" v-model:limit="queryParams.pageSize" @pagination="getList" />
    </div>

    <!-- ==================== 详情面板（只读） ==================== -->
    <Teleport to="body">
      <Transition name="sheet">
        <div v-if="detailOpen" class="sheet-overlay" @click.self="closeDetail">
          <div class="sheet sheet--detail" role="dialog" aria-modal="true" aria-label="模型详情">
            <div class="hero" :style="{ background: detail.status === '0' ? gradientOf(detail.modelType || detail.modelCode) : offGradient }">
              <button type="button" class="hero__close" aria-label="关闭" @click="closeDetail">✕</button>
              <div class="hero__body">
                <div class="hero__avatar">{{ typeEmoji(detail.modelType) }}</div>
                <div class="hero__text">
                  <h2 class="hero__name">{{ detail.displayName }}</h2>
                  <div class="hero__sub">
                    <span class="hero__code">{{ detail.modelCode }}</span>
                    <span class="hero__status" :class="detail.status === '0' ? 'is-on' : 'is-off'">
                      <i :class="detail.status === '0' ? 'is-on' : 'is-off'"></i>{{ detail.status === '0' ? '已启用' : '已停用' }}
                    </span>
                  </div>
                </div>
              </div>
              <div class="hero__stats">
                <div class="hero__stat"><b>{{ typeLabel(detail.modelType) }}</b><span>类型</span></div>
                <div class="hero__stat"><b>{{ formatTokens(detail.contextWindow) }}</b><span>上下文</span></div>
                <div class="hero__stat"><b>{{ detail.sort ?? 0 }}</b><span>排序</span></div>
              </div>
            </div>

            <div class="sheet__body">
              <!-- 关键属性 + 描述（主列） -->
              <div class="detail-cols">
                <div class="detail-main">
                  <div class="detail-block">
                    <div class="detail-block__title">能力参数</div>
                    <dl class="detail-kv detail-kv--two">
                      <div class="detail-kv__row">
                        <dt>类型</dt>
                        <dd>
                          <span class="chip" :style="{ background: softOf(detail.modelType || ''), color: colorOf(detail.modelType || '') }">
                            <i class="chip__dot" :style="{ background: colorOf(detail.modelType || '') }"></i>
                            {{ typeLabel(detail.modelType) }}
                          </span>
                        </dd>
                      </div>
                      <template v-if="detail.modelType === 'CHAT'">
                        <div class="detail-kv__row">
                          <dt>思考模式</dt>
                          <dd :class="{ 'is-on': detail.reasoningEnabled === '1' }">{{ detail.reasoningEnabled === '1' ? '已开启' : '已关闭' }}</dd>
                        </div>
                        <div class="detail-kv__row">
                          <dt>输入模态</dt>
                          <dd :class="{ 'is-on': modalityLabels(detail.inputModalities).length > 0 }">
                            {{ modalityLabels(detail.inputModalities).join('、') || '纯文本' }}
                          </dd>
                        </div>
                      </template>
                      <div class="detail-kv__row">
                        <dt>上下文</dt>
                        <dd>{{ formatTokens(detail.contextWindow) }} tokens</dd>
                      </div>
                      <div class="detail-kv__row">
                        <dt>最大输出</dt>
                        <dd>{{ formatTokens(detail.maxOutputTokens) }} tokens</dd>
                      </div>
                    </dl>
                  </div>

                  <div class="detail-block" v-if="detail.remark">
                    <div class="detail-block__title">备注</div>
                    <div class="detail-prompt detail-prompt--static">{{ detail.remark }}</div>
                  </div>
                </div>

                <!-- 侧列：基础信息（供应管理已移至卡片按钮，详情只看模型本身） -->
                <aside class="detail-side">
                  <div class="detail-block">
                    <div class="detail-block__title">基础信息</div>
                    <dl class="detail-kv">
                      <div class="detail-kv__row"><dt>创建</dt><dd :title="detail.createTime">{{ formatTime(detail.createTime) }}</dd></div>
                      <div class="detail-kv__row"><dt>更新</dt><dd :title="detail.updateTime">{{ formatTime(detail.updateTime) }}</dd></div>
                      <div class="detail-kv__row" v-if="detail.createBy"><dt>创建人</dt><dd>{{ detail.createBy }}</dd></div>
                    </dl>
                  </div>
                </aside>
              </div>
            </div>

            <div class="sheet__footer">
              <button type="button" class="apple-btn apple-btn--ghost" @click="closeDetail">关闭</button>
              <button type="button" class="apple-btn apple-btn--primary" @click="editFromDetail" v-hasPermi="['ai:model:edit']">编辑</button>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>

    <!-- ==================== 编辑面板 ==================== -->
    <Teleport to="body">
      <Transition name="sheet">
        <div v-if="open" class="sheet-overlay" @click.self="cancel">
          <div class="sheet" role="dialog" aria-modal="true" aria-label="编辑模型">
            <div class="sheet__header">
              <h2 class="sheet__title">{{ title }}</h2>
              <button type="button" class="sheet__close" aria-label="关闭" @click="cancel">✕</button>
            </div>

            <div class="sheet__body">
              <el-form ref="modelRef" :model="form" :rules="rules" label-position="top" class="aform" @submit.prevent>
                <!-- 基本信息 -->
                <div class="aform__group">
                  <div class="aform__row">
                    <el-form-item label="模型编码" prop="modelCode" class="aform__item">
                      <el-input v-model="form.modelCode" placeholder="提交后自动生成" disabled />
                    </el-form-item>
                    <el-form-item label="展示名称" prop="displayName" class="aform__item">
                      <el-input v-model="form.displayName" placeholder="对外展示用，如 DeepSeek V3" />
                    </el-form-item>
                  </div>
                  <el-form-item label="模型类型" prop="modelType">
                    <el-select v-model="form.modelType" placeholder="请选择" style="width: 100%">
                      <el-option v-for="t in MODEL_TYPES" :key="t.value" :label="t.label" :value="t.value" />
                    </el-select>
                  </el-form-item>
                  <el-form-item label="可见范围" prop="visibility">
                    <el-select v-model="form.visibility" placeholder="请选择" style="width: 100%">
                      <el-option label="公开（所有用户可选）" value="PUBLIC" />
                      <el-option label="私人（仅创建者可选）" value="PRIVATE" />
                    </el-select>
                  </el-form-item>
                  <div class="aform__row">
                    <el-form-item label="上下文长度 (tokens)" prop="contextWindow" class="aform__item">
                      <el-input-number v-model="form.contextWindow" :min="1" :step="1024" controls-position="right" style="width: 100%" />
                    </el-form-item>
                    <el-form-item label="最大输出 (tokens)" prop="maxOutputTokens" class="aform__item">
                      <el-input-number v-model="form.maxOutputTokens" :min="1" :step="1024" controls-position="right" style="width: 100%" />
                    </el-form-item>
                  </div>
                </div>

                <!-- 状态 + 推理 + 排序 -->
                <div class="aform__group aform__group--toggles">
                  <template v-if="form.modelType === 'CHAT'">
                    <div class="toggle-row">
                      <div class="toggle-row__info">
                        <span class="toggle-row__label">开启思考</span>
                        <span class="toggle-row__hint">开启后请求并展示推理；关闭后不记录或展示思考内容</span>
                      </div>
                      <el-switch v-model="form.reasoningEnabled" active-value="1" inactive-value="0" />
                    </div>
                    <div class="toggle-row toggle-row--stack">
                      <div class="toggle-row__info">
                        <span class="toggle-row__label">输入模态</span>
                        <span class="toggle-row__hint">模型能接收哪几种输入。全不选＝纯文本；选错会让整轮请求被上游拒绝</span>
                      </div>
                      <div class="modality-grid">
                        <button
                          v-for="m in INPUT_MODALITIES"
                          :key="m.value"
                          type="button"
                          class="modality-card"
                          :class="{ 'is-on': formModalities.includes(m.value), 'is-warn': m.warn }"
                          :aria-pressed="formModalities.includes(m.value)"
                          @click="toggleModality('form', m.value)"
                        >
                          <span class="modality-card__icon">{{ m.icon }}</span>
                          <span class="modality-card__body">
                            <b>{{ m.label }}</b>
                            <em>{{ m.hint }}</em>
                          </span>
                          <span class="modality-card__tick">
                            <svg viewBox="0 0 12 12" width="12" height="12" fill="none"><path d="M2.5 6.2l2.4 2.4L9.5 4" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/></svg>
                          </span>
                        </button>
                      </div>
                    </div>
                  </template>
                  <div class="toggle-row">
                    <div class="toggle-row__info">
                      <span class="toggle-row__label">排序</span>
                      <span class="toggle-row__hint">数字越小越靠前</span>
                    </div>
                    <el-input-number v-model="form.sort" :min="0" controls-position="right" />
                  </div>
                  <div class="toggle-row">
                    <div class="toggle-row__info">
                      <span class="toggle-row__label">启用状态</span>
                      <span class="toggle-row__hint">停用后不可被选择</span>
                    </div>
                    <el-switch v-model="form.status" active-value="0" inactive-value="1" />
                  </div>
                </div>

                <!-- 备注 -->
                <div class="aform__group">
                  <el-form-item label="备注">
                    <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="可选备注信息" />
                  </el-form-item>
                </div>
              </el-form>
            </div>

            <div class="sheet__footer">
              <button type="button" class="apple-btn apple-btn--ghost" @click="cancel">取消</button>
              <button type="button" class="apple-btn apple-btn--primary" @click="submitForm">保存</button>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>

    <!-- ==================== 导入面板 ==================== -->
    <Teleport to="body">
      <Transition name="sheet">
        <div v-if="importOpen" class="sheet-overlay" @click.self="cancelImport">
          <div class="sheet sheet--wide" role="dialog" aria-modal="true" aria-label="导入模型">
            <div class="sheet__header">
              <h2 class="sheet__title">导入模型</h2>
              <button type="button" class="sheet__close" aria-label="关闭" @click="cancelImport">✕</button>
            </div>

            <div class="sheet__body">
              <p class="import-hint">选择上游渠道读取已维护的模型清单；已建模型可以补充渠道供应，未建模型导入即创建。</p>

              <div class="import-source">
                <el-select v-model="importChannelId" placeholder="选择上游渠道" filterable clearable style="width: 320px" @change="handleChannelChange">
                  <el-option v-for="c in channelOptions" :key="c.channelId" :label="`${c.channelName} (${c.channelType})`" :value="c.channelId" />
                </el-select>
                <button type="button" class="apple-btn apple-btn--primary" :disabled="!importChannelId || upstreamLoading" @click="fetchUpstreamModels">
                  <svg width="13" height="13" viewBox="0 0 14 14" fill="none"><path d="M7 1v12M1 7h12" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/></svg>
                  读取清单
                </button>
              </div>

              <!-- 上游模型表 -->
              <div class="import-table-wrap" v-loading="upstreamLoading">
                <div v-if="upstreamList.length" class="import-table">
                  <div class="import-table__head">
                    <span class="import-cell import-cell--id">模型 ID</span>
                    <span class="import-cell import-cell--type">类型</span>
                    <span class="import-cell import-cell--caps">能力</span>
                    <span class="import-cell import-cell--owner">归属</span>
                    <span class="import-cell import-cell--status">状态</span>
                    <span class="import-cell import-cell--op">操作</span>
                  </div>
                  <template v-for="row in upstreamList" :key="row.upstreamModelId">
                    <!-- 行内导入表单（替换该行） -->
                    <div v-if="inlineImportId === row.upstreamModelId" class="import-form">
                      <div class="import-form__head">
                        <span class="import-form__title">
                          {{ row.importStatus === 'NOT_IMPORTED' ? '导入模型' : '新增渠道供应' }} · {{ row.upstreamModelId }}
                        </span>
                      </div>
                      <el-form ref="importRef" :model="importForm" :rules="importRules" label-position="top" class="aform" @submit.prevent="submitImport">
                        <el-form-item label="模型编码" prop="modelCode">
                          <el-input v-model="importForm.modelCode" disabled />
                        </el-form-item>

                        <template v-if="importTargetStatus === 'NOT_IMPORTED'">
                          <div class="aform__row aform__row--2">
                            <el-form-item label="展示名称" prop="displayName" class="aform__item">
                              <el-input v-model="importForm.displayName" placeholder="对外展示用，缺省同模型编码" />
                            </el-form-item>
                            <el-form-item label="模型类型" prop="modelType" class="aform__item">
                              <el-select v-model="importForm.modelType" placeholder="请选择" style="width: 100%">
                                <el-option v-for="t in MODEL_TYPES" :key="t.value" :label="t.label" :value="t.value" />
                              </el-select>
                            </el-form-item>
                          </div>
                          <div class="aform__row aform__row--2">
                            <el-form-item label="上下文 (tokens)" prop="contextWindow" class="aform__item">
                              <el-input-number v-model="importForm.contextWindow" :min="1" :step="1024" controls-position="right" style="width: 100%" />
                            </el-form-item>
                            <el-form-item label="最大输出 (tokens)" prop="maxOutputTokens" class="aform__item">
                              <el-input-number v-model="importForm.maxOutputTokens" :min="1" :step="1024" controls-position="right" style="width: 100%" />
                            </el-form-item>
                          </div>
                          <template v-if="importForm.modelType === 'CHAT'">
                            <el-form-item label="支持推理" class="aform__item">
                              <el-switch v-model="importForm.reasoningEnabled" active-value="1" inactive-value="0" style="margin-top: 4px" />
                            </el-form-item>
                            <el-form-item label="输入模态" class="aform__item">
                              <div class="modality-grid">
                                <button
                                  v-for="m in INPUT_MODALITIES"
                                  :key="m.value"
                                  type="button"
                                  class="modality-card"
                                  :class="{ 'is-on': importModalities.includes(m.value), 'is-warn': m.warn }"
                                  :aria-pressed="importModalities.includes(m.value)"
                                  @click="toggleModality('import', m.value)"
                                >
                                  <span class="modality-card__icon">{{ m.icon }}</span>
                                  <span class="modality-card__body">
                                    <b>{{ m.label }}</b>
                                    <em>{{ m.hint }}</em>
                                  </span>
                                  <span class="modality-card__tick">
                                    <svg viewBox="0 0 12 12" width="12" height="12" fill="none"><path d="M2.5 6.2l2.4 2.4L9.5 4" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/></svg>
                                  </span>
                                </button>
                              </div>
                              <div class="modality-tip">上游清单给出模态时自动选中；否则按模型名推测，<b>务必核对</b>——多模态已是默认能力，名字里通常看不出来。</div>
                            </el-form-item>
                          </template>
                        </template>

                        <div class="aform__group-label" style="margin-top: 6px">渠道供应配置</div>
                        <el-form-item label="调用标识" prop="modelName">
                          <el-input v-model="importForm.modelName" placeholder="渠道下实际 model 参数，缺省同编码" />
                        </el-form-item>
                        <div class="aform__row aform__row--3">
                          <el-form-item label="权重" prop="weight" class="aform__item">
                            <el-input-number v-model="importForm.weight" :min="1" :max="100" controls-position="right" style="width: 100%" />
                          </el-form-item>
                          <el-form-item label="重试" prop="retryCount" class="aform__item">
                            <el-input-number v-model="importForm.retryCount" :min="0" :max="10" controls-position="right" style="width: 100%" />
                          </el-form-item>
                          <el-form-item label="输入价 (元/千tok)" prop="inputPrice" class="aform__item">
                            <el-input-number v-model="importForm.inputPrice" :min="0" :precision="4" :step="0.001" controls-position="right" style="width: 100%" />
                          </el-form-item>
                        </div>
                        <div class="aform__row aform__row--2">
                          <el-form-item label="输出价 (元/千tok)" prop="outputPrice" class="aform__item">
                            <el-input-number v-model="importForm.outputPrice" :min="0" :precision="4" :step="0.001" controls-position="right" style="width: 100%" />
                          </el-form-item>
                        </div>
                        <div class="import-form__foot">
                          <button type="button" class="apple-btn apple-btn--ghost" @click="cancelInlineImport">取消</button>
                          <button type="button" class="apple-btn apple-btn--primary" :disabled="importSubmitting" @click="submitImport">确认导入</button>
                        </div>
                      </el-form>
                    </div>
                    <!-- 默认只读行 -->
                    <div
                      v-else
                      class="import-row"
                      :class="{ 'is-imported': row.importStatus === 'CHANNEL_BOUND' }"
                    >
                      <span class="import-cell import-cell--id" :title="row.upstreamModelId">{{ row.upstreamModelId }}</span>
                      <span class="import-cell import-cell--type">
                        <span class="import-type-chip" :style="{ background: softOf(row.recommendType || ''), color: colorOf(row.recommendType || '') }">
                          <i class="import-type-chip__dot" :style="{ background: colorOf(row.recommendType || '') }"></i>
                          {{ typeLabel(row.recommendType) }}
                        </span>
                      </span>
                      <span class="import-cell import-cell--caps">
                        <span v-for="cap in (row.capabilities || [])" :key="cap" class="cap-pill">{{ cap }}</span>
                      </span>
                      <span class="import-cell import-cell--owner" :title="row.ownedBy">{{ row.ownedBy || '-' }}</span>
                      <span class="import-cell import-cell--status">
                        <span v-if="row.importStatus === 'NOT_IMPORTED'" class="status-tag status-tag--info">未导入</span>
                        <span v-else-if="row.importStatus === 'MODEL_EXISTS'" class="status-tag status-tag--warn">已建模型</span>
                        <span v-else class="status-tag status-tag--ok">已接入</span>
                      </span>
                      <span class="import-cell import-cell--op">
                        <!-- 未导入:一键导入(用缺省值),旁挂「高级」入口展开表单自定义 -->
                        <template v-if="row.importStatus === 'NOT_IMPORTED'">
                          <button type="button" class="row-link" :disabled="importingId === row.upstreamModelId" @click.stop="quickImport(row)">
                            {{ importingId === row.upstreamModelId ? '导入中…' : '导入' }}
                          </button>
                          <button type="button" class="row-link row-link--muted" @click.stop="openInlineImport(row)">高级</button>
                        </template>
                        <!-- 已建模型:一键接入供应,旁挂「高级」 -->
                        <template v-else-if="row.importStatus === 'MODEL_EXISTS'">
                          <button type="button" class="row-link row-link--warn" :disabled="importingId === row.upstreamModelId" @click.stop="quickImport(row)">
                            {{ importingId === row.upstreamModelId ? '接入中…' : '供应' }}
                          </button>
                          <button type="button" class="row-link row-link--muted" @click.stop="openInlineImport(row)">高级</button>
                        </template>
                        <span v-else class="row-link row-link--muted">-</span>
                      </span>
                    </div>
                  </template>
                </div>
                <div v-else class="import-empty">
                  <span v-if="!importChannelId">请先选择上游渠道</span>
                  <template v-else-if="!upstreamLoading">
                    <span>该渠道尚未维护模型清单</span>
                    <button type="button" class="row-link" @click="goChannel">去渠道管理同步</button>
                  </template>
                </div>
              </div>
            </div>

            <div class="sheet__footer">
              <button type="button" class="apple-btn apple-btn--ghost" @click="cancelImport">关闭</button>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>

    <!-- 供应管理（独立 Sheet） -->
    <ModelSupply v-model="supplyOpen" :model="supplyModel" @changed="onSupplyChanged" />
  </div>
</template>

<script setup name="AiModel">
import { listModel, getModel, updateModel, delModel, listUpstreamModels, importModel } from '@/api/ai/model'
import { listChannel } from '@/api/ai/channel'
import { gradientOf, colorOf, softOf } from '@/utils/ai-palette'
import ModelSupply from './supply.vue'

const { proxy } = getCurrentInstance()
const { sys_normal_disable } = proxy.useDict('sys_normal_disable')

const offGradient = 'linear-gradient(135deg, #A1A1A6, #C7C7CC)'

// 输入模态选项。四种互相独立 —— 不是等级,也不能互相推导:
// 实测 OpenRouter 417 个模型跑出 12 种组合(gpt-audio 有音频没图片,o3-mini 有文档没图片)。
// hint 里的格式限制来自 Spring AI 的序列化能力,不是模型侧的限制。
const INPUT_MODALITIES = [
  { value: 'image', label: '图片', hint: 'jpg/png/gif/webp/bmp', icon: '🖼️' },
  { value: 'file',  label: '文档', hint: '仅 PDF', icon: '📄' },
  { value: 'audio', label: '音频', hint: '仅 mp3/wav', icon: '🎵' },
  { value: 'video', label: '视频', hint: '暂无法送入模型', icon: '🎬', warn: true }
]
const MODALITY_LABEL = INPUT_MODALITIES.reduce((m, x) => { m[x.value] = x.label; return m }, {})

/** 逗号分隔字符串 → 数组,给 el-checkbox-group 用 */
function toModalityArray(raw) {
  return String(raw || '').split(',').map(x => x.trim()).filter(Boolean)
}

/** 逗号分隔字符串 → 中文标签数组,未知词元忽略 */
function modalityLabels(raw) {
  return toModalityArray(raw).map(x => MODALITY_LABEL[x]).filter(Boolean)
}

/**
 * 导入时的模态预填。优先用上游清单给的真实值,拿不到才回退按模型名推测 ——
 * 后者漏报很多(实测约三分之二支持图片的模型名字里看不出来),界面上已提示需人工核对。
 */
function modalitiesFromRow(row) {
  if (row && row.inputModalities) return row.inputModalities
  const caps = (row && row.capabilities) || []
  return caps.includes('vision') ? 'image' : ''
}

// 模型类型选项（与后端字典对齐）
const MODEL_TYPES = [
  { value: 'CHAT', label: '对话' },
  { value: 'EMBEDDING', label: '向量' },
  { value: 'RERANK', label: '重排序' },
  { value: 'IMAGE', label: '图像' },
  { value: 'VIDEO', label: '视频' },
  { value: 'TTS', label: '语音合成' },
  { value: 'STT', label: '语音识别' },
  { value: 'MODERATION', label: '内容审核' }
]

const TYPE_EMOJI = {
  CHAT: '💬', EMBEDDING: '🧬', RERANK: '🔀', IMAGE: '🖼️', VIDEO: '🎬',
  TTS: '🔊', STT: '🎙️', MODERATION: '🛡️'
}

function typeLabel(t) {
  const m = MODEL_TYPES.find(x => x.value === t)
  return m ? m.label : (t || '—')
}
function typeEmoji(t) {
  return TYPE_EMOJI[t] || '🤖'
}
function formatTokens(n) {
  if (n == null || n === '') return '—'
  if (n >= 1000000) return (n / 1000000).toFixed(1) + 'M'
  if (n >= 1000) return (n / 1000).toFixed(0) + 'K'
  return String(n)
}
function formatTime(s) {
  if (!s) return '—'
  return String(s).replace('T', ' ').slice(0, 16)
}

/* ==================== 列表 ==================== */

const modelList = ref([])
const loading = ref(true)
const total = ref(0)

const data = reactive({
  queryParams: {
    pageNum: 1, pageSize: 12,
    modelCode: undefined, displayName: undefined,
    modelType: undefined, status: undefined
  },
  form: {},
  rules: {
    modelCode: [{ required: true, message: '模型编码不能为空', trigger: 'blur' }],
    displayName: [{ required: true, message: '展示名称不能为空', trigger: 'blur' }],
    modelType: [{ required: true, message: '模型类型不能为空', trigger: 'change' }],
    contextWindow: [{ required: true, message: '上下文长度不能为空', trigger: 'blur' }],
    maxOutputTokens: [{ required: true, message: '最大输出不能为空', trigger: 'blur' }],
    reasoningEnabled: [{ required: true, message: '请选择是否支持推理', trigger: 'change' }]
  }
})
const { queryParams, form, rules } = toRefs(data)

// checkbox-group 要数组,库里存逗号分隔字符串;用可写 computed 双向转换,
// form.inputModalities 始终保持字符串形态,提交时无需再加工。
const formModalities = computed({
  get: () => toModalityArray(form.value.inputModalities),
  set: (v) => { form.value.inputModalities = (v || []).join(',') }
})

/**
 * 切换一个模态。整卡可点,所以自己维护数组增删,不走 checkbox-group。
 * target 只有 'form' / 'import' 两个来源,写死比传 ref 进来可读。
 */
function toggleModality(target, value) {
  const box = target === 'import' ? importModalities : formModalities
  const cur = box.value
  box.value = cur.includes(value) ? cur.filter((x) => x !== value) : [...cur, value]
}

/**
 * 推理与输入模态只对 CHAT 有意义 —— 向量/重排序/图像/语音模型不走对话链路,
 * 后端也只在装配 chat 时读这两个字段。改成非对话类型时必须清空:
 * 界面藏起来但值还留着,存进去就是一条自相矛盾的记录(向量模型声称能读图片)。
 */
watch(() => form.value.modelType, (type, old) => {
  if (old === undefined || type === old || type === 'CHAT') return
  form.value.reasoningEnabled = '0'
  form.value.inputModalities = ''
})

function getList() {
  loading.value = true
  listModel(queryParams.value).then((response) => {
    modelList.value = response.rows
    total.value = response.total
    loading.value = false
  }).catch(() => { loading.value = false })
}

function handleQuery() {
  queryParams.value.pageNum = 1
  getList()
}

function resetQuery() {
  queryParams.value = { pageNum: 1, pageSize: queryParams.value.pageSize, modelCode: undefined, displayName: undefined, modelType: undefined, status: undefined }
  getList()
}

/* ==================== 详情（只读） ==================== */

const detailOpen = ref(false)
const detail = ref({})

function handleDetail(row) {
  detail.value = { ...row }
  detailOpen.value = true
}

function closeDetail() {
  detailOpen.value = false
}

function editFromDetail() {
  // 复制详情为编辑草稿，避免双向影响
  const copy = JSON.parse(JSON.stringify(detail.value))
  detailOpen.value = false
  reset()
  openEditor(copy, '编辑模型')
}

/* ==================== 编辑 ==================== */

const open = ref(false)
const title = ref('')
let formSnapshot = ''

function takeSnapshot() { formSnapshot = JSON.stringify(form.value) }
function isDirty() { return JSON.stringify(form.value) !== formSnapshot }

function reset() {
  form.value = {
    modelId: undefined,
    modelCode: undefined,
    displayName: undefined,
    modelType: 'CHAT',
    contextWindow: 128000,
    maxOutputTokens: 8192,
    reasoningEnabled: '0',
    inputModalities: '',
    sort: 0,
    status: '0',
    visibility: 'PUBLIC',
    remark: undefined
  }
  proxy.resetForm('modelRef')
}

function openEditor(payload, sheetTitle) {
  form.value = payload
  title.value = sheetTitle
  open.value = true
  nextTick(() => takeSnapshot())
}

function handleUpdate(row) {
  reset()
  getModel(row.modelId).then((res) => {
    openEditor(res.data, '编辑模型')
  })
}

function cancel() {
  if (!isDirty()) {
    closeEditor()
    return
  }
  proxy.$modal.confirm('有未保存的修改，关闭后将丢失。确定关闭吗？')
    .then(closeEditor)
    .catch(() => {})
}

function closeEditor() {
  open.value = false
  formSnapshot = ''
}

function submitForm() {
  proxy.$refs['modelRef'].validate((valid) => {
    if (!valid) return
    if (form.value.modelId == null) return
    updateModel(form.value).then(() => {
      proxy.$modal.msgSuccess('修改成功')
      closeEditor()
      getList()
    })
  })
}

/* ==================== 删除 ==================== */

function handleDelete(row) {
  proxy.$modal.confirm(`确认删除模型「${row.displayName}」？删除后相关供应绑定也将移除。`).then(() => {
    return delModel(row.modelId)
  }).then(() => {
    proxy.$modal.msgSuccess('删除成功')
    if (detail.value.modelId === row.modelId) detailOpen.value = false
    getList()
  }).catch(() => {})
}

/* ==================== 供应管理（从卡片按钮直接打开，双层） ==================== */

const supplyOpen = ref(false)
const supplyModel = ref({})

function openSupplyFromCard(row) {
  supplyModel.value = row
  supplyOpen.value = true
}

function onSupplyChanged() {
  // 供应变动后刷新模型列表（卡片上的状态/计数可能变化）
  getList()
}

/* ==================== 导入 ==================== */

const importOpen = ref(false)
const importChannelId = ref(undefined)
const channelOptions = ref([])
const upstreamList = ref([])
const upstreamLoading = ref(false)
const inlineImportId = ref(null) // 当前展开行内表单的上游模型 id
const importTargetStatus = ref('NOT_IMPORTED')
const importSubmitting = ref(false)
const importingId = ref(null) // 一键导入进行中的模型 id(防止重复点击)
let importFormSnapshot = ''

const importData = reactive({
  importForm: {},
  importRules: {
    modelType: [{ required: true, message: '模型类型不能为空', trigger: 'change' }],
    contextWindow: [{ required: true, message: '上下文长度不能为空', trigger: 'blur' }],
    maxOutputTokens: [{ required: true, message: '最大输出不能为空', trigger: 'blur' }],
    modelName: [{ required: true, message: '调用标识不能为空', trigger: 'blur' }],
    weight: [{ required: true, message: '权重不能为空', trigger: 'blur' }],
    retryCount: [{ required: true, message: '重试次数不能为空', trigger: 'blur' }]
  }
})
const { importForm, importRules } = toRefs(importData)

const importModalities = computed({
  get: () => toModalityArray(importForm.value.inputModalities),
  set: (v) => { importForm.value.inputModalities = (v || []).join(',') }
})

watch(() => importForm.value.modelType, (type, old) => {
  if (old === undefined || type === old || type === 'CHAT') return
  importForm.value.reasoningEnabled = '0'
  importForm.value.inputModalities = ''
})

function handleImportOpen() {
  importOpen.value = true
  upstreamList.value = []
  importChannelId.value = undefined
  inlineImportId.value = null
  listChannel({ pageNum: 1, pageSize: 100, status: '0' }).then((res) => {
    channelOptions.value = res.rows || []
  })
}

function cancelImport() {
  // dirty check：行内表单有改动，先确认
  if (inlineImportId.value && isImportDirty()) {
    proxy.$modal.confirm('有未保存的修改，关闭将丢失。确定吗？')
      .then(() => { importOpen.value = false; inlineImportId.value = null })
      .catch(() => {})
    return
  }
  importOpen.value = false
  inlineImportId.value = null
}

function handleChannelChange() {
  upstreamList.value = []
  inlineImportId.value = null
  if (importChannelId.value) fetchUpstreamModels()
}

function fetchUpstreamModels() {
  upstreamLoading.value = true
  listUpstreamModels(importChannelId.value).then((res) => {
    upstreamList.value = res.data || []
  }).finally(() => { upstreamLoading.value = false })
}

function goChannel() {
  importOpen.value = false
  proxy.$router.push('/ai/model/channel')
}

/**
 * 一键导入:用缺省值直接调后端,不展开行内表单。
 * NOT_IMPORTED -> 创建模型 + 接入渠道;MODEL_EXISTS -> 仅接入渠道(供应)。
 * 想自定义上下文窗口/价格等点「高级」走行内表单。
 */
function quickImport(row) {
  if (importingId.value === row.upstreamModelId) return
  resetImportForm(row)
  importingId.value = row.upstreamModelId
  importModel(importForm.value).then((response) => {
    proxy.$modal.msgSuccess(response.msg || '导入成功')
    fetchUpstreamModels()
    getList()
  }).catch(() => {}).finally(() => { importingId.value = null })
}

function resetImportForm(row) {
  importForm.value = {
    channelId: importChannelId.value,
    modelCode: row.upstreamModelId,
    displayName: row.displayName || row.upstreamModelId,
    modelType: row.recommendType || 'CHAT',
    contextWindow: 128000,
    maxOutputTokens: 8192,
    reasoningEnabled: (row.capabilities || []).includes('reasoning') ? '1' : '0',
    inputModalities: modalitiesFromRow(row),
    modelName: row.upstreamModelId,
    weight: 1,
    retryCount: 0,
    inputPrice: undefined,
    outputPrice: undefined
  }
  proxy.resetForm('importRef')
}

function takeImportSnapshot() { importFormSnapshot = JSON.stringify(importForm.value) }
function isImportDirty() { return JSON.stringify(importForm.value) !== importFormSnapshot }

async function openInlineImport(row) {
  if (row.importStatus === 'CHANNEL_BOUND') return
  // 点击同一行 → 收起
  if (inlineImportId.value === row.upstreamModelId) {
    cancelInlineImport(true)
    return
  }
  // 切到另一行前，dirty check
  if (inlineImportId.value && isImportDirty()) {
    try {
      await proxy.$modal.confirm('有未保存的修改，切换将丢失。确定吗？')
    } catch { return }
  }
  resetImportForm(row)
  importTargetStatus.value = row.importStatus
  inlineImportId.value = row.upstreamModelId
  nextTick(() => takeImportSnapshot())
}

function cancelInlineImport(force) {
  if (!force && isImportDirty()) {
    proxy.$modal.confirm('有未保存的修改，取消将丢失。确定吗？')
      .then(() => { inlineImportId.value = null; importFormSnapshot = '' })
      .catch(() => {})
    return
  }
  inlineImportId.value = null
  importFormSnapshot = ''
}

function submitImport() {
  proxy.$refs['importRef'].validate((valid) => {
    if (!valid) return
    importSubmitting.value = true
    importModel(importForm.value).then((response) => {
      proxy.$modal.msgSuccess(response.msg || '导入成功')
      inlineImportId.value = null
      importFormSnapshot = ''
      fetchUpstreamModels()
      getList()
    }).finally(() => { importSubmitting.value = false })
  })
}

/* ==================== 键盘交互 ==================== */

function onKeydown(e) {
  if (e.key === 'Escape') {
    if (detailOpen.value) closeDetail()
    else if (open.value) cancel()
    else if (inlineImportId.value) cancelInlineImport()
    else if (importOpen.value) cancelImport()
  }
}

onMounted(() => document.addEventListener('keydown', onKeydown))
onUnmounted(() => document.removeEventListener('keydown', onKeydown))

getList()
</script>

<style scoped lang="scss">
@use '../../../assets/styles/ai-tokens.scss' as *;

// 设计令牌见 @/assets/styles/ai-tokens.scss + ai-theme.scss（支持暗色）
$spring: cubic-bezier(0.34, 1.56, 0.64, 1);

.model-page {
  font-family: $font; padding: 40px 48px; min-height: calc(100vh - 84px); -webkit-font-smoothing: antialiased;
  background:
    var(--ai-page-bg);
  @media (max-width: 768px) { padding: 24px 16px; }
}

/* Header */
.model-header {
  display: flex; align-items: baseline; justify-content: space-between; margin-bottom: 28px;
  &__left { display: flex; align-items: baseline; gap: 12px; }
  &__title { font-size: 34px; font-weight: 700; color: $text; letter-spacing: -0.4px; margin: 0; }
  &__count { font-size: 15px; color: $gray; }
}

/* Buttons */
.apple-btn {
  display: inline-flex; align-items: center; gap: 6px; font-family: $font; font-size: 14px; font-weight: 500;
  border: none; border-radius: 980px; padding: 8px 18px; cursor: pointer; transition: all 0.2s $ease; outline: none;
  &:active { transform: scale(0.96); }
  &--add, &--primary { background: $blue; color: #fff; padding: 10px 24px; box-shadow: 0 2px 10px rgba(10,132,255,0.32); &:hover { background: #0071e3; } &:disabled { opacity: 0.5; cursor: not-allowed; } }
  &--ghost { background: transparent; color: $blue; padding: 10px 16px; &:hover { background: rgba(10,132,255,0.08); } }
  &--outline { background: transparent; color: $blue; border: 1.5px solid rgba(10,132,255,0.35); padding: 7px 16px; &:hover { background: rgba(10,132,255,0.06); border-color: $blue; } }
}

/* Search */
.model-search { display: flex; align-items: center; gap: 10px; margin-bottom: 24px; flex-wrap: wrap;
  &__field { position: relative; flex: 1; min-width: 220px; max-width: 320px; }
  &__icon { position: absolute; left: 13px; top: 50%; transform: translateY(-50%); color: $gray2; pointer-events: none; }
  &__input {
    width: 100%; height: 38px; padding: 0 32px 0 36px; border: none; border-radius: 980px;
    background: var(--ai-search-bg); font-size: 14px; font-family: $font; color: $text; outline: none;
    transition: all 0.25s $ease; box-shadow: 0 1px 3px var(--ai-border);
    &::placeholder { color: $gray2; }
    &:focus { background: var(--ai-card-bg); box-shadow: 0 0 0 4px rgba(10,132,255,0.12), 0 2px 12px var(--ai-border-2); }
    &--mid { padding-left: 14px; max-width: 220px; }
  }
  &__clear {
    position: absolute; right: 10px; top: 50%; transform: translateY(-50%); width: 18px; height: 18px;
    border: none; border-radius: 50%; background: $gray3; color: #fff; font-size: 9px; cursor: pointer;
    display: flex; align-items: center; justify-content: center; &:hover { background: $gray; }
  }
}
.model-select {
  height: 36px; padding: 0 28px 0 12px; border: none; border-radius: 8px; background: var(--ai-search-bg);
  box-shadow: 0 1px 3px var(--ai-border);
  font-size: 13px; font-family: $font; color: $text; appearance: none; cursor: pointer; outline: none;
  background-image: url("data:image/svg+xml,%3Csvg width='10' height='6' viewBox='0 0 10 6' fill='none' xmlns='http://www.w3.org/2000/svg'%3E%3Cpath d='M1 1l4 4 4-4' stroke='%238E8E93' stroke-width='1.5' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E");
  background-repeat: no-repeat; background-position: right 10px center;
}

/* 卡片网格 */
.model-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(330px, 1fr)); gap: 16px; min-height: 180px; @media (max-width: 768px) { grid-template-columns: 1fr; } }
.model-card {
  position: relative; display: flex; flex-direction: column; gap: 10px; padding: 16px 18px 14px;
  background: var(--ai-card-bg); border: 1px solid var(--ai-border); border-radius: 16px; cursor: pointer;
  box-shadow: 0 1px 2px var(--ai-fill-2); transition: all 0.28s $ease; overflow: hidden;
  &:hover { box-shadow: var(--ai-shadow-card); transform: translateY(-3px); border-color: var(--ai-input-bg);
    .model-card__actions { opacity: 1; transform: translateY(0); } .model-card__rail { opacity: 1; } }
  &:active { transform: translateY(-1px) scale(0.995); }
  &.is-off { background: var(--ai-card-off); .model-card__name { color: $text2; } }
  &__rail { position: absolute; left: 0; top: 0; bottom: 0; width: 3px; background: var(--accent); opacity: 0; transition: opacity 0.28s $ease; }
  &__head { display: flex; align-items: center; gap: 8px; }
  &__ident { flex: 1; min-width: 0; }
  &__name { font-size: 16px; font-weight: 600; color: $text; margin: 0 0 4px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; letter-spacing: -0.2px; }
  &__sub { display: flex; align-items: center; gap: 7px; min-width: 0; }
  &__code { font-family: $mono; font-size: 10.5px; color: $gray; background: var(--ai-fill-2); padding: 1.5px 6px; border-radius: 4px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  &__status { display: inline-flex; align-items: center; gap: 4px; font-size: 11px; flex-shrink: 0;
    i { width: 6px; height: 6px; border-radius: 50%; display: inline-block; }
    &.is-on { color: #248A3D; i { background: $green; box-shadow: 0 0 0 2.5px rgba(52,199,89,0.18); } }
    &.is-off { color: $gray; i { background: $gray2; } }
  }
  &__actions { display: flex; gap: 4px; opacity: 0; transform: translateY(-3px); transition: all 0.22s $ease; flex-shrink: 0; }
  &__action { width: 27px; height: 27px; border: none; border-radius: 8px; background: var(--ai-border); color: $text2; cursor: pointer; display: flex; align-items: center; justify-content: center; transition: all 0.18s;
    &:hover { background: rgba(10,132,255,0.12); color: $blue; }
    &--danger:hover { background: rgba(255,59,48,0.12); color: $red; }
  }
  &__supply-btn {
    display: flex; align-items: center; justify-content: center; gap: 6px; width: 100%;
    margin-top: 4px; padding: 9px 12px; border: none; border-radius: 10px;
    background: rgba(52,199,89,0.12); color: #1E7A3C; font-family: $font; font-size: 13px; font-weight: 600;
    cursor: pointer; transition: all 0.2s $ease;
    &:hover { background: $green; color: #fff; box-shadow: 0 4px 12px rgba(52,199,89,0.3); }
    &:active { transform: scale(0.98); }
  }
  &__props { display: flex; flex-wrap: wrap; gap: 5px 12px; align-items: center; padding: 2px 0; }
  &__chip { display: inline-flex; align-items: center; gap: 5px; font-size: 11.5px; font-weight: 600; padding: 2.5px 8px; border-radius: 980px;
    &-dot { width: 6px; height: 6px; border-radius: 50%; }
    &--scope { background: var(--ai-fill-2, #f3f4f6); color: var(--ai-text-2, #4b5563); border: 1px solid var(--ai-border-2, #e5e7eb); }
    &--scope.is-public { background: rgba(16, 185, 129, 0.1); color: #059669; border-color: rgba(16, 185, 129, 0.25); }
    &--scope.is-private { background: rgba(245, 158, 11, 0.1); color: #d97706; border-color: rgba(245, 158, 11, 0.25); }
  }
  &__prop { display: inline-flex; align-items: baseline; gap: 4px; font-size: 12px;
    &-k { color: $gray; } b { font-weight: 600; color: $text; font-variant-numeric: tabular-nums; }
  }
  &__reason { color: $orange !important; }
  &__vision { color: $blue !important; }
  &__remark { font-size: 12px; color: $text2; margin: 0; padding-top: 8px; border-top: 1px dashed var(--ai-border-2); display: -webkit-box; -webkit-line-clamp: 1; -webkit-box-orient: vertical; overflow: hidden; }
}
.model-empty { grid-column: 1 / -1; text-align: center; padding: 72px 0; &__icon { font-size: 44px; margin-bottom: 14px; } &__text { font-size: 16px; color: $gray; margin: 0 0 18px; } }
.model-pagination { margin-top: 28px; display: flex; justify-content: center; }

/* ==================== Sheet 基础 ==================== */
.sheet-overlay { position: fixed; inset: 0; z-index: 2000; background: var(--ai-overlay); backdrop-filter: blur(5px); display: flex; align-items: center; justify-content: center; padding: 32px; }
.sheet {
  width: 100%; max-width: 820px; height: min(720px, 88vh); background: var(--ai-card-bg);
  border-radius: 20px; box-shadow: var(--ai-shadow-sheet);
  display: flex; flex-direction: column; overflow: hidden;
  transition: max-width 0.3s $ease, height 0.3s $ease;
  &--wide { max-width: 1080px; height: min(820px, 92vh); }
  &--detail { max-width: 940px; height: auto; max-height: 88vh; }
  &__header { display: flex; align-items: center; justify-content: space-between; padding: 22px 28px 0; flex-shrink: 0; }
  &__title { font-size: 21px; font-weight: 700; color: $text; margin: 0; }
  &__close { width: 28px; height: 28px; border: none; border-radius: 50%; background: var(--ai-fill-3); color: $gray; font-size: 12px; cursor: pointer; display: flex; align-items: center; justify-content: center; flex-shrink: 0; &:hover { background: var(--ai-hover-strong); color: $text; } }
  &__body { flex: 1; min-height: 0; overflow-y: auto; padding: 20px 28px 24px;
    &::-webkit-scrollbar { width: 5px; } &::-webkit-scrollbar-thumb { background: var(--ai-border-4); border-radius: 3px; } }
  &__footer { display: flex; justify-content: flex-end; gap: 10px; padding: 14px 28px 22px; border-top: 1px solid var(--ai-fill-3); flex-shrink: 0; }
}
.sheet-enter-active { transition: all 0.35s $spring; }
.sheet-leave-active { transition: all 0.2s ease-in; }
.sheet-enter-from { opacity: 0; .sheet { transform: scale(0.92) translateY(20px); opacity: 0; } }
.sheet-leave-to { opacity: 0; .sheet { transform: scale(0.96); opacity: 0; } }

/* ==================== Hero ==================== */
.hero {
  position: relative; flex-shrink: 0; padding: 24px 28px 0; color: #fff;
  &::after { content: ''; position: absolute; inset: 0; background: linear-gradient(180deg, var(--ai-fill-3), var(--ai-border-4)); pointer-events: none; }
  &__close {
    position: absolute; top: 16px; right: 16px; z-index: 2; width: 28px; height: 28px; border: none; border-radius: 50%;
    background: rgba(255,255,255,0.22); color: #fff; font-size: 12px; cursor: pointer;
    display: flex; align-items: center; justify-content: center; backdrop-filter: blur(6px);
    &:hover { background: rgba(255,255,255,0.34); }
  }
  &__body { position: relative; z-index: 1; display: flex; align-items: center; gap: 14px; }
  &__avatar {
    width: 54px; height: 54px; border-radius: 15px; flex-shrink: 0; display: flex; align-items: center; justify-content: center;
    font-size: 26px; line-height: 1; background: rgba(255,255,255,0.24); backdrop-filter: blur(10px);
    border: 1px solid rgba(255,255,255,0.3);
  }
  &__text { min-width: 0; }
  &__name { font-size: 22px; font-weight: 700; margin: 0 0 6px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; text-shadow: 0 1px 3px var(--ai-border-4); }
  &__sub { display: flex; align-items: center; gap: 9px; flex-wrap: wrap; }
  &__code { font-family: $mono; font-size: 11px; background: rgba(255,255,255,0.2); padding: 2px 7px; border-radius: 5px; }
  &__status { display: inline-flex; align-items: center; gap: 5px; font-size: 12px;
    i { width: 6px; height: 6px; border-radius: 50%; background: var(--ai-card-bg); display: inline-block; &.is-off { opacity: 0.55; } }
  }
  &__stats {
    position: relative; z-index: 1; display: flex; gap: 26px; margin-top: 18px;
    padding: 12px 2px; border-top: 1px solid rgba(255,255,255,0.22);
  }
  &__stat { display: flex; align-items: baseline; gap: 5px; b { font-size: 18px; font-weight: 700; font-variant-numeric: tabular-nums; } span { font-size: 12px; opacity: 0.82; } }
}

/* ==================== 详情内容 ==================== */
.detail-cols {
  display: grid; grid-template-columns: minmax(0, 1fr) 280px; gap: 24px; align-items: start;
  @media (max-width: 760px) { grid-template-columns: 1fr; gap: 20px; }
}
.detail-main { min-width: 0; display: flex; flex-direction: column; gap: 18px; }
.detail-side { min-width: 0; display: flex; flex-direction: column; gap: 14px; background: var(--ai-fill-1); border-radius: $radius; padding: 16px; }
.detail-block {
  min-width: 0;
  & + & { padding-top: 14px; border-top: 1px solid var(--ai-fill-3); }
  &__title { display: flex; align-items: center; gap: 6px; font-size: 12px; font-weight: 600; color: $text; margin-bottom: 9px; }
  &__count { font-size: 10.5px; font-weight: 700; color: $gray; background: var(--ai-fill-3); padding: 1px 6px; border-radius: 980px; }
}
.detail-kv {
  margin: 0; display: flex; flex-direction: column; gap: 8px;
  &--two { display: grid; grid-template-columns: 1fr 1fr; gap: 8px 24px; @media (max-width: 600px) { grid-template-columns: 1fr; } }
  &__row { display: flex; align-items: baseline; gap: 10px; min-width: 0; }
  dt { font-size: 12px; color: $gray; flex-shrink: 0; width: 56px; }
  dd { margin: 0; font-size: 12.5px; font-weight: 500; color: $text; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
    &.is-on { color: $orange; } }
}
.detail-prompt { padding: 14px 16px; background: var(--ai-block-bg); border: 1px solid var(--ai-border-2); border-radius: $radius-sm; font-size: 13.5px; line-height: 1.7; color: $text;
  &--static { white-space: pre-wrap; }
}
.chip { display: inline-flex; align-items: center; gap: 5px; font-size: 11.5px; font-weight: 600; padding: 2.5px 8px; border-radius: 980px; max-width: 100%;
  &__dot { width: 6px; height: 6px; border-radius: 50%; }
}

/* ==================== 表单 ==================== */
.aform {
  :deep(.el-form-item) { margin-bottom: 14px; }
  :deep(.el-form-item__label) { font-size: 13px; font-weight: 500; color: $text2; padding-bottom: 4px; }
  :deep(.el-input__wrapper), :deep(.el-textarea__inner) { border-radius: $radius-sm; background: var(--ai-input-bg); box-shadow: 0 0 0 1px var(--ai-border-3) inset; transition: all 0.2s $ease;
    &:hover { box-shadow: 0 0 0 1px var(--ai-border-4) inset; }
    &.is-focus, &:focus { background: var(--ai-card-bg); box-shadow: 0 0 0 4px rgba(10,132,255,0.12), 0 0 0 1px $blue inset; } }
  :deep(.el-switch.is-checked .el-switch__core) { background-color: $green; border-color: $green; }
  &__group { background: var(--ai-fill-1); border-radius: $radius; padding: 16px 20px; margin-bottom: 12px; &--toggles { padding: 6px 20px; } }
  &__row { display: flex; gap: 14px; @media (max-width: 600px) { flex-direction: column; gap: 0; } }
  &__item { flex: 1; }
  &__group-label { font-size: 13px; font-weight: 600; color: $text; margin-bottom: 10px; }
}
.toggle-row { display: flex; align-items: center; justify-content: space-between; padding: 12px 0; gap: 16px;
  & + & { border-top: 1px solid var(--ai-border); }
  &__info { display: flex; flex-direction: column; gap: 2px; } &__label { font-size: 14px; font-weight: 500; color: $text; } &__hint { font-size: 12px; color: $gray; }
  /* 四个模态横排放不下开关那种左右布局,改成上下堆叠 */
  &--stack { flex-direction: column; align-items: stretch; gap: 10px; }
}

/* 输入模态多选:整卡可点,点击区域比 checkbox 大得多,也放得下格式说明 */
.modality-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px;
  @media (max-width: 600px) { grid-template-columns: 1fr; }
}
.modality-card { position: relative; display: flex; align-items: center; gap: 10px; width: 100%;
  padding: 10px 12px; border-radius: 10px; cursor: pointer; text-align: left; font-family: inherit;
  background: var(--ai-card-bg); border: 1.5px solid var(--ai-border);
  transition: background 0.18s, border-color 0.18s, transform 0.18s;
  &:hover { border-color: rgba(10,132,255,0.38); background: rgba(10,132,255,0.05); }
  &:active { transform: scale(0.985); }
  &:focus-visible { outline: none; box-shadow: 0 0 0 3.5px rgba(10,132,255,0.16); }

  &__icon { font-size: 17px; line-height: 1; flex: none; }
  &__body { display: flex; flex-direction: column; gap: 1px; min-width: 0;
    b { font-size: 13px; font-weight: 500; color: $text; }
    em { font-style: normal; font-size: 11px; color: $gray; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  }
  &__tick { position: absolute; top: 7px; right: 8px; width: 15px; height: 15px; border-radius: 50%;
    display: inline-flex; align-items: center; justify-content: center; color: #fff; background: $blue;
    opacity: 0; transform: scale(0.4); transition: opacity 0.18s, transform 0.22s $spring; }

  &.is-on { border-color: $blue; background: rgba(10,132,255,0.08);
    .modality-card__tick { opacity: 1; transform: scale(1); }
  }

  /* 视频:格式说明恒为橙色,选中后整卡转橙 —— 选了也送不出去,不能长得跟能用的一样 */
  &.is-warn {
    .modality-card__body em { color: $orange; }
    &:hover { border-color: rgba(255,149,0,0.45); background: rgba(255,149,0,0.05); }
    &.is-on { border-color: $orange; background: rgba(255,149,0,0.09);
      .modality-card__tick { background: $orange; }
    }
  }
}
.modality-tip { font-size: 11.5px; color: $gray; line-height: 1.5; margin-top: 6px;
  b { color: $orange; font-weight: 600; }
}

/* ==================== 导入 ==================== */
.import-hint { font-size: 13px; color: $text2; margin: 0 0 14px; line-height: 1.6; }
.import-source { display: flex; align-items: center; gap: 10px; margin-bottom: 16px; }
.import-table-wrap { background: var(--ai-input-bg); border-radius: $radius; padding: 6px; min-height: 220px; }
.import-table { display: flex; flex-direction: column; }
.import-table__head, .import-row {
  display: grid; grid-template-columns: 2fr 0.9fr 1.4fr 0.9fr 0.9fr 0.7fr; gap: 8px; padding: 9px 12px; align-items: center;
  font-size: 12.5px;
}
.import-table__head { color: $gray; font-weight: 600; font-size: 11.5px; text-transform: uppercase; letter-spacing: 0.4px; border-bottom: 1px solid var(--ai-fill-3); }
.import-row { background: var(--ai-card-bg); border-radius: 8px; margin-top: 4px; transition: background 0.18s $ease;
  &:hover { background: rgba(10,132,255,0.04); }
  &.is-imported { opacity: 0.7; cursor: default; }
}
/* 行内导入表单（替换上游行） */
.import-form {
  background: var(--ai-block-bg); border: 1px solid rgba(10,132,255,0.18);
  border-radius: 10px; padding: 14px 16px 12px; margin-top: 6px;
  box-shadow: 0 2px 12px rgba(10,132,255,0.06);
  animation: fadeUp 0.22s $ease;
  &__head { margin-bottom: 10px; }
  &__title { font-size: 13px; font-weight: 600; color: $text; }
  &__foot { display: flex; justify-content: flex-end; gap: 8px; padding-top: 8px; border-top: 1px solid var(--ai-border); margin-top: 6px; }
  // 行内布局：3-4 列网格，断点回退
  .aform__row { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px 14px;
    @media (max-width: 760px) { grid-template-columns: repeat(2, 1fr); }
    @media (max-width: 460px) { grid-template-columns: 1fr; } }
  .aform__item { min-width: 0; }
  .aform__row--2 { grid-template-columns: repeat(2, 1fr); @media (max-width: 600px) { grid-template-columns: 1fr; } }
  .aform__row--3 { grid-template-columns: repeat(3, 1fr); @media (max-width: 760px) { grid-template-columns: 1fr; } }
  .aform__row--full { grid-template-columns: 1fr; }
}
@keyframes fadeUp { from { opacity: 0; transform: translateY(6px); } to { opacity: 1; transform: translateY(0); } }
.import-cell { overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  &--id { font-family: $mono; font-size: 12px; }
  &--type, &--status, &--op { text-align: center; }
  &--op { text-align: right; }
}
.import-type-chip { display: inline-flex; align-items: center; gap: 5px; font-size: 11px; font-weight: 600; padding: 2px 8px; border-radius: 980px;
  &__dot { width: 5px; height: 5px; border-radius: 50%; }
}
.cap-pill { display: inline-block; font-size: 10.5px; color: $text2; background: var(--ai-fill-2); padding: 1px 7px; border-radius: 4px; margin-right: 4px;
  &:last-child { margin-right: 0; }
}
.status-tag { display: inline-block; font-size: 11px; font-weight: 600; padding: 2px 8px; border-radius: 980px;
  &--info { background: var(--ai-border); color: $gray; }
  &--warn { background: rgba(255,159,10,0.14); color: #B35C00; }
  &--ok { background: rgba(48,209,88,0.16); color: #1E7A3C; }
}
.row-link { background: transparent; border: none; padding: 4px 10px; border-radius: 7px; font-size: 12px; font-weight: 500; color: $blue; cursor: pointer; transition: background 0.18s;
  &:hover { background: rgba(10,132,255,0.1); }
  &--warn { color: #B35C00; &:hover { background: rgba(255,159,10,0.12); } }
  &--muted { color: $gray3; cursor: default; &:hover { background: transparent; } }
}
.import-empty { padding: 48px 16px; text-align: center; color: $gray; font-size: 13px; }
</style>
