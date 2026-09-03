<template>
  <div class="agent-page">
    <!-- 页面标题 -->
    <header class="agent-header">
      <div class="agent-header__left">
        <h1 class="agent-header__title">智能体</h1>
        <span class="agent-header__count">{{ total }} 个</span>
      </div>
      <div class="agent-header__actions">
        <button type="button" class="apple-btn apple-btn--add" @click="handleAdd" v-hasPermi="['ai:agent:add']">
          <svg width="14" height="14" viewBox="0 0 14 14" fill="none"><path d="M7 1v12M1 7h12" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/></svg>
          新增
        </button>
      </div>
    </header>

    <!-- 搜索栏 -->
    <div class="agent-search" v-show="showSearch">
      <div class="agent-search__field">
        <svg class="agent-search__icon" width="15" height="15" viewBox="0 0 15 15" fill="none">
          <circle cx="6.5" cy="6.5" r="5.5" stroke="currentColor" stroke-width="1.4"/>
          <path d="M10.5 10.5L14 14" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"/>
        </svg>
        <input v-model="queryParams.agentName" class="agent-search__input" placeholder="搜索智能体…" @keyup.enter="handleQuery" />
        <button type="button" v-if="queryParams.agentName" class="agent-search__clear" @click="queryParams.agentName = ''; handleQuery()">✕</button>
      </div>
      <select v-model="queryParams.status" class="agent-select" @change="handleQuery">
        <option value="">全部状态</option>
        <option v-for="dict in sys_normal_disable" :key="dict.value" :value="dict.value">{{ dict.label }}</option>
      </select>
      <button type="button" class="apple-btn apple-btn--ghost" @click="resetQuery">重置</button>
    </div>

    <!-- 卡片列表 -->
    <div v-loading="loading" class="agent-grid">
      <article
        class="agent-card"
        v-for="item in agentList"
        :key="item.agentId"
        :class="{ 'is-off': item.status !== '0' }"
        :style="{ '--accent': colorOf(item.agentCode, item.theme) }"
        @click="handleDetail(item)"
      >
        <span class="agent-card__rail"></span>
        <div class="agent-card__head">
          <div class="agent-card__avatar" :style="{ background: item.status === '0' ? gradientOf(item.agentCode, item.theme) : offGradient }">
            {{ item.icon || '🤖' }}
          </div>
          <div class="agent-card__ident">
            <h3 class="agent-card__name" :title="item.agentName">{{ item.agentName }}</h3>
            <div class="agent-card__sub">
              <span class="agent-card__code">{{ item.agentCode }}</span>
              <span v-if="item.isPublic === '1'" class="agent-card__public">公共</span>
              <span class="agent-card__status" :class="item.status === '0' ? 'is-on' : 'is-off'">
                <i></i>{{ item.status === '0' ? '已启用' : '已停用' }}
              </span>
            </div>
          </div>
          <div class="agent-card__actions">
            <button type="button" class="agent-card__action" @click.stop="handleUpdate(item)" title="编辑">
              <svg width="15" height="15" viewBox="0 0 16 16" fill="none"><path d="M11.5 2.5l2 2L5 13H3v-2l8.5-8.5z" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/></svg>
            </button>
            <button type="button" class="agent-card__action agent-card__action--danger" @click.stop="handleDelete(item)" title="删除">
              <svg width="15" height="15" viewBox="0 0 16 16" fill="none"><path d="M3 4.5h10M6.5 2.5h3M4.5 4.5l.5 9h6l.5-9M6.5 7v4M9.5 7v4" stroke="currentColor" stroke-width="1.2" stroke-linecap="round" stroke-linejoin="round"/></svg>
            </button>
          </div>
        </div>

        <p class="agent-card__desc" :title="item.agentDesc">{{ item.agentDesc || '暂无描述' }}</p>

        <div class="agent-card__foot">
          <div class="agent-card__caps">
            <span class="cap-badge" :class="{ 'is-zero': !item.skillCount }" title="关联技能">
              <svg width="12" height="12" viewBox="0 0 16 16" fill="none"><path d="M9 1.5L3 9h4l-1 5.5L13 7H9l1-5.5z" stroke="currentColor" stroke-width="1.3" stroke-linejoin="round"/></svg>
              {{ item.skillCount || 0 }}
            </span>
            <span class="cap-badge" :class="{ 'is-zero': !item.toolCount }" title="关联工具">
              <svg width="12" height="12" viewBox="0 0 16 16" fill="none"><path d="M10.5 5.5a3 3 0 01-4 4L3 13l1.5 1.5L8 11a3 3 0 004-4l-1.5 1.5-2-2L10.5 5.5z" stroke="currentColor" stroke-width="1.2" stroke-linejoin="round"/><path d="M10.5 5.5L13 3" stroke="currentColor" stroke-width="1.2" stroke-linecap="round"/></svg>
              {{ item.toolCount || 0 }}
            </span>
            <span class="cap-badge" :class="{ 'is-zero': !item.childCount }" title="子智能体">
              <svg width="12" height="12" viewBox="0 0 16 16" fill="none"><path d="M8 2v4M4 14v-2a2 2 0 012-2h4a2 2 0 012 2v2" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/><circle cx="8" cy="2.5" r="1.5" stroke="currentColor" stroke-width="1.3"/><circle cx="4" cy="13.5" r="1.5" stroke="currentColor" stroke-width="1.3"/><circle cx="12" cy="13.5" r="1.5" stroke="currentColor" stroke-width="1.3"/></svg>
              {{ item.childCount || 0 }}
            </span>
          </div>
          <span class="agent-card__model" :class="{ 'is-missing': !item.modelDisplayName }" :title="item.modelDisplayName || '未绑定模型'">
            {{ item.modelDisplayName || '未绑定模型' }}
          </span>
        </div>
      </article>

      <div class="agent-empty" v-if="!loading && agentList.length === 0">
        <div class="agent-empty__icon">🤖</div>
        <p class="agent-empty__text">还没有智能体</p>
        <button type="button" class="apple-btn apple-btn--add" @click="handleAdd">创建第一个</button>
      </div>
    </div>

    <!-- 分页 -->
    <div class="agent-pagination" v-show="total > 0">
      <pagination :total="total" v-model:page="queryParams.pageNum" v-model:limit="queryParams.pageSize" @pagination="getList" />
    </div>

    <!-- ==================== 详情面板（只读） ==================== -->
    <Teleport to="body">
      <Transition name="sheet">
        <div class="sheet-overlay" v-if="detailOpen" @click.self="closeDetail">
          <div class="sheet sheet--detail" role="dialog" aria-modal="true" aria-label="智能体详情">
            <!-- 渐变 hero -->
            <div class="hero" :style="{ background: detail.status === '0' ? gradientOf(detail.agentCode, detail.theme) : offGradient }">
              <button type="button" class="hero__close" aria-label="关闭" @click="closeDetail">✕</button>
              <div class="hero__body">
                <div class="hero__avatar">{{ detail.icon || '🤖' }}</div>
                <div class="hero__text">
                  <h2 class="hero__name">{{ detail.agentName }}</h2>
                  <div class="hero__sub">
                    <span class="hero__code">{{ detail.agentCode }}</span>
                    <span v-if="detail.isPublic === '1'" class="hero__public">公共智能体</span>
                    <span class="hero__status"><i :class="detail.status === '0' ? 'is-on' : 'is-off'"></i>{{ detail.status === '0' ? '已启用' : '已停用' }}</span>
                  </div>
                </div>
              </div>
              <div class="hero__stats">
                <div class="hero__stat"><b>{{ detailSkills.length }}</b><span>技能</span></div>
                <div class="hero__stat"><b>{{ detailTools.length }}</b><span>工具</span></div>
                <div class="hero__stat"><b>{{ (detail.childAgents || []).length }}</b><span>子智能体</span></div>
              </div>
            </div>

            <div class="sheet__body detail-body">
              <p class="detail-desc">{{ detail.agentDesc || '暂无描述' }}</p>

              <!-- 能力拓扑：一眼看清这个智能体被组装成什么样 -->
              <AgentTopology
                class="detail-topo"
                :name="detail.agentName"
                :code="detail.agentCode"
                :theme="detail.theme"
                :icon="detail.icon || '🤖'"
                :model="detail.modelDisplayName || detail.modelCode"
                :skills="topoSkills"
                :tools="topoTools"
                :children="topoChildren"
              />

              <div class="detail-cols">
                <!-- 主列：提示词才是这个页面的主角 -->
                <div class="detail-main">
                  <div class="detail-block__title">角色提示词</div>
                  <div v-if="detail.agentRole" class="detail-prompt md-body" v-html="renderedDetailRole"></div>
                  <div v-else class="detail-hollow">
                    <span>未设置角色提示词</span>
                  </div>
                </div>

                <!-- 侧列：配置一览，空的项收成一行，不占版面 -->
                <aside class="detail-side">
                  <div class="detail-block">
                    <div class="detail-block__title">运行配置</div>
                    <dl class="detail-kv">
                      <div class="detail-kv__row">
                        <dt>对话模型</dt>
                        <dd :class="{ 'is-missing': !detail.modelDisplayName && !detail.modelCode }">
                          {{ detail.modelDisplayName || detail.modelCode || '未绑定' }}
                        </dd>
                      </div>
                      <div class="detail-kv__row">
                        <dt>生图模型</dt>
                        <dd :class="{ 'is-missing': !detail.imageModelCode }">{{ modelLabel(detail.imageModelCode) }}</dd>
                      </div>
                      <div class="detail-kv__row">
                        <dt>视频模型</dt>
                        <dd :class="{ 'is-missing': !detail.videoModelCode }">{{ modelLabel(detail.videoModelCode) }}</dd>
                      </div>
                      <div class="detail-kv__row">
                        <dt>语音模型</dt>
                        <dd :class="{ 'is-missing': !detail.ttsModelCode }">{{ modelLabel(detail.ttsModelCode) }}</dd>
                      </div>
                      <div class="detail-kv__row">
                        <dt>本地文档</dt>
                        <dd>{{ detail.loadLocalDoc === '1' ? 'agents.md' : '未加载' }}</dd>
                      </div>
                      <div class="detail-kv__row">
                        <dt>公开范围</dt>
                        <dd>{{ detail.isPublic === '1' ? '公共智能体' : '私有/内部' }}</dd>
                      </div>
                      <div class="detail-kv__row">
                        <dt>排序</dt>
                        <dd>{{ detail.sort ?? 0 }}</dd>
                      </div>
                    </dl>
                  </div>

                  <!-- 技能/工具已在拓扑图里，这里只补拓扑放不下的文字信息 -->
                  <div class="detail-block" v-if="detail.childAgents && detail.childAgents.length">
                    <div class="detail-block__title">调度规则<span class="detail-block__count">{{ detail.childAgents.length }}</span></div>
                    <div class="detail-child-list">
                      <div class="detail-child" v-for="(c, i) in detail.childAgents" :key="i">
                        <span class="detail-child__idx" :style="{ background: gradientOf(c.childAgentCode || i) }">{{ i + 1 }}</span>
                        <div class="detail-child__body">
                          <div class="detail-child__name">{{ c.childAgentName || '（已删除的智能体）' }}</div>
                          <div class="detail-child__trigger">{{ c.triggerDesc || '未填写触发条件' }}</div>
                        </div>
                      </div>
                    </div>
                  </div>
                </aside>
              </div>
            </div>

            <div class="sheet__footer">
              <button type="button" class="apple-btn apple-btn--ghost" @click="closeDetail">关闭</button>
              <button type="button" class="apple-btn apple-btn--primary" @click="editFromDetail" v-hasPermi="['ai:agent:edit']">编辑</button>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>

    <!-- ==================== 编辑面板 ==================== -->
    <Teleport to="body">
      <Transition name="sheet">
        <div class="sheet-overlay" v-if="open" @click.self="cancel">
          <div class="sheet" :class="{ 'sheet--wide': wideSheet }" role="dialog" aria-modal="true">
            <div class="sheet__header">
              <h2 class="sheet__title">{{ title }}</h2>
              <button type="button" class="sheet__close" aria-label="关闭" @click="cancel">✕</button>
            </div>

            <div class="segmented">
              <button type="button" v-for="tab in tabs" :key="tab.key" class="segmented__item" :class="{ 'is-active': activeTab === tab.key }" @click="activeTab = tab.key">
                {{ tab.label }}
                <em class="segmented__num" v-if="tab.count && tabCount(tab.key)">{{ tabCount(tab.key) }}</em>
                <span class="segmented__dot" v-if="errorTabs.includes(tab.key)"></span>
              </button>
            </div>

            <div class="sheet__body" :class="{ 'is-fill': fillTabs.includes(activeTab) }">
              <el-form ref="agentRef" :model="form" :rules="rules" label-position="top" class="aform" @submit.prevent>
                <!-- ====== 基本信息 ====== -->
                <div v-show="activeTab === 'basic'" class="aform__section">
                  <!-- 外观：图标 + 主题色，给每个智能体一个可辨识的身份 -->
                  <div class="aform__group face">
                    <div class="face__preview">
                      <div class="face__avatar" :style="{ background: gradientOf(form.agentCode, form.theme), boxShadow: glowOf(form.agentCode, form.theme) }">
                        {{ form.icon || '🤖' }}
                      </div>
                      <span class="face__name">{{ form.agentName || '未命名智能体' }}</span>
                    </div>
                    <div class="face__pickers">
                      <div class="face__row">
                        <span class="face__label">图标</span>
                        <div class="face__icons">
                          <button
                            type="button"
                            v-for="ic in ICONS"
                            :key="ic"
                            class="face__icon"
                            :class="{ 'is-active': (form.icon || '🤖') === ic }"
                            @click="form.icon = ic"
                          >{{ ic }}</button>
                        </div>
                      </div>
                      <div class="face__row">
                        <span class="face__label">主题色</span>
                        <div class="face__themes">
                          <button
                            type="button"
                            class="face__theme"
                            :class="{ 'is-active': !form.theme }"
                            title="跟随编码自动取色"
                            @click="form.theme = ''"
                          >
                            <span class="face__auto">A</span>
                          </button>
                          <button
                            type="button"
                            v-for="i in THEME_COUNT"
                            :key="i"
                            class="face__theme"
                            :class="{ 'is-active': form.theme === String(i - 1) }"
                            :style="{ background: gradientOf('', String(i - 1)) }"
                            @click="form.theme = String(i - 1)"
                          ></button>
                        </div>
                      </div>
                    </div>
                  </div>

                  <div class="aform__group">
                    <div class="aform__row">
                      <el-form-item label="名称" prop="agentName" class="aform__item">
                        <el-input v-model="form.agentName" placeholder="如：代码审查员" />
                      </el-form-item>
                      <el-form-item v-if="form.agentId" label="编码" prop="agentCode" class="aform__item">
                        <el-input v-model="form.agentCode" placeholder="提交后自动生成" disabled />
                      </el-form-item>
                    </div>
                    <el-form-item label="描述" prop="agentDesc" class="aform__item">
                      <el-input v-model="form.agentDesc" type="textarea" :rows="2" placeholder="一句话描述这个智能体的用途" />
                    </el-form-item>
                  </div>

                  <div class="aform__group">
                    <el-form-item label="排序" prop="sort">
                      <el-input-number v-model="form.sort" :min="0" controls-position="right" style="width: 140px" />
                    </el-form-item>
                  </div>

                  <div class="aform__group aform__group--toggles">
                    <div class="toggle-row">
                      <div class="toggle-row__info">
                        <span class="toggle-row__label">加载本地文档</span>
                        <span class="toggle-row__hint">开启后将在系统提示中自动加载工作目录下的 agents.md 文件</span>
                      </div>
                      <el-switch v-model="form.loadLocalDoc" active-value="1" inactive-value="0" />
                    </div>
                    <div class="toggle-row">
                      <div class="toggle-row__info">
                        <span class="toggle-row__label">公共智能体</span>
                        <span class="toggle-row__hint">用于标记面向全体用户的智能体，可供后续权限策略使用</span>
                      </div>
                      <el-switch v-model="form.isPublic" active-value="1" inactive-value="0" />
                    </div>
                    <div class="toggle-row">
                      <div class="toggle-row__info">
                        <span class="toggle-row__label">启用状态</span>
                        <span class="toggle-row__hint">停用后不可被调用</span>
                      </div>
                      <el-switch v-model="form.status" active-value="0" inactive-value="1" />
                    </div>
                  </div>
                </div>

                <!-- ====== 模型绑定 ====== -->
                <div v-show="activeTab === 'models'" class="aform__section">
                  <div class="model-bind">
                    <p class="model-bind__lead">对话模型是智能体的大脑，必须绑定。生图、视频、语音按需选填，绑了才会出现对应能力。</p>

                    <article class="model-card model-card--hero" :class="{ 'is-bound': !!form.modelCode }">
                      <div class="model-card__head">
                        <span class="model-card__icon" aria-hidden="true">
                          <svg width="18" height="18" viewBox="0 0 16 16" fill="none">
                            <path d="M3 3.5h10A1.5 1.5 0 0114.5 5v5A1.5 1.5 0 0113 11.5H8l-3.2 2.2A.5.5 0 014 13.3V11.5H3A1.5 1.5 0 011.5 10V5A1.5 1.5 0 013 3.5z" stroke="currentColor" stroke-width="1.3" stroke-linejoin="round"/>
                          </svg>
                        </span>
                        <div class="model-card__titles">
                          <div class="model-card__title-row">
                            <h3 class="model-card__title">对话模型</h3>
                            <span class="model-tag model-tag--req">必须</span>
                          </div>
                          <p class="model-card__hint">负责思考、回复，以及编排技能和工具</p>
                        </div>
                      </div>
                      <el-form-item prop="modelCode" class="model-card__field">
                        <el-select v-model="form.modelCode" placeholder="请选择对话模型" filterable clearable no-data-text="暂无可用的对话模型" style="width: 100%">
                          <el-option v-for="m in chatModelOptions" :key="m.modelCode" :label="m.displayName" :value="m.modelCode">
                            <div class="model-option">
                              <span class="model-option__name">{{ m.displayName }}</span>
                              <span class="model-option__code">{{ m.modelCode }}</span>
                            </div>
                          </el-option>
                        </el-select>
                      </el-form-item>
                    </article>

                    <div class="model-bind__opt-head">
                      <span class="model-bind__opt-title">可选能力</span>
                      <span class="model-bind__opt-note">不绑定不影响正常对话</span>
                    </div>

                    <div class="model-bind__grid">
                      <article class="model-card model-card--image" :class="{ 'is-bound': !!form.imageModelCode }">
                        <div class="model-card__head">
                          <span class="model-card__icon" aria-hidden="true">
                            <svg width="16" height="16" viewBox="0 0 16 16" fill="none">
                              <rect x="2" y="3" width="12" height="10" rx="1.6" stroke="currentColor" stroke-width="1.3"/>
                              <circle cx="5.5" cy="6.4" r="1.1" stroke="currentColor" stroke-width="1.2"/>
                              <path d="M2.8 11.4l3.4-3.2 2.2 2 2.1-2.4 3 3.6" stroke="currentColor" stroke-width="1.2" stroke-linecap="round" stroke-linejoin="round"/>
                            </svg>
                          </span>
                          <div class="model-card__titles">
                            <div class="model-card__title-row">
                              <h3 class="model-card__title">生图模型</h3>
                              <span class="model-tag model-tag--opt">选填</span>
                            </div>
                            <p class="model-card__hint">绑定后可生成和编辑图片</p>
                          </div>
                        </div>
                        <el-form-item prop="imageModelCode" class="model-card__field">
                          <el-select v-model="form.imageModelCode" placeholder="不绑定" filterable clearable no-data-text="暂无生图模型" style="width: 100%">
                            <el-option v-for="m in imageModelOptions" :key="m.modelCode" :label="m.displayName" :value="m.modelCode">
                              <div class="model-option">
                                <span class="model-option__name">{{ m.displayName }}</span>
                                <span class="model-option__code">{{ m.modelCode }}</span>
                              </div>
                            </el-option>
                          </el-select>
                        </el-form-item>
                        <p v-if="!imageModelOptions.length" class="model-card__empty">模型管理里还没有生图模型</p>
                      </article>

                      <article class="model-card model-card--video" :class="{ 'is-bound': !!form.videoModelCode }">
                        <div class="model-card__head">
                          <span class="model-card__icon" aria-hidden="true">
                            <svg width="16" height="16" viewBox="0 0 16 16" fill="none">
                              <rect x="1.8" y="3.2" width="8.6" height="9.6" rx="1.5" stroke="currentColor" stroke-width="1.3"/>
                              <path d="M10.4 6.4l3.3-1.7c.4-.2.8.1.8.5v5.6c0 .4-.4.7-.8.5l-3.3-1.7" stroke="currentColor" stroke-width="1.3" stroke-linejoin="round"/>
                            </svg>
                          </span>
                          <div class="model-card__titles">
                            <div class="model-card__title-row">
                              <h3 class="model-card__title">视频模型</h3>
                              <span class="model-tag model-tag--opt">选填</span>
                            </div>
                            <p class="model-card__hint">绑定后可按描述生成视频</p>
                          </div>
                        </div>
                        <el-form-item prop="videoModelCode" class="model-card__field">
                          <el-select v-model="form.videoModelCode" placeholder="不绑定" filterable clearable no-data-text="暂无视频模型" style="width: 100%">
                            <el-option v-for="m in videoModelOptions" :key="m.modelCode" :label="m.displayName" :value="m.modelCode">
                              <div class="model-option">
                                <span class="model-option__name">{{ m.displayName }}</span>
                                <span class="model-option__code">{{ m.modelCode }}</span>
                              </div>
                            </el-option>
                          </el-select>
                        </el-form-item>
                        <p v-if="!videoModelOptions.length" class="model-card__empty">模型管理里还没有视频模型</p>
                      </article>

                      <article class="model-card model-card--tts" :class="{ 'is-bound': !!form.ttsModelCode }">
                        <div class="model-card__head">
                          <span class="model-card__icon" aria-hidden="true">
                            <svg width="16" height="16" viewBox="0 0 16 16" fill="none">
                              <path d="M8 2.6v10.8M5.2 5v6M2.6 6.6v2.8M10.8 5v6M13.4 6.6v2.8" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"/>
                            </svg>
                          </span>
                          <div class="model-card__titles">
                            <div class="model-card__title-row">
                              <h3 class="model-card__title">语音模型</h3>
                              <span class="model-tag model-tag--opt">选填</span>
                            </div>
                            <p class="model-card__hint">绑定后可把文字合成语音</p>
                          </div>
                        </div>
                        <el-form-item prop="ttsModelCode" class="model-card__field">
                          <el-select v-model="form.ttsModelCode" placeholder="不绑定" filterable clearable no-data-text="暂无语音模型" style="width: 100%">
                            <el-option v-for="m in ttsModelOptions" :key="m.modelCode" :label="m.displayName" :value="m.modelCode">
                              <div class="model-option">
                                <span class="model-option__name">{{ m.displayName }}</span>
                                <span class="model-option__code">{{ m.modelCode }}</span>
                              </div>
                            </el-option>
                          </el-select>
                        </el-form-item>
                        <p v-if="!ttsModelOptions.length" class="model-card__empty">模型管理里还没有语音模型</p>
                      </article>
                    </div>
                  </div>
                </div>

                <!-- ====== 角色提示词 ====== -->
                <div v-show="activeTab === 'prompt'" class="aform__section">
                  <div class="aform__group aform__group--full">
                    <div class="role-section">
                      <div class="role-section__header">
                        <span class="role-section__title">角色提示词</span>
                        <div class="role-section__tools">
                          <span class="role-section__len" v-if="form.agentRole">{{ form.agentRole.length }} 字</span>
                          <button type="button" class="role-section__btn" @click="promptExpanded = !promptExpanded">
                            {{ promptExpanded ? '收起' : '展开' }}
                          </button>
                          <button type="button" class="role-section__btn" @click="roleEditing = !roleEditing">
                            <svg v-if="!roleEditing" width="13" height="13" viewBox="0 0 16 16" fill="none"><path d="M11.5 2.5l2 2L5 13H3v-2l8.5-8.5z" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/></svg>
                            {{ roleEditing ? '完成' : '编辑' }}
                          </button>
                        </div>
                      </div>

                      <div v-if="!roleEditing && form.agentRole" class="role-preview md-body" v-html="renderedRole"></div>
                      <div v-if="!roleEditing && !form.agentRole" class="role-preview role-preview--empty">
                        <p>还没有角色提示词</p>
                        <button type="button" class="apple-btn apple-btn--outline" @click="roleEditing = true">开始编写</button>
                      </div>

                      <div v-if="roleEditing" class="md-editor">
                        <div class="md-editor__pane">
                          <div class="md-editor__pane-tag">Markdown</div>
                          <textarea
                            v-model="form.agentRole"
                            class="md-editor__textarea"
                            placeholder="输入系统提示词…&#10;&#10;支持 Markdown 语法：&#10;# 标题&#10;**加粗** *斜体*&#10;- 列表项&#10;`代码`"
                            spellcheck="false"
                          ></textarea>
                        </div>
                        <div class="md-editor__divider"></div>
                        <div class="md-editor__pane md-editor__pane--preview">
                          <div class="md-editor__pane-tag">预览</div>
                          <div class="md-editor__preview md-body" v-html="renderedRole"></div>
                          <div v-if="!form.agentRole" class="md-editor__placeholder">实时预览</div>
                        </div>
                      </div>
                    </div>
                  </div>
                </div>

                <!-- ====== 能力配置 ====== -->
                <div v-show="activeTab === 'ability'" class="aform__section">
                  <div class="cap-switch">
                    <button type="button" class="cap-switch__item" :class="{ 'is-active': abilityKind === 'skill' }" @click="abilityKind = 'skill'">
                      技能<em>{{ (form.skillIds || []).length }}</em>
                    </button>
                    <button type="button" class="cap-switch__item" :class="{ 'is-active': abilityKind === 'tool' }" @click="abilityKind = 'tool'">
                      工具<em>{{ (form.toolIds || []).length }}</em>
                    </button>
                    <span class="cap-switch__hint">
                      {{ abilityKind === 'skill' ? '技能按选中顺序注入系统提示词'
                        : '工具会注册到 LLM function calling' }}
                    </span>
                  </div>

                  <CapabilityPicker
                    v-show="abilityKind === 'skill'"
                    v-model="form.skillIds"
                    :items="skillOptions"
                    id-key="skillId"
                    name-key="skillName"
                    code-key="skillCode"
                    desc-key="description"
                    :group-by="skillGroupBy"
                    :disabled-by="statusDisabled"
                    search-placeholder="搜索技能名称、编码或描述…"
                    empty-text="还没有可用技能，先去「技能管理」创建"
                    order-hint="按此顺序注入"
                  />

                  <CapabilityPicker
                    v-show="abilityKind === 'tool'"
                    v-model="form.toolIds"
                    :items="toolOptions"
                    id-key="toolId"
                    name-key="toolName"
                    code-key="toolCode"
                    desc-key="description"
                    :group-by="toolGroupBy"
                    :tag-by="toolTagBy"
                    :disabled-by="statusDisabled"
                    search-placeholder="搜索工具名称、编码或描述…"
                    empty-text="还没有可用工具，先去「工具管理」或「MCP 服务」同步"
                  />
                </div>

                <!-- ====== 子智能体 ====== -->
                <div v-show="activeTab === 'child'" class="aform__section">
                  <div class="aform__group">
                    <div class="aform__group-label">子智能体编排</div>
                    <p class="aform__group-desc">配置当前智能体可调度的下级智能体，实现多 Agent 协作</p>
                    <div class="child-list" v-if="form.childAgents && form.childAgents.length > 0">
                      <div class="child-item" v-for="(child, idx) in form.childAgents" :key="idx">
                        <span class="child-item__index">{{ idx + 1 }}</span>
                        <div class="child-item__fields">
                          <el-select v-model="child.childAgentId" placeholder="选择子智能体" filterable style="width: 100%">
                            <el-option v-for="a in allAgentOptions" :key="a.agentId" :label="a.agentName + ' (' + a.agentCode + ')'" :value="a.agentId" :disabled="a.agentId === form.agentId" />
                          </el-select>
                          <el-input
                            v-model="child.triggerDesc"
                            type="textarea"
                            :rows="2"
                            placeholder="触发条件（写给大模型看）：例如「当用户提交的是 SQL 或数据库相关问题时，转交此智能体处理」"
                            style="margin-top: 8px"
                          />
                        </div>
                        <button type="button" class="child-item__remove" aria-label="移除" @click="removeChildAgent(idx)">✕</button>
                      </div>
                    </div>
                    <div class="child-empty" v-else>
                      <span class="child-empty__icon">🔗</span>
                      <p>暂未配置子智能体</p>
                    </div>
                    <button type="button" class="apple-btn apple-btn--outline" @click="addChildAgent" style="margin-top: 14px">
                      <svg width="12" height="12" viewBox="0 0 12 12" fill="none"><path d="M6 1v10M1 6h10" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/></svg>
                      添加子智能体
                    </button>
                  </div>
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
  </div>
</template>

<script setup name="Agent">
import { marked } from 'marked'
import CapabilityPicker from './CapabilityPicker.vue'
import AgentTopology from './AgentTopology.vue'
import { gradientOf, colorOf, softOf, glowOf, THEME_COUNT } from '@/utils/ai-palette'
import { listAgent, listAllAgent, getAgent, addAgent, updateAgent, delAgent } from '@/api/ai/agent'
import { listModel } from '@/api/ai/model'
import { listSkill } from '@/api/ai/skill'
import { listTool } from '@/api/ai/tool'

const { proxy } = getCurrentInstance()
const { sys_normal_disable } = proxy.useDict('sys_normal_disable')

marked.setOptions({ breaks: true, gfm: true })

const offGradient = 'linear-gradient(135deg, #A1A1A6, #C7C7CC)'

// 可选图标，覆盖常见智能体角色
const ICONS = [
  '🤖', '🧠', '💬', '🔍', '📊', '📝', '💻', '🛠️',
  '📚', '🎯', '⚡', '🧪', '🗂️', '🌐', '🔐', '🎨'
]

const agentList = ref([])
const open = ref(false)
const detailOpen = ref(false)
const detail = ref({})
const loading = ref(true)
const showSearch = ref(true)
const total = ref(0)
const title = ref('')
const activeTab = ref('basic')
const abilityKind = ref('skill')
const roleEditing = ref(false)
const promptExpanded = ref(false)
const errorTabs = ref([])

const tabs = [
  { key: 'basic', label: '基本信息' },
  { key: 'models', label: '模型绑定', count: true },
  { key: 'prompt', label: '角色提示词' },
  { key: 'ability', label: '能力配置', count: true },
  { key: 'child', label: '子智能体', count: true }
]

// 这两个 tab 的内容需要撑满面板高度（编辑器 / 选择器都要靠内部滚动）
const fillTabs = ['prompt', 'ability']

const propTabMap = {
  agentName: 'basic', agentCode: 'basic', agentDesc: 'basic', sort: 'basic',
  modelCode: 'models', imageModelCode: 'models', videoModelCode: 'models', ttsModelCode: 'models'
}

const modelOptions = ref([])
const skillOptions = ref([])
const toolOptions = ref([])
const allAgentOptions = ref([])

const chatModelOptions = computed(() => modelOptions.value.filter(m => m.modelType === 'CHAT'))
// 生图模型选项:modelType=IMAGE。绑定后智能体装配期自动获得 drawImage 工具。
const imageModelOptions = computed(() => modelOptions.value.filter(m => m.modelType === 'IMAGE'))
// 视频模型选项:modelType=VIDEO。绑定后智能体装配期自动获得 drawVideo 工具。
const videoModelOptions = computed(() => modelOptions.value.filter(m => m.modelType === 'VIDEO'))
const ttsModelOptions = computed(() => modelOptions.value.filter(m => m.modelType === 'TTS'))

const data = reactive({
  form: {},
  queryParams: { pageNum: 1, pageSize: 12, agentCode: undefined, agentName: undefined, status: undefined },
  rules: {
    // agentCode:新增时由后端兜底生成,UI 不展示、不校验;编辑时只读回显
    agentName: [{ required: true, message: '智能体名称不能为空', trigger: 'blur' }],
    modelCode: [{ required: true, message: '请选择对话模型', trigger: 'change' }]
  }
})

const { queryParams, form, rules } = toRefs(data)

const wideSheet = computed(() => (promptExpanded.value && activeTab.value === 'prompt') || activeTab.value === 'ability')

// 能力选择器的分组 / 角标规则
function skillGroupBy(s) { return s.category || '未分类' }
function toolGroupBy(t) {
  if (t.toolType === '2') return t.mcpServerName || 'MCP 工具'
  return t.category || '内置工具'
}
function toolTagBy(t) { return t.toolType === '2' ? 'MCP' : '内置' }
function statusDisabled(x) { return x.status !== '0' }

function tabCount(key) {
  if (key === 'models') {
    return [form.value.modelCode, form.value.imageModelCode, form.value.videoModelCode, form.value.ttsModelCode]
      .filter(Boolean).length
  }
  if (key === 'ability') return (form.value.skillIds || []).length + (form.value.toolIds || []).length
  if (key === 'child') return (form.value.childAgents || []).length
  return 0
}

function modelLabel(code) {
  if (!code) return '未绑定'
  const m = modelOptions.value.find(x => x.modelCode === code)
  return m ? (m.displayName || m.modelCode) : code
}

const renderedRole = computed(() => (form.value.agentRole ? marked(form.value.agentRole) : ''))
const renderedDetailRole = computed(() => (detail.value.agentRole ? marked(detail.value.agentRole) : ''))

const detailSkills = computed(() =>
  (detail.value.skillIds || []).map(id => {
    const s = skillOptions.value.find(x => x.skillId === id)
    const group = s ? s.category || '未分类' : '已失效'
    return {
      key: id,
      name: s ? s.skillName : '已删除的技能 #' + id,
      code: s ? s.skillCode : '',
      color: colorOf(group),
      soft: softOf(group)
    }
  })
)

const detailTools = computed(() =>
  (detail.value.toolIds || []).map(id => {
    const t = toolOptions.value.find(x => x.toolId === id)
    const group = t ? toolGroupBy(t) : '已失效'
    return {
      key: id,
      name: t ? t.toolName : '已删除的工具 #' + id,
      isMcp: t ? t.toolType === '2' : false,
      color: colorOf(group),
      soft: softOf(group)
    }
  })
)

// 拓扑图的三条分支数据
const topoSkills = computed(() => detailSkills.value.map(s => ({ text: s.name, hint: s.code || s.name, color: s.color })))
const topoTools = computed(() => detailTools.value.map(t => ({ text: t.name, hint: t.name, color: t.color, tag: t.isMcp ? 'MCP' : '' })))
const topoChildren = computed(() =>
  (detail.value.childAgents || []).map(c => ({
    text: c.childAgentName || '已删除',
    hint: c.triggerDesc || '未填写触发条件',
    color: colorOf(c.childAgentCode || '')
  }))
)

/* ==================== 列表 ==================== */

function getList() {
  loading.value = true
  listAgent(queryParams.value).then(response => {
    agentList.value = response.rows
    total.value = response.total
    loading.value = false
  })
}

let optionsLoaded = false
function loadOptions(force = false) {
  if (optionsLoaded && !force) return Promise.resolve()
  return Promise.all([
    listModel({ pageNum: 1, pageSize: 999 }).then(res => { modelOptions.value = res.rows || [] }),
    listSkill({ pageNum: 1, pageSize: 999 }).then(res => { skillOptions.value = res.rows || [] }),
    listTool({ pageNum: 1, pageSize: 999 }).then(res => { toolOptions.value = res.rows || [] }),
    listAllAgent().then(res => { allAgentOptions.value = res.data || [] })
  ]).then(() => { optionsLoaded = true })
}

function handleQuery() {
  queryParams.value.pageNum = 1
  getList()
}

function resetQuery() {
  queryParams.value.agentName = undefined
  queryParams.value.status = undefined
  handleQuery()
}

function normalize(d) {
  d.skillIds = d.skillIds || []
  d.toolIds = d.toolIds || []
  d.childAgents = d.childAgents || []
  return d
}

/* ==================== 详情（只读） ==================== */

function handleDetail(row) {
  Promise.all([loadOptions(), getAgent(row.agentId)]).then(([, res]) => {
    detail.value = normalize(res.data)
    detailOpen.value = true
  })
}

function closeDetail() {
  detailOpen.value = false
}

function editFromDetail() {
  const copy = JSON.parse(JSON.stringify(detail.value))
  detailOpen.value = false
  reset()
  openEditor(normalize(copy), '编辑智能体')
}

/* ==================== 编辑 ==================== */

let formSnapshot = ''

function takeSnapshot() {
  formSnapshot = JSON.stringify(form.value)
}

function isDirty() {
  return JSON.stringify(form.value) !== formSnapshot
}

function reset() {
  form.value = {
    agentId: undefined, agentCode: undefined, agentName: undefined,
    agentDesc: undefined, agentRole: undefined, loadLocalDoc: '0', isPublic: '0',
    icon: '🤖', theme: '',
    modelCode: undefined, imageModelCode: undefined, videoModelCode: undefined, ttsModelCode: undefined, sort: 0, status: '0', remark: undefined,
    skillIds: [], toolIds: [], childAgents: []
  }
  activeTab.value = 'basic'
  abilityKind.value = 'skill'
  roleEditing.value = false
  promptExpanded.value = false
  errorTabs.value = []
  proxy.resetForm('agentRef')
}

function openEditor(payload, sheetTitle) {
  form.value = payload
  title.value = sheetTitle
  open.value = true
  takeSnapshot()
}

function handleAdd() {
  reset()
  loadOptions()
  roleEditing.value = true
  openEditor(form.value, '新增智能体')
}

function handleUpdate(row) {
  reset()
  Promise.all([loadOptions(), getAgent(row.agentId)]).then(([, res]) => {
    openEditor(normalize(res.data), '编辑智能体')
  })
}

function closeEditor() {
  open.value = false
  reset()
  formSnapshot = ''
}

function cancel() {
  if (!isDirty()) {
    closeEditor()
    return
  }
  proxy.$modal
    .confirm('有未保存的修改，关闭后将丢失。确定关闭吗？')
    .then(closeEditor)
    .catch(() => {})
}

function submitForm() {
  proxy.$refs['agentRef'].validate((valid, fields) => {
    if (!valid) {
      const props = Object.keys(fields || {})
      errorTabs.value = [...new Set(props.map(p => propTabMap[p] || 'basic'))]
      if (errorTabs.value.length) activeTab.value = errorTabs.value[0]
      return
    }
    errorTabs.value = []

    const children = form.value.childAgents || []
    if (children.some(c => !c.childAgentId)) {
      proxy.$modal.msgError('请完善子智能体配置')
      errorTabs.value = ['child']
      activeTab.value = 'child'
      return
    }
    children.forEach((c, i) => { c.sort = i })

    const isEdit = form.value.agentId != null
    // clearable 清空后是 null,不转成空串的话后端 MyBatis 会跳过 SET,旧模型还在
    const payload = {
      ...form.value,
      modelCode: form.value.modelCode || '',
      imageModelCode: form.value.imageModelCode || '',
      videoModelCode: form.value.videoModelCode || '',
      ttsModelCode: form.value.ttsModelCode || ''
    }
    const req = isEdit ? updateAgent(payload) : addAgent(payload)
    req.then(() => {
      proxy.$modal.msgSuccess(isEdit ? '修改成功' : '新增成功')
      closeEditor()
      optionsLoaded = false
      getList()
    })
  })
}

function handleDelete(row) {
  proxy.$modal.confirm('确认删除智能体「' + row.agentName + '」？关联配置将一并清除。').then(() => {
    return delAgent(row.agentId)
  }).then(() => {
    optionsLoaded = false
    getList()
    proxy.$modal.msgSuccess('删除成功')
  }).catch(() => {})
}

function addChildAgent() {
  if (!form.value.childAgents) form.value.childAgents = []
  form.value.childAgents.push({ childAgentId: null, triggerDesc: '', sort: form.value.childAgents.length })
}

function removeChildAgent(index) {
  form.value.childAgents.splice(index, 1)
}

function onKeydown(e) {
  if (e.key === 'Escape' && detailOpen.value) closeDetail()
}

onMounted(() => document.addEventListener('keydown', onKeydown))
onUnmounted(() => document.removeEventListener('keydown', onKeydown))

getList()
</script>

<style scoped lang="scss">
@use '../../../assets/styles/ai-tokens.scss' as *;

// 设计令牌见 @/assets/styles/ai-tokens.scss + ai-theme.scss（支持暗色）
$spring: cubic-bezier(0.34, 1.56, 0.64, 1);

.agent-page {
  font-family: $font; padding: 40px 48px; min-height: calc(100vh - 84px); -webkit-font-smoothing: antialiased;
  /* 纯灰太平，加两团极淡的色晕撑起层次 */
  background:
    var(--ai-page-bg);
  @media (max-width: 768px) { padding: 24px 16px; }
}

/* Header */
.agent-header {
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
  &--add { background: $blue; color: #fff; box-shadow: 0 2px 10px rgba(10,132,255,0.32); &:hover { background: #0071e3; } }
  &--primary { background: $blue; color: #fff; padding: 10px 24px; box-shadow: 0 2px 10px rgba(10,132,255,0.32); &:hover { background: #0071e3; } }
  &--ghost { background: transparent; color: $blue; padding: 10px 16px; &:hover { background: rgba(10,132,255,0.08); } }
  &--outline { background: transparent; color: $blue; border: 1.5px solid rgba(10,132,255,0.35); padding: 7px 16px; &:hover { background: rgba(10,132,255,0.06); border-color: $blue; } }
}

/* Search */
.agent-search {
  display: flex; align-items: center; gap: 10px; margin-bottom: 24px; flex-wrap: wrap;
  &__field { position: relative; flex: 1; min-width: 220px; max-width: 360px; }
  &__icon { position: absolute; left: 13px; top: 50%; transform: translateY(-50%); color: $gray2; pointer-events: none; }
  &__input {
    width: 100%; height: 38px; padding: 0 32px 0 36px; border: none; border-radius: 980px;
    background: var(--ai-search-bg); font-size: 14px; font-family: $font; color: $text; outline: none; transition: all 0.25s $ease;
    box-shadow: 0 1px 3px var(--ai-border);
    &::placeholder { color: $gray2; }
    &:focus { background: var(--ai-card-bg); box-shadow: 0 0 0 4px rgba(10,132,255,0.12), 0 2px 12px var(--ai-border-2); }
  }
  &__clear {
    position: absolute; right: 10px; top: 50%; transform: translateY(-50%); width: 18px; height: 18px;
    border: none; border-radius: 50%; background: $gray3; color: #fff; font-size: 9px; cursor: pointer;
    display: flex; align-items: center; justify-content: center; &:hover { background: $gray; }
  }
}
.agent-select {
  height: 36px; padding: 0 28px 0 12px; border: none; border-radius: 8px; background: var(--ai-search-bg);
  box-shadow: 0 1px 3px var(--ai-border);
  font-size: 13px; font-family: $font; color: $text; appearance: none; cursor: pointer; outline: none;
  background-image: url("data:image/svg+xml,%3Csvg width='10' height='6' viewBox='0 0 10 6' fill='none' xmlns='http://www.w3.org/2000/svg'%3E%3Cpath d='M1 1l4 4 4-4' stroke='%238E8E93' stroke-width='1.5' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E");
  background-repeat: no-repeat; background-position: right 10px center;
}

/* ==================== 卡片 ==================== */
.agent-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(330px, 1fr)); gap: 16px; min-height: 180px; @media (max-width: 768px) { grid-template-columns: 1fr; } }
.agent-card {
  position: relative; display: flex; flex-direction: column; gap: 10px; padding: 16px 18px 14px;
  background: var(--ai-card-bg); border: 1px solid var(--ai-border); border-radius: 16px; cursor: pointer;
  box-shadow: 0 1px 2px var(--ai-fill-2); transition: all 0.28s $ease; overflow: hidden;
  &:hover {
    box-shadow: var(--ai-shadow-card); transform: translateY(-3px); border-color: var(--ai-input-bg);
    .agent-card__actions { opacity: 1; transform: translateY(0); }
    .agent-card__rail { opacity: 1; }
  }
  &:active { transform: translateY(-1px) scale(0.995); }
  &.is-off { background: var(--ai-card-off); .agent-card__name { color: $text2; } }

  /* 左侧色条：每个智能体一个稳定的强调色 */
  &__rail {
    position: absolute; left: 0; top: 0; bottom: 0; width: 3px;
    background: var(--accent); opacity: 0; transition: opacity 0.28s $ease;
  }

  &__head { display: flex; align-items: center; gap: 11px; }
  &__avatar {
    width: 40px; height: 40px; border-radius: 12px; display: flex; align-items: center; justify-content: center;
    font-size: 17px; font-weight: 600; color: #fff; flex-shrink: 0;
    box-shadow: 0 3px 10px rgba(0,0,0,0.14);
  }
  &__ident { flex: 1; min-width: 0; }
  &__name { font-size: 15px; font-weight: 600; color: $text; margin: 0 0 3px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  &__sub { display: flex; align-items: center; gap: 7px; min-width: 0; }
  &__code { font-family: $mono; font-size: 10.5px; color: $gray; background: var(--ai-fill-2); padding: 1.5px 6px; border-radius: 4px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  &__public { color: #0071e3; background: rgba(10,132,255,0.12); padding: 1.5px 6px; border-radius: 4px; font-size: 10.5px; font-weight: 600; flex-shrink: 0; }
  &__status {
    display: inline-flex; align-items: center; gap: 4px; font-size: 11px; flex-shrink: 0;
    i { width: 6px; height: 6px; border-radius: 50%; display: inline-block; }
    &.is-on { color: #248A3D; i { background: $green; box-shadow: 0 0 0 2.5px rgba(52,199,89,0.18); } }
    &.is-off { color: $gray; i { background: $gray2; } }
  }
  &__desc {
    font-size: 13px; color: $text2; margin: 0; line-height: 1.5; min-height: 39px;
    display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
  }
  &__foot {
    display: flex; align-items: center; justify-content: space-between; gap: 10px;
    padding-top: 11px; border-top: 1px solid var(--ai-border);
  }
  &__caps { display: flex; align-items: center; gap: 6px; }
  &__model {
    font-size: 11.5px; color: $text2; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; min-width: 0;
    &.is-missing { color: #C24A00; }
  }
  &__actions { display: flex; gap: 4px; opacity: 0; transform: translateY(-3px); transition: all 0.22s $ease; flex-shrink: 0; }
  &__action {
    width: 27px; height: 27px; border: none; border-radius: 8px; background: var(--ai-border); color: $text2;
    cursor: pointer; display: flex; align-items: center; justify-content: center; transition: all 0.18s;
    &:hover { background: rgba(10,132,255,0.12); color: $blue; }
    &--danger:hover { background: rgba(255,59,48,0.12); color: $red; }
  }
}
.cap-badge {
  display: inline-flex; align-items: center; gap: 3px; font-size: 11.5px; font-weight: 600;
  color: $text2; background: var(--ai-fill-2); padding: 3px 7px; border-radius: 6px;
  font-variant-numeric: tabular-nums;
  svg { opacity: 0.75; }
  &.is-zero { color: $gray3; background: var(--ai-fill-1); }
}
.agent-empty { grid-column: 1 / -1; text-align: center; padding: 72px 0; &__icon { font-size: 44px; margin-bottom: 14px; } &__text { font-size: 16px; color: $gray; margin: 0 0 18px; } }
.agent-pagination { margin-top: 28px; display: flex; justify-content: center; }

/* ==================== Sheet ==================== */
.sheet-overlay { position: fixed; inset: 0; z-index: 2000; background: var(--ai-overlay); backdrop-filter: blur(5px); display: flex; align-items: center; justify-content: center; padding: 32px; }
.sheet {
  width: 100%; max-width: 820px; height: min(760px, 88vh); background: var(--ai-card-bg);
  border-radius: 20px; box-shadow: var(--ai-shadow-sheet);
  display: flex; flex-direction: column; overflow: hidden;
  transition: max-width 0.3s $ease, height 0.3s $ease;
  &--wide { max-width: 1120px; height: min(880px, 94vh); }
  &--detail { max-width: 940px; height: auto; max-height: 88vh; }
  &__header { display: flex; align-items: center; justify-content: space-between; padding: 22px 28px 0; flex-shrink: 0; }
  &__title { font-size: 21px; font-weight: 700; color: $text; margin: 0; }
  &__close { width: 28px; height: 28px; border: none; border-radius: 50%; background: var(--ai-fill-3); color: $gray; font-size: 12px; cursor: pointer; display: flex; align-items: center; justify-content: center; flex-shrink: 0; &:hover { background: var(--ai-hover-strong); color: $text; } }
  &__body {
    flex: 1; min-height: 0; overflow-y: auto; padding: 20px 28px 24px;
    &::-webkit-scrollbar { width: 5px; } &::-webkit-scrollbar-thumb { background: var(--ai-border-4); border-radius: 3px; }
    /* 提示词 / 能力配置：外层不滚，内部组件自己撑满并滚动 */
    &.is-fill {
      overflow: hidden; display: flex; flex-direction: column;
      .aform { flex: 1; min-height: 0; display: flex; flex-direction: column; }
      .aform__section { flex: 1; min-height: 0; display: flex; flex-direction: column; }
      .aform__group--full { flex: 1; min-height: 0; display: flex; flex-direction: column; }
      .role-section { flex: 1; min-height: 0; display: flex; flex-direction: column; }
      /* 空态选择器保持紧凑，不要被拉成一整屏空白 */
      .cap-picker:not(.cap-picker--void) { flex: 1; min-height: 0; }
    }
  }
  &__footer { display: flex; justify-content: flex-end; gap: 10px; padding: 14px 28px 22px; border-top: 1px solid var(--ai-fill-3); flex-shrink: 0; }
}

/* Segmented */
.segmented {
  display: flex; margin: 14px 28px 0; background: var(--ai-fill-2); border-radius: 10px; padding: 3px; flex-shrink: 0;
  &__item {
    position: relative; flex: 1; display: inline-flex; align-items: center; justify-content: center; gap: 5px;
    padding: 7px 12px; border: none; border-radius: 8px; background: transparent;
    font-size: 13px; font-weight: 500; font-family: $font; color: $text2; cursor: pointer; transition: all 0.22s $ease;
    &.is-active { background: var(--ai-card-bg); color: $text; box-shadow: 0 1px 5px var(--ai-hover-strong); }
    &:not(.is-active):hover { color: $text; }
  }
  &__num {
    font-style: normal; font-size: 10.5px; font-weight: 700; color: #fff; background: $blue;
    min-width: 16px; height: 16px; padding: 0 4px; border-radius: 980px;
    display: inline-flex; align-items: center; justify-content: center;
  }
  &__dot { position: absolute; top: 5px; right: 7px; width: 6px; height: 6px; border-radius: 50%; background: $red; }
}

/* Form */
.aform {
  :deep(.el-form-item) { margin-bottom: 14px; }
  :deep(.el-form-item__label) { font-size: 13px; font-weight: 500; color: $text2; padding-bottom: 4px; }
  :deep(.el-input__wrapper) { border-radius: $radius-sm; box-shadow: 0 0 0 1px var(--ai-border-3) inset; background: var(--ai-input-bg); transition: all 0.2s $ease; &:hover { box-shadow: 0 0 0 1px var(--ai-border-4) inset; } &.is-focus { background: var(--ai-card-bg); box-shadow: 0 0 0 4px rgba(10,132,255,0.12), 0 0 0 1px $blue inset; } }
  :deep(.el-textarea__inner) { border-radius: $radius-sm; box-shadow: 0 0 0 1px var(--ai-border-3) inset; background: var(--ai-input-bg); transition: all 0.2s $ease; &:hover { box-shadow: 0 0 0 1px var(--ai-border-4) inset; } &:focus { background: var(--ai-card-bg); box-shadow: 0 0 0 4px rgba(10,132,255,0.12), 0 0 0 1px $blue inset; } }
  :deep(.el-switch.is-checked .el-switch__core) { background-color: $green; border-color: $green; }
}
.aform__section { animation: fadeUp 0.25s $ease; }
.aform__group { background: var(--ai-fill-1); border-radius: $radius; padding: 18px 20px; margin-bottom: 14px; &--full { padding: 18px 20px; margin-bottom: 0; } &--toggles { padding: 6px 20px; } }
.aform__group-label { font-size: 13px; font-weight: 600; color: $text; margin-bottom: 10px; }
.aform__group-desc { font-size: 13px; color: $gray; margin: -4px 0 14px; line-height: 1.5; }
.aform__row { display: flex; gap: 14px; @media (max-width: 600px) { flex-direction: column; gap: 0; } }
.aform__item { flex: 1; }

/* 外观取色器 */
.face {
  display: flex; align-items: center; gap: 20px;
  @media (max-width: 620px) { flex-direction: column; align-items: stretch; gap: 14px; }
  &__preview {
    flex-shrink: 0; width: 104px; display: flex; flex-direction: column; align-items: center; gap: 8px; text-align: center;
    @media (max-width: 620px) { width: auto; flex-direction: row; justify-content: flex-start; gap: 12px; }
  }
  &__avatar {
    width: 60px; height: 60px; border-radius: 18px; display: flex; align-items: center; justify-content: center;
    font-size: 30px; line-height: 1; position: relative; transition: background 0.25s $ease, box-shadow 0.25s $ease;
    &::after { content: ''; position: absolute; inset: 0; border-radius: 18px; border: 1px solid rgba(255,255,255,0.3); }
  }
  &__name {
    font-size: 12px; color: $text2; max-width: 100%;
    overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  }
  &__pickers { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 12px; }
  &__row { display: flex; align-items: flex-start; gap: 12px; }
  &__label { font-size: 12px; font-weight: 500; color: $text2; width: 40px; flex-shrink: 0; padding-top: 7px; }
  /* 固定 8 列，永远是整齐的两行，不会出现末行只剩一个 */
  &__icons {
    flex: 1; min-width: 0; display: grid; gap: 5px;
    grid-template-columns: repeat(8, 30px); justify-content: start;
    @media (max-width: 720px) { grid-template-columns: repeat(6, 30px); }
  }
  &__icon {
    width: 30px; height: 30px; border-radius: 9px; border: 1px solid transparent; background: var(--ai-hover);
    font-size: 16px; line-height: 1; cursor: pointer; transition: all 0.16s $ease;
    display: inline-flex; align-items: center; justify-content: center;
    &:hover { background: var(--ai-border-2); transform: translateY(-1px); }
    &.is-active { background: var(--ai-card-bg); border-color: $blue; box-shadow: 0 0 0 3px rgba(10,132,255,0.14); }
  }
  &__themes { flex: 1; min-width: 0; display: flex; flex-wrap: wrap; gap: 6px; }
  &__theme {
    width: 26px; height: 26px; border-radius: 50%; border: 2px solid transparent; cursor: pointer;
    padding: 0; transition: all 0.16s $ease; background: var(--ai-border);
    display: inline-flex; align-items: center; justify-content: center;
    &:hover { transform: scale(1.12); }
    &.is-active { border-color: $text; box-shadow: 0 0 0 2px var(--ai-card-bg) inset; }
  }
  &__auto { font-size: 10px; font-weight: 700; color: $text2; }
}

/* 详情里的拓扑图 */
.detail-topo { margin-bottom: 20px; }

/* 能力配置：技能 / 工具切换 */
.cap-switch {
  display: flex; align-items: center; gap: 8px; margin-bottom: 12px; flex-shrink: 0; flex-wrap: wrap;
  &__item {
    display: inline-flex; align-items: center; gap: 6px; padding: 6px 14px; border-radius: 980px;
    border: 1px solid var(--ai-hover-strong); background: var(--ai-card-bg); font-family: $font; font-size: 13px; font-weight: 500;
    color: $text2; cursor: pointer; transition: all 0.2s $ease;
    em {
      font-style: normal; font-size: 10.5px; font-weight: 700; min-width: 16px; height: 16px; padding: 0 4px;
      border-radius: 980px; background: var(--ai-fill-4); color: $text2;
      display: inline-flex; align-items: center; justify-content: center;
    }
    &:hover { border-color: rgba(10,132,255,0.4); color: $blue; }
    &.is-active {
      background: $blue; border-color: $blue; color: #fff; box-shadow: 0 2px 8px rgba(10,132,255,0.3);
      em { background: rgba(255,255,255,0.25); color: #fff; }
    }
  }
  &__hint { font-size: 11.5px; color: $gray; margin-left: auto; }
}

/* 模型绑定：必须主卡 + 选填三列 */
.model-bind {
  display: flex;
  flex-direction: column;
  gap: 12px;
  &__lead {
    margin: 0 2px 4px;
    font-size: $ai-fs-5;
    color: $ai-text3;
    line-height: $ai-lh-meta;
  }
  &__opt-head {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    gap: 12px;
    margin: 6px 2px 0;
  }
  &__opt-title { font-size: $ai-fs-5; font-weight: 600; color: $text; }
  &__opt-note { font-size: $ai-fs-6; color: $gray; }
  &__grid {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 12px;
    @media (max-width: 720px) { grid-template-columns: 1fr; }
  }
}
.model-card {
  position: relative;
  background: var(--ai-fill-1);
  border: 1px solid transparent;
  border-radius: $radius;
  padding: 16px 16px 14px;
  transition: border-color 0.2s $ease, background 0.2s $ease, box-shadow 0.2s $ease;
  &--hero {
    padding: 18px 20px 16px 22px;
    &::before {
      content: '';
      position: absolute;
      left: 0;
      top: 16px;
      bottom: 16px;
      width: 3px;
      border-radius: 3px;
      background: $blue;
    }
  }
  &.is-bound {
    background: var(--ai-card-bg);
    border-color: var(--ai-border-3);
    box-shadow: 0 1px 4px var(--ai-border);
  }
  &--hero.is-bound {
    border-color: rgba(10, 132, 255, 0.28);
    box-shadow: 0 0 0 3px rgba(10, 132, 255, 0.08);
  }
  &__head { display: flex; align-items: flex-start; gap: 10px; margin-bottom: 14px; }
  &__icon {
    width: 36px;
    height: 36px;
    border-radius: 10px;
    background: rgba(10, 132, 255, 0.1);
    color: $blue;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    flex-shrink: 0;
  }
  &--image .model-card__icon { background: rgba(191, 90, 242, 0.12); color: #BF5AF2; }
  &--video .model-card__icon { background: rgba(255, 159, 10, 0.14); color: $orange; }
  &--tts .model-card__icon { background: rgba(52, 199, 89, 0.14); color: $green; }
  &__titles { min-width: 0; flex: 1; }
  &__title-row { display: flex; align-items: center; gap: 7px; flex-wrap: wrap; }
  &__title { margin: 0; font-size: $ai-fs-5; font-weight: 600; color: $text; line-height: $ai-lh-tight; }
  &__hint { margin: 4px 0 0; font-size: $ai-fs-6; color: $gray; line-height: $ai-lh-meta; }
  &__field { margin-bottom: 0 !important; }
  &__empty { margin: 8px 0 0; font-size: $ai-fs-6; color: $gray; line-height: $ai-lh-meta; }
}
.model-tag {
  font-size: 10.5px;
  font-weight: 700;
  letter-spacing: 0.02em;
  padding: 1px 6px;
  border-radius: 980px;
  line-height: 16px;
  &--req { background: rgba(10, 132, 255, 0.12); color: $blue; }
  &--opt { background: var(--ai-fill-3); color: $text2; }
}

/* Select 内的选项 */
.model-option { display: flex; align-items: center; justify-content: space-between; width: 100%;
  &__name { font-weight: 500; } &__code { font-size: 11px; font-family: $mono; color: $gray; } }

/* Role Section */
.role-section {
  &__header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; flex-shrink: 0; }
  &__title { font-size: 13px; font-weight: 600; color: $text; }
  &__tools { display: flex; align-items: center; gap: 8px; }
  &__len { font-size: 11px; color: $gray; font-variant-numeric: tabular-nums; }
  &__btn {
    display: inline-flex; align-items: center; gap: 5px; border: none; background: rgba(10,132,255,0.09);
    color: $blue; font-size: 12px; font-weight: 500; font-family: $font; padding: 5px 12px;
    border-radius: 7px; cursor: pointer; transition: all 0.2s;
    &:hover { background: rgba(10,132,255,0.16); }
  }
}
.role-preview {
  flex: 1; min-height: 160px; overflow-y: auto; padding: 16px 18px;
  background: var(--ai-card-bg); border: 1px solid var(--ai-border-2); border-radius: $radius-sm;
  font-size: 14px; line-height: 1.8; color: $text;
  &--empty { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 14px; color: $gray; font-size: 13px; p { margin: 0; } }
}

/* MD Editor */
.md-editor {
  flex: 1; min-height: 260px; display: flex; border: 1px solid var(--ai-border-3); border-radius: $radius-sm; overflow: hidden; background: var(--ai-card-bg);
  &__pane { flex: 1; display: flex; flex-direction: column; min-width: 0; position: relative; &--preview { background: #FAFAFB; } }
  &__pane-tag {
    padding: 7px 14px; font-size: 10px; font-weight: 600; color: $gray; text-transform: uppercase;
    letter-spacing: 0.5px; border-bottom: 1px solid var(--ai-border); background: var(--ai-fill-1); flex-shrink: 0;
  }
  &__divider { width: 1px; background: var(--ai-fill-4); flex-shrink: 0; }
  &__textarea { flex: 1; width: 100%; min-height: 0; border: none; outline: none; resize: none; padding: 14px 16px; font-family: $mono; font-size: 13px; line-height: 1.8; color: $text; background: transparent; &::placeholder { color: $gray3; } }
  &__preview { flex: 1; min-height: 0; padding: 14px 16px; overflow-y: auto; font-size: 14px; line-height: 1.7; color: $text; }
  &__placeholder { position: absolute; top: 50%; left: 50%; transform: translate(-50%, -50%); color: $gray3; font-size: 13px; pointer-events: none; }
}

/* MD Body */
.md-body {
  :deep(h1) { font-size: 18px; font-weight: 700; margin: 0 0 10px; color: $text; }
  :deep(h2) { font-size: 16px; font-weight: 600; margin: 14px 0 8px; color: $text; }
  :deep(h3) { font-size: 14px; font-weight: 600; margin: 10px 0 6px; color: $text; }
  :deep(p) { margin: 0 0 8px; }
  :deep(ul), :deep(ol) { padding-left: 18px; margin: 0 0 8px; }
  :deep(li) { margin-bottom: 3px; }
  :deep(code) { font-family: $mono; font-size: 12px; background: var(--ai-border); padding: 2px 5px; border-radius: 4px; }
  :deep(pre) { background: #1d1d1f; color: #f5f5f7; padding: 12px 14px; border-radius: 8px; overflow-x: auto; margin: 0 0 10px; code { background: none; padding: 0; color: inherit; } }
  :deep(blockquote) { border-left: 3px solid $blue; padding-left: 12px; margin: 0 0 8px; color: $text2; }
  :deep(strong) { font-weight: 600; }
  :deep(a) { color: $blue; text-decoration: none; }
  :deep(hr) { border: none; border-top: 1px solid $gray5; margin: 14px 0; }
}

/* Toggle Row */
.toggle-row {
  display: flex; align-items: center; justify-content: space-between; padding: 13px 0; gap: 16px;
  & + & { border-top: 1px solid var(--ai-border); }
  &__info { display: flex; flex-direction: column; gap: 2px; }
  &__label { font-size: 14px; font-weight: 500; color: $text; }
  &__hint { font-size: 12px; color: $gray; }
}

/* Child Agent */
.child-list { display: flex; flex-direction: column; gap: 12px; }
.child-item {
  display: flex; align-items: flex-start; gap: 10px; padding: 14px; background: var(--ai-card-bg); border: 1px solid var(--ai-border-2); border-radius: $radius-sm;
  &__index { width: 24px; height: 24px; border-radius: 50%; background: rgba(10,132,255,0.1); color: $blue; font-size: 12px; font-weight: 600; display: flex; align-items: center; justify-content: center; flex-shrink: 0; margin-top: 4px; }
  &__fields { flex: 1; min-width: 0; }
  &__remove { width: 24px; height: 24px; border: none; border-radius: 50%; background: rgba(255,59,48,0.08); color: $red; font-size: 10px; cursor: pointer; display: flex; align-items: center; justify-content: center; flex-shrink: 0; margin-top: 4px; transition: all 0.2s; &:hover { background: rgba(255,59,48,0.16); } }
}
.child-empty { text-align: center; padding: 28px; color: $gray; font-size: 14px; &__icon { font-size: 28px; display: block; margin-bottom: 8px; } }

/* ==================== 详情 hero ==================== */
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
    font-size: 23px; font-weight: 700; background: rgba(255,255,255,0.24); backdrop-filter: blur(10px);
    border: 1px solid rgba(255,255,255,0.3);
  }
  &__text { min-width: 0; }
  &__name { font-size: 22px; font-weight: 700; margin: 0 0 6px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; text-shadow: 0 1px 3px var(--ai-border-4); }
  &__sub { display: flex; align-items: center; gap: 9px; flex-wrap: wrap; }
  &__code { font-family: $mono; font-size: 11px; background: rgba(255,255,255,0.2); padding: 2px 7px; border-radius: 5px; }
  &__public { font-size: 11px; font-weight: 600; background: rgba(255,255,255,0.22); padding: 2px 7px; border-radius: 5px; }
  &__status {
    display: inline-flex; align-items: center; gap: 5px; font-size: 12px;
    i { width: 6px; height: 6px; border-radius: 50%; background: var(--ai-card-bg); display: inline-block; &.is-off { opacity: 0.55; } }
  }
  &__stats {
    position: relative; z-index: 1; display: flex; gap: 26px; margin-top: 18px;
    padding: 12px 2px; border-top: 1px solid rgba(255,255,255,0.22);
  }
  &__stat {
    display: flex; align-items: baseline; gap: 5px;
    b { font-size: 18px; font-weight: 700; font-variant-numeric: tabular-nums; }
    span { font-size: 12px; opacity: 0.82; }
  }
}

/* ==================== 详情内容 ==================== */
.detail-desc { font-size: 14px; line-height: 1.6; color: $text2; margin: 0 0 18px; }

/* 主列放提示词，侧列放配置一览；窄屏堆叠 */
.detail-cols {
  display: grid; grid-template-columns: minmax(0, 1fr) 268px; gap: 24px; align-items: start;
  @media (max-width: 760px) { grid-template-columns: 1fr; gap: 20px; }
}
.detail-main { min-width: 0; }
.detail-side {
  min-width: 0; display: flex; flex-direction: column; gap: 14px;
  background: var(--ai-fill-1); border-radius: $radius; padding: 16px;
}
.detail-block {
  min-width: 0;
  & + & { padding-top: 14px; border-top: 1px solid var(--ai-fill-3); }
  &__title { display: flex; align-items: center; gap: 6px; font-size: 12px; font-weight: 600; color: $text; margin-bottom: 9px; }
  &__count { font-size: 10.5px; font-weight: 700; color: $gray; background: var(--ai-fill-3); padding: 1px 6px; border-radius: 980px; }
}
.detail-kv {
  margin: 0; display: flex; flex-direction: column; gap: 7px;
  &__row { display: flex; align-items: baseline; gap: 10px; min-width: 0; }
  dt { font-size: 12px; color: $gray; flex-shrink: 0; width: 58px; }
  dd {
    margin: 0; font-size: 12.5px; font-weight: 500; color: $text; min-width: 0;
    overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
    &.is-missing { color: #C24A00; }
  }
}
.detail-none { font-size: 12px; color: $gray3; }
.detail-prompt {
  padding: 16px 18px; background: var(--ai-block-bg); border: 1px solid var(--ai-border-2);
  border-radius: $radius-sm; font-size: 14px; line-height: 1.8; color: $text;
  max-height: 460px; overflow-y: auto;
}
.detail-hollow {
  display: flex; align-items: center; justify-content: center; min-height: 120px;
  border: 1px dashed var(--ai-border-4); border-radius: $radius-sm;
  background: var(--ai-fill-1); font-size: 13px; color: $gray3;
}
.chip-list { display: flex; flex-wrap: wrap; gap: 6px; }
.chip {
  display: inline-flex; align-items: center; gap: 5px; font-size: 12px; color: $text;
  border: 1px solid var(--ai-fill-4); border-radius: 980px; padding: 3px 9px; max-width: 100%;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  &__dot { width: 6px; height: 6px; border-radius: 50%; flex-shrink: 0; }
  &__code { font-family: $mono; font-size: 10px; font-style: normal; color: $gray; }
  &__tag { font-size: 9.5px; font-weight: 600; font-style: normal; padding: 1px 5px; border-radius: 4px; flex-shrink: 0;
    &.is-mcp { background: rgba(191,90,242,0.14); color: #8E3FBE; }
    &.is-builtin { background: rgba(48,209,88,0.16); color: #1E7A3C; } }
}
.detail-child-list { display: flex; flex-direction: column; gap: 8px; }
.detail-child {
  display: flex; align-items: flex-start; gap: 9px; padding: 9px 10px; background: var(--ai-card-bg);
  border: 1px solid var(--ai-border-2); border-radius: 9px;
  &__idx {
    width: 20px; height: 20px; border-radius: 7px; color: #fff; font-size: 11px; font-weight: 700;
    display: flex; align-items: center; justify-content: center; flex-shrink: 0; margin-top: 1px;
  }
  &__body { min-width: 0; }
  &__name { font-size: 12.5px; font-weight: 500; color: $text; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  &__trigger {
    font-size: 11.5px; color: $text2; line-height: 1.5; margin-top: 2px;
    display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
  }
}

/* Transitions */
.sheet-enter-active { transition: all 0.35s $spring; }
.sheet-leave-active { transition: all 0.2s ease-in; }
.sheet-enter-from { opacity: 0; .sheet { transform: scale(0.92) translateY(20px); opacity: 0; } }
.sheet-leave-to { opacity: 0; .sheet { transform: scale(0.96); opacity: 0; } }
@keyframes fadeUp { from { opacity: 0; transform: translateY(6px); } to { opacity: 1; transform: translateY(0); } }
</style>
