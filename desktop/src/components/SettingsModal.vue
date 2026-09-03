<script setup>
import { computed, reactive, ref, toRef, watch } from 'vue'
import { getUserProfile, updateUserProfile, updateUserPwd, uploadAvatar } from '../api/user'
import { getMyUsageStats } from '../api/stat'
import { useAuthStore } from '../stores/auth'
import { useThemeStore } from '../stores/theme'
import { confirmDanger, toast } from '../utils/confirm'
import { useDialogLifecycle } from '../composables/useDialogLifecycle'

const props = defineProps({
  visible: { type: Boolean, default: false },
  initialTab: { type: String, default: 'profile' }
})

const emit = defineEmits(['close'])

const auth = useAuthStore()
const theme = useThemeStore()

const currentTab = ref('profile')
const settingsRoot = ref(null)
useDialogLifecycle(toRef(props, 'visible'), () => emit('close'), settingsRoot)
watch(() => props.initialTab, (t) => { if (t) currentTab.value = t })
watch(() => props.visible, (v) => { if (v) initSettings() })
watch(currentTab, (tab) => { if (tab === 'usage') loadUsageStats() })

/* ==========================================================================
   1. 个人资料与头像修改 (Profile Tab)
   ========================================================================== */
const profileLoading = ref(false)
const savingProfile = ref(false)
const changingPwd = ref(false)
const avatarUploading = ref(false)
const avatarInputRef = ref(null)

const profileForm = reactive({
  userName: '',
  nickName: '',
  phonenumber: '',
  email: '',
  roleGroup: '',
  avatar: ''
})

const pwdForm = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: ''
})

/* ==========================================================================
   2. 个人 Token 用量统计 (Usage Tab)
   ========================================================================== */
const usageDays = ref(7)
const usageLoading = ref(false)
const usageError = ref('')
const usageStats = ref(null)

function numberOf(value) {
  const n = Number(value)
  return Number.isFinite(n) ? n : 0
}

function formatTokens(value) {
  const n = numberOf(value)
  if (n >= 1000000) return `${(n / 1000000).toFixed(n >= 10000000 ? 0 : 1)}M`
  if (n >= 1000) return `${(n / 1000).toFixed(n >= 10000 ? 1 : 2)}K`
  return n.toLocaleString('zh-CN')
}

const usageSummary = computed(() => usageStats.value?.summary || {})
const usageToday = computed(() => usageStats.value?.today || {})
const usageCache = computed(() => usageStats.value?.cache || {})
const usageCacheRate = computed(() => Math.max(0, Math.min(100, numberOf(usageCache.value.hitRate))))
const usageTrend = computed(() => {
  const days = Math.max(1, numberOf(usageStats.value?.days) || usageDays.value)
  const rows = new Map((usageStats.value?.trend || []).map(row => [row.day, row]))
  const result = []
  for (let index = days - 1; index >= 0; index--) {
    const date = new Date()
    date.setHours(0, 0, 0, 0)
    date.setDate(date.getDate() - index)
    const day = `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
    const row = rows.get(day) || {}
    result.push({
      day,
      label: `${date.getMonth() + 1}/${date.getDate()}`,
      showLabel: days <= 7 || index === 0 || index === days - 1 || index % 5 === 0,
      totalTokens: numberOf(row.totalTokens),
      promptTokens: numberOf(row.promptTokens),
      completionTokens: numberOf(row.completionTokens),
      callCount: numberOf(row.callCount)
    })
  }
  return result
})
const usageTrendMax = computed(() => Math.max(1, ...usageTrend.value.map(item => item.totalTokens)))

async function loadUsageStats() {
  if (usageLoading.value) return
  usageLoading.value = true
  usageError.value = ''
  try {
    const res = await getMyUsageStats(usageDays.value)
    usageStats.value = res.data || {}
  } catch (e) {
    usageError.value = e.message || '加载用量统计失败'
  } finally {
    usageLoading.value = false
  }
}

function setUsageDays(days) {
  if (usageDays.value === days) return
  usageDays.value = days
  loadUsageStats()
}

const avatarBase = import.meta.env.VITE_APP_BASE_API || '/dev-api'
const displayAvatar = computed(() => {
  const raw = profileForm.avatar || auth.user?.avatar || ''
  if (!raw) return ''
  return /^https?:\/\//.test(raw) || raw.startsWith('data:') ? raw : avatarBase + raw
})

async function loadProfile() {
  profileLoading.value = true
  try {
    const res = await getUserProfile()
    const u = res.data || {}
    profileForm.userName = u.userName || ''
    profileForm.nickName = u.nickName || ''
    profileForm.phonenumber = u.phonenumber || ''
    profileForm.email = u.email || ''
    profileForm.avatar = u.avatar || ''
    profileForm.roleGroup = u.roleGroup || res.roleGroup || '普通用户'
    if (auth.user) {
      auth.user.nickName = u.nickName
      auth.user.avatar = u.avatar
    }
  } catch (e) {
    toast(e.message || '获取个人资料失败')
  } finally {
    profileLoading.value = false
  }
}

async function onAvatarPick(e) {
  const file = e.target.files?.[0]
  e.target.value = ''
  if (!file) return
  if (!file.type.startsWith('image/')) {
    toast('请选择图片文件')
    return
  }
  avatarUploading.value = true
  try {
    const res = await uploadAvatar(file)
    const newAvatar = res.imgUrl || res.data?.imgUrl
    if (newAvatar) {
      profileForm.avatar = newAvatar
      if (auth.user) auth.user.avatar = newAvatar
      toast('头像更新成功')
      await auth.fetchUser()
    }
  } catch (err) {
    toast(err.message || '头像上传失败')
  } finally {
    avatarUploading.value = false
  }
}

async function saveProfile() {
  if (!profileForm.nickName.trim()) {
    toast('用户昵称不能为空')
    return
  }
  savingProfile.value = true
  try {
    await updateUserProfile({
      nickName: profileForm.nickName.trim(),
      phonenumber: profileForm.phonenumber.trim(),
      email: profileForm.email.trim()
    })
    toast('资料保存成功')
    await auth.fetchUser()
  } catch (e) {
    toast(e.message || '保存失败')
  } finally {
    savingProfile.value = false
  }
}

async function savePassword() {
  if (!pwdForm.oldPassword || !pwdForm.newPassword) {
    toast('请完整填写新旧密码')
    return
  }
  if (pwdForm.newPassword !== pwdForm.confirmPassword) {
    toast('两次输入的新密码不一致')
    return
  }
  if (pwdForm.newPassword.length < 5) {
    toast('新密码长度不能少于 5 位')
    return
  }
  changingPwd.value = true
  try {
    await updateUserPwd(pwdForm.oldPassword, pwdForm.newPassword)
    toast('密码修改成功，请牢记新密码')
    pwdForm.oldPassword = ''
    pwdForm.newPassword = ''
    pwdForm.confirmPassword = ''
  } catch (e) {
    toast(e.message || '密码修改失败')
  } finally {
    changingPwd.value = false
  }
}

function initSettings() {
  // 密码绝不应从本地状态或浏览器自动填充到个人资料页。
  pwdForm.oldPassword = ''
  pwdForm.newPassword = ''
  pwdForm.confirmPassword = ''
  loadProfile()
  if (currentTab.value === 'usage') loadUsageStats()
}
</script>

<template>
  <Teleport to="body">
  <transition name="app-modal">
    <div v-if="visible" ref="settingsRoot" class="settings-mask" @click.self="emit('close')">
      <div class="settings-dialog" data-dialog-surface>
        <!-- 侧边导航栏 -->
        <aside class="settings-sidebar">
          <div class="settings-sidebar__title">偏好设置</div>
          <nav class="settings-nav">
            <button
              type="button"
              class="settings-nav__item"
              :class="{ active: currentTab === 'profile' }"
              @click="currentTab = 'profile'"
            >
              <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>
              <span>个人资料</span>
            </button>

            <button
              type="button"
              class="settings-nav__item"
              :class="{ active: currentTab === 'appearance' }"
              @click="currentTab = 'appearance'"
            >
              <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="5"/><path d="M12 1v2M12 21v2M4.22 4.22l1.42 1.42M18.36 18.36l1.42 1.42M1 12h2M21 12h2M4.22 19.78l1.42-1.42M18.36 5.64l1.42-1.42"/></svg>
              <span>外观与主题</span>
            </button>

            <button
              type="button"
              class="settings-nav__item"
              :class="{ active: currentTab === 'usage' }"
              @click="currentTab = 'usage'"
            >
              <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M4 19V5"/><path d="M4 19h16"/><path d="M8 15l3-3 3 2 5-6"/></svg>
              <span>用量统计</span>
            </button>

            <button
              type="button"
              class="settings-nav__item"
              :class="{ active: currentTab === 'system' }"
              @click="currentTab = 'system'"
            >
              <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><line x1="12" y1="16" x2="12" y2="12"/><line x1="12" y1="8" x2="12.01" y2="8"/></svg>
              <span>关于软件</span>
            </button>
          </nav>
        </aside>

        <!-- 主内容视窗 -->
        <main class="settings-content">
          <!-- 头部关闭按钮 -->
          <div class="settings-content__head">
            <span class="settings-content__title">
              {{ currentTab === 'profile' ? '个人资料与账户安全' : currentTab === 'appearance' ? '界面外观与主题' : currentTab === 'usage' ? '个人用量统计' : '关于 AgentHub Desktop' }}
            </span>
            <button type="button" class="settings-close" title="关闭 (Esc)" @click="emit('close')">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
            </button>
          </div>

          <!-- 1. 个人资料 Tab -->
          <section v-if="currentTab === 'profile'" class="tab-pane">
            <div class="profile-header">
              <!-- 头像上传区域 -->
              <div class="avatar-uploader" title="点击更换头像" @click="avatarInputRef?.click()">
                <img v-if="displayAvatar" :src="displayAvatar" alt="头像" class="avatar-uploader__img" />
                <span v-else class="avatar-uploader__fallback">{{ (profileForm.nickName || profileForm.userName || '我').slice(0, 1).toUpperCase() }}</span>
                <div class="avatar-uploader__mask">
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z"/><circle cx="12" cy="13" r="4"/></svg>
                  <span>更换</span>
                </div>
                <input ref="avatarInputRef" type="file" accept="image/*" style="display: none" @change="onAvatarPick" />
              </div>

              <div class="profile-header__info">
                <div class="profile-header__name">{{ profileForm.nickName || '未命名用户' }}</div>
                <div class="profile-header__meta">账号: @{{ profileForm.userName }} · 角色: {{ profileForm.roleGroup }}</div>
              </div>
            </div>

            <!-- 基本资料表单 -->
            <div class="settings-card">
              <div class="settings-card__title">基础信息</div>
              <div class="form-grid">
                <div class="form-item">
                  <label class="form-label">用户账号</label>
                  <input :value="profileForm.userName" disabled class="form-input is-disabled" />
                </div>
                <div class="form-item">
                  <label class="form-label">用户昵称</label>
                  <input v-model="profileForm.nickName" class="form-input" placeholder="输入您的昵称" />
                </div>
                <div class="form-item">
                  <label class="form-label">手机号码</label>
                  <input v-model="profileForm.phonenumber" class="form-input" placeholder="绑定手机号" />
                </div>
                <div class="form-item">
                  <label class="form-label">电子邮箱</label>
                  <input v-model="profileForm.email" class="form-input" placeholder="绑定邮箱" />
                </div>
              </div>
              <div class="form-actions">
                <button type="button" class="btn-primary" :disabled="savingProfile" @click="saveProfile">
                  {{ savingProfile ? '保存中…' : '保存个人资料' }}
                </button>
              </div>
            </div>

            <!-- 修改密码 -->
            <div class="settings-card">
              <div class="settings-card__title">安全密码修改</div>
              <div class="form-grid">
                <div class="form-item">
                  <label class="form-label">原密码</label>
                  <input v-model="pwdForm.oldPassword" type="password" name="settings-current-password" autocomplete="new-password" class="form-input" placeholder="输入当前旧密码" />
                </div>
                <div class="form-item">
                  <label class="form-label">新密码</label>
                  <input v-model="pwdForm.newPassword" type="password" name="settings-new-password" autocomplete="new-password" class="form-input" placeholder="输入至少 5 位新密码" />
                </div>
                <div class="form-item">
                  <label class="form-label">确认新密码</label>
                  <input v-model="pwdForm.confirmPassword" type="password" name="settings-confirm-password" autocomplete="new-password" class="form-input" placeholder="重复输入新密码" />
                </div>
              </div>
              <div class="form-actions">
                <button type="button" class="btn-secondary" :disabled="changingPwd" @click="savePassword">
                  {{ changingPwd ? '提交中…' : '更新密码' }}
                </button>
              </div>
            </div>
          </section>
          <section v-else-if="currentTab === 'appearance'" class="tab-pane">
            <div class="settings-card">
              <div class="settings-card__title">主题模式</div>
              <div class="theme-cards">
                <div
                  class="theme-card"
                  :class="{ active: theme.mode === 'light' }"
                  @click="theme.setMode('light')"
                >
                  <div class="theme-card__preview theme-card__preview--light">
                    <div class="theme-mock-bar"></div>
                    <div class="theme-mock-content"></div>
                  </div>
                  <div class="theme-card__label">
                    <span>☀️ 明亮浅色</span>
                    <span v-if="theme.mode === 'light'" class="theme-check">✓</span>
                  </div>
                </div>

                <div
                  class="theme-card"
                  :class="{ active: theme.mode === 'dark' }"
                  @click="theme.setMode('dark')"
                >
                  <div class="theme-card__preview theme-card__preview--dark">
                    <div class="theme-mock-bar"></div>
                    <div class="theme-mock-content"></div>
                  </div>
                  <div class="theme-card__label">
                    <span>🌙 黑曜暗色</span>
                    <span v-if="theme.mode === 'dark'" class="theme-check">✓</span>
                  </div>
                </div>

                <div
                  class="theme-card"
                  :class="{ active: theme.mode === 'system' }"
                  @click="theme.setMode('system')"
                >
                  <div class="theme-card__preview theme-card__preview--system">
                    <div class="theme-mock-bar"></div>
                    <div class="theme-mock-content"></div>
                  </div>
                  <div class="theme-card__label">
                    <span>💻 跟随系统</span>
                    <span v-if="theme.mode === 'system'" class="theme-check">✓</span>
                  </div>
                </div>
              </div>
            </div>
          </section>

          <!-- 4. 个人用量统计 Tab -->
          <section v-else-if="currentTab === 'usage'" class="tab-pane usage-pane">
            <div class="usage-pane__head">
              <div>
                <div class="usage-pane__headline">Token 消耗</div>
                <p>仅统计当前账号发起的会话与模型调用。</p>
              </div>
              <div class="usage-period" aria-label="统计周期">
                <button type="button" :class="{ active: usageDays === 7 }" @click="setUsageDays(7)">近 7 天</button>
                <button type="button" :class="{ active: usageDays === 30 }" @click="setUsageDays(30)">近 30 天</button>
              </div>
            </div>

            <div v-if="usageLoading && !usageStats" class="usage-empty">正在汇总用量数据…</div>
            <div v-else-if="usageError" class="usage-empty usage-empty--err">
              <span>{{ usageError }}</span>
              <button type="button" class="btn-secondary btn-sm" @click="loadUsageStats">重试</button>
            </div>
            <template v-else>
              <div class="usage-overview-grid">
                <article class="usage-metric usage-metric--primary">
                  <span>今日消耗</span>
                  <strong>{{ formatTokens(usageToday.totalTokens) }}</strong>
                  <small>{{ numberOf(usageToday.callCount) }} 次模型调用</small>
                </article>
                <article class="usage-metric">
                  <span>{{ usageDays }} 日总消耗</span>
                  <strong>{{ formatTokens(usageSummary.totalTokens) }}</strong>
                  <small>{{ numberOf(usageSummary.sessionCount) }} 个会话</small>
                </article>
                <article class="usage-metric">
                  <span>输入 Token</span>
                  <strong>{{ formatTokens(usageSummary.promptTokens) }}</strong>
                  <small>占总消耗 {{ usageSummary.totalTokens ? Math.round(numberOf(usageSummary.promptTokens) * 100 / numberOf(usageSummary.totalTokens)) : 0 }}%</small>
                </article>
                <article class="usage-metric">
                  <span>输出 Token</span>
                  <strong>{{ formatTokens(usageSummary.completionTokens) }}</strong>
                  <small>共 {{ numberOf(usageSummary.callCount) }} 次调用</small>
                </article>
              </div>

              <div class="usage-detail-grid">
                <section class="settings-card usage-chart-card">
                  <div class="usage-card-head">
                    <div>
                      <div class="settings-card__title">每日 Token 消耗</div>
                      <p>输入与输出 Token 合计</p>
                    </div>
                    <span>{{ formatTokens(usageSummary.totalTokens) }}</span>
                  </div>
                  <div v-if="usageTrend.some(item => item.totalTokens > 0)" class="usage-chart" role="img" :aria-label="`近 ${usageDays} 天 Token 消耗趋势`">
                    <div v-for="item in usageTrend" :key="item.day" class="usage-chart__item" :title="`${item.day}：${formatTokens(item.totalTokens)} Token，${item.callCount} 次调用`">
                      <div class="usage-chart__bar-wrap">
                        <i class="usage-chart__bar" :style="{ height: Math.max(item.totalTokens ? 8 : 0, item.totalTokens * 100 / usageTrendMax) + '%' }"></i>
                      </div>
                      <span :class="{ 'is-hidden': !item.showLabel }">{{ item.label }}</span>
                    </div>
                  </div>
                  <div v-else class="usage-chart-empty">所选周期内暂无模型调用</div>
                </section>

                <section class="settings-card usage-cache-card">
                  <div class="usage-card-head">
                    <div>
                      <div class="settings-card__title">Token 命中率</div>
                      <p>缓存命中仅基于模型返回的真实用量</p>
                    </div>
                  </div>
                  <div class="usage-cache-body">
                    <div class="usage-cache-ring" :style="{ '--usage-rate': usageCacheRate + '%' }">
                      <strong>{{ usageCacheRate.toFixed(1) }}%</strong>
                      <span>缓存命中</span>
                    </div>
                    <div class="usage-cache-legend">
                      <div><i class="is-hit"></i><span>命中 Token</span><strong>{{ formatTokens(usageCache.hitTokens) }}</strong></div>
                      <div><i class="is-miss"></i><span>未命中 Token</span><strong>{{ formatTokens(usageCache.missTokens) }}</strong></div>
                    </div>
                  </div>
                </section>
              </div>
            </template>
          </section>

          <!-- 5. 关于软件 Tab -->
          <section v-else-if="currentTab === 'system'" class="tab-pane">
            <div class="settings-card">
              <div class="settings-card__title">关于 AgentHub Desktop</div>
              <div class="sys-item">
                <span class="sys-label">客户端版本</span>
                <span class="sys-val">v2.5.0 Pro Native</span>
              </div>
              <div class="sys-item">
                <span class="sys-label">核心内核架构</span>
                <span class="sys-val">Vue 3 · Vite · ReAct Engine</span>
              </div>
              <div class="sys-item">
                <span class="sys-label">知识向量引擎</span>
                <span class="sys-val">Hybrid RAG · Milvus / IR Parser</span>
              </div>
            </div>
          </section>
        </main>
      </div>
    </div>
  </transition>
  </Teleport>
</template>

<style scoped lang="scss">
.settings-mask {
  position: fixed;
  inset: 0;
  z-index: 1300;
  background: var(--ai-overlay);
  backdrop-filter: blur(8px);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
}

.settings-dialog {
  width: 100%;
  max-width: 900px;
  height: 620px;
  background: var(--bg-elevated);
  border: 1px solid var(--border-strong);
  border-radius: 16px;
  box-shadow: var(--shadow);
  display: flex;
  overflow: hidden;
}

.settings-sidebar {
  width: 190px;
  background: var(--bg-raised);
  border-right: 1px solid var(--divider);
  display: flex;
  flex-direction: column;
  padding: 16px 10px;
  flex-shrink: 0;

  &__title {
    font-size: 14px;
    font-weight: 700;
    color: var(--text);
    padding: 0 8px 14px;
    letter-spacing: -0.01em;
  }
}

.settings-nav {
  display: flex;
  flex-direction: column;
  gap: 2px;

  &__item {
    width: 100%;
    height: 34px;
    border-radius: 8px;
    border: none;
    background: transparent;
    color: var(--text-secondary);
    font-size: 13px;
    font-weight: 500;
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 0 10px;
    cursor: pointer;
    transition: all 0.14s ease;

    &:hover {
      background: var(--bg-hover);
      color: var(--text);
    }

    &.active {
      background: var(--ai-fill-3);
      color: var(--accent);
      font-weight: 600;
    }
  }
}

.settings-content {
  flex: 1;
  min-width: 0;
  background: var(--bg);
  display: flex;
  flex-direction: column;
  overflow: hidden;

  &__head {
    height: 52px;
    padding: 0 20px;
    border-bottom: 1px solid var(--divider);
    display: flex;
    align-items: center;
    justify-content: space-between;
    flex-shrink: 0;
  }

  &__title {
    font-size: 14px;
    font-weight: 600;
    color: var(--text);
  }
}

.settings-close {
  width: 28px;
  height: 28px;
  border-radius: 6px;
  border: 1px solid var(--border);
  background: var(--ai-fill-1);
  color: var(--text-secondary);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.14s ease;

  &:hover {
    background: var(--bg-hover);
    color: var(--text);
  }
}

.tab-pane {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* 个人资料区 */
.profile-header {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 16px;
  background: var(--bg-raised);
  border: 1px solid var(--border);
  border-radius: 12px;
}

.avatar-uploader {
  position: relative;
  width: 64px;
  height: 64px;
  border-radius: 50%;
  overflow: hidden;
  cursor: pointer;
  background: var(--accent-gradient);
  color: #fff;
  flex-shrink: 0;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.15);

  &__img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }

  &__fallback {
    width: 100%;
    height: 100%;
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 26px;
    font-weight: 700;
  }

  &__mask {
    position: absolute;
    inset: 0;
    background: rgba(0, 0, 0, 0.55);
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 2px;
    font-size: 10px;
    color: #fff;
    opacity: 0;
    transition: opacity 0.14s ease;
  }

  &:hover &__mask {
    opacity: 1;
  }
}

.profile-header__info {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.profile-header__name {
  font-size: 16px;
  font-weight: 700;
  color: var(--text);
}

.profile-header__meta {
  font-size: 12px;
  color: var(--text-secondary);
}

/* 卡片与表单 */
.settings-card {
  padding: 16px;
  background: var(--bg-raised);
  border: 1px solid var(--border);
  border-radius: 12px;
  display: flex;
  flex-direction: column;
  gap: 12px;

  &__title {
    font-size: 13px;
    font-weight: 600;
    color: var(--text);
  }
}

.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.form-item {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.form-label {
  font-size: 12px;
  font-weight: 500;
  color: var(--text-secondary);
}

.form-input, .form-textarea {
  width: 100%;
  height: 34px;
  padding: 0 10px;
  background: var(--bg-input);
  border: 1px solid var(--border);
  border-radius: 8px;
  color: var(--text);
  font-size: 13px;
  outline: none;
  font-family: inherit;
  transition: border-color 0.14s ease;

  &:focus {
    border-color: var(--accent);
  }

  &.is-disabled {
    opacity: 0.6;
    cursor: not-allowed;
  }
}

.form-textarea {
  height: auto;
  padding: 8px 10px;
  resize: vertical;
}

.form-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 4px;
}

.btn-primary {
  height: 32px;
  padding: 0 14px;
  border-radius: 8px;
  background: var(--accent-gradient);
  color: #fff;
  border: none;
  font-size: 12.5px;
  font-weight: 600;
  cursor: pointer;
  transition: filter 0.14s ease;
  display: inline-flex;
  align-items: center;
  gap: 6px;

  &:hover:not(:disabled) {
    filter: brightness(1.08);
  }

  &:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }

  &.btn-sm {
    height: 28px;
    font-size: 12px;
    padding: 0 10px;
  }
}

.btn-secondary {
  height: 32px;
  padding: 0 14px;
  border-radius: 8px;
  background: var(--ai-fill-2);
  color: var(--text);
  border: 1px solid var(--border);
  font-size: 12.5px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.14s ease;
  display: inline-flex;
  align-items: center;
  gap: 6px;

  &:hover:not(:disabled) {
    background: var(--bg-hover);
    border-color: var(--border-strong);
  }

  &.btn-sm {
    height: 28px;
    font-size: 12px;
    padding: 0 10px;
  }
}

/* 个人用量统计 */
.usage-pane {
  gap: 14px;
}

.usage-pane__head,
.usage-card-head,
.usage-cache-body,
.usage-cache-legend > div {
  display: flex;
  align-items: center;
}

.usage-pane__head,
.usage-card-head {
  justify-content: space-between;
  gap: 14px;
}

.usage-pane__headline {
  color: var(--text);
  font-size: 16px;
  font-weight: 700;
}

.usage-pane__head p,
.usage-card-head p {
  margin: 3px 0 0;
  color: var(--text-tertiary);
  font-size: 12px;
}

.usage-period {
  display: inline-flex;
  flex-shrink: 0;
  padding: 3px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--ai-fill-1);

  button {
    height: 26px;
    padding: 0 9px;
    border: 0;
    border-radius: 5px;
    background: transparent;
    color: var(--text-tertiary);
    font-family: inherit;
    font-size: 11.5px;
    cursor: pointer;

    &.active {
      background: var(--bg-raised);
      color: var(--text);
      box-shadow: 0 1px 3px rgba(0, 0, 0, 0.1);
    }
  }
}

.usage-empty,
.usage-chart-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  min-height: 120px;
  border: 1px dashed var(--border);
  border-radius: 10px;
  color: var(--text-tertiary);
  font-size: 12.5px;
}

.usage-empty--err {
  color: var(--danger-text, #dc2626);
}

.usage-overview-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}

.usage-metric {
  min-width: 0;
  padding: 14px;
  border: 1px solid var(--border);
  border-radius: 11px;
  background: var(--bg-raised);

  span,
  small {
    display: block;
    overflow: hidden;
    color: var(--text-tertiary);
    font-size: 11.5px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  strong {
    display: block;
    margin: 7px 0 4px;
    overflow: hidden;
    color: var(--text);
    font-size: 21px;
    font-variant-numeric: tabular-nums;
    letter-spacing: -0.04em;
    text-overflow: ellipsis;
  }

  &--primary {
    border-color: color-mix(in srgb, var(--accent) 25%, var(--border));
    background: color-mix(in srgb, var(--accent) 7%, var(--bg-raised));
  }
}

.usage-detail-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.45fr) minmax(250px, 0.9fr);
  gap: 12px;
}

.usage-card-head > span {
  color: var(--text-secondary);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.usage-chart {
  display: flex;
  align-items: flex-end;
  height: 168px;
  gap: 7px;
  padding-top: 8px;
}

.usage-chart__item {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  align-items: center;
  gap: 7px;
  color: var(--text-tertiary);
  font-size: 10px;

  .is-hidden { visibility: hidden; }
}

.usage-chart__bar-wrap {
  display: flex;
  width: 100%;
  height: 138px;
  align-items: flex-end;
  border-radius: 5px 5px 3px 3px;
  background: var(--ai-fill-1);
  overflow: hidden;
}

.usage-chart__bar {
  width: 100%;
  min-height: 0;
  border-radius: inherit;
  background: var(--accent-gradient);
  transition: height 0.22s ease;
}

.usage-cache-card {
  justify-content: space-between;
}

.usage-cache-body {
  justify-content: center;
  gap: 18px;
  min-height: 156px;
}

.usage-cache-ring {
  --usage-rate: 0%;
  display: flex;
  width: 112px;
  height: 112px;
  flex: 0 0 112px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: conic-gradient(var(--accent) var(--usage-rate), var(--ai-fill-2) 0);
  position: relative;

  &::before {
    position: absolute;
    inset: 10px;
    border-radius: 50%;
    background: var(--bg-raised);
    content: '';
  }

  strong,
  span {
    position: relative;
    z-index: 1;
  }

  strong {
    color: var(--text);
    font-size: 18px;
    font-variant-numeric: tabular-nums;
    letter-spacing: -0.04em;
  }

  span {
    margin-top: 2px;
    color: var(--text-tertiary);
    font-size: 10.5px;
  }
}

.usage-cache-legend {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 12px;

  > div {
    gap: 7px;
    min-width: 0;
    color: var(--text-secondary);
    font-size: 11.5px;

    i {
      width: 7px;
      height: 7px;
      flex: 0 0 7px;
      border-radius: 50%;
      background: var(--accent);

      &.is-miss { background: var(--text-tertiary); }
    }

    span {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    strong {
      margin-left: auto;
      color: var(--text);
      font-size: 12px;
      font-variant-numeric: tabular-nums;
    }
  }
}

@media (max-width: 820px) {
  .usage-overview-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .usage-detail-grid { grid-template-columns: 1fr; }
}


/* 主题预览卡片 */
.theme-cards {
  display: grid;
  grid-template-columns: 1fr 1fr 1fr;
  gap: 12px;
}

.theme-card {
  border: 2px solid var(--border);
  border-radius: 12px;
  padding: 10px;
  cursor: pointer;
  transition: all 0.14s ease;

  &__preview {
    height: 70px;
    border-radius: 8px;
    border: 1px solid var(--border);
    margin-bottom: 8px;
    overflow: hidden;
    padding: 6px;
    display: flex;
    flex-direction: column;
    gap: 4px;

    &--light {
      background: #f8fafc;
      .theme-mock-bar { height: 10px; background: #e2e8f0; border-radius: 3px; }
      .theme-mock-content { flex: 1; background: #ffffff; border-radius: 3px; }
    }

    &--dark {
      background: #0f172a;
      .theme-mock-bar { height: 10px; background: #1e293b; border-radius: 3px; }
      .theme-mock-content { flex: 1; background: #141824; border-radius: 3px; }
    }

    &--system {
      background: linear-gradient(135deg, #f8fafc 50%, #0f172a 50%);
      .theme-mock-bar { height: 10px; background: rgba(148, 163, 184, 0.4); border-radius: 3px; }
      .theme-mock-content { flex: 1; background: rgba(148, 163, 184, 0.2); border-radius: 3px; }
    }
  }

  &__label {
    display: flex;
    align-items: center;
    justify-content: space-between;
    font-size: 12.5px;
    font-weight: 600;
    color: var(--text);
  }

  .theme-check {
    color: var(--accent);
    font-weight: 700;
  }

  &.active {
    border-color: var(--accent);
    background: var(--ai-fill-2);
  }
}

/* 系统状态 */
.sys-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 0;
  font-size: 12.5px;
}

.sys-label {
  color: var(--text-secondary);
}

.sys-val {
  color: var(--text);
  font-family: ui-monospace, monospace;
}

@keyframes settings-pop {
  from { opacity: 0; transform: scale(0.96); }
  to { opacity: 1; transform: scale(1); }
}

.preview-slide-enter-active, .preview-slide-leave-active {
  transition: transform 0.2s cubic-bezier(0.16, 1, 0.3, 1);
}
.preview-slide-enter-from, .preview-slide-leave-to {
  transform: translateX(100%);
}

.modal-fade-enter-active, .modal-fade-leave-active {
  transition: opacity 0.16s ease;
}
.modal-fade-enter-from, .modal-fade-leave-to {
  opacity: 0;
}
</style>
