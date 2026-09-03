<script setup>
import { onMounted, onBeforeUnmount, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getCodeImg } from '../api/auth'
import AuthHeroBackdrop from '../components/AuthHeroBackdrop.vue'
import logoMark from '../assets/agenthub-logo-mark.svg'
import { useAuthStore } from '../stores/auth'
import { useThemeStore } from '../stores/theme'
import { toast } from '../utils/confirm'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const theme = useThemeStore()

const form = reactive({
  username: '',
  password: '',
  code: '',
  uuid: ''
})

const captchaEnabled = ref(true)
const codeUrl = ref('')
const loading = ref(false)
const oauthLoading = ref(false)
const error = ref('')
const showPassword = ref(false)
const focusedField = ref('')

async function loadCaptcha() {
  try {
    const res = await getCodeImg()
    captchaEnabled.value = res.captchaEnabled === undefined ? true : !!res.captchaEnabled
    if (captchaEnabled.value) {
      codeUrl.value = 'data:image/gif;base64,' + res.img
      form.uuid = res.uuid
    }
  } catch (_) {
    captchaEnabled.value = false
  }
}

async function submit() {
  error.value = ''
  if (!form.username.trim() || !form.password) {
    error.value = '请填写账号和密码'
    return
  }
  if (captchaEnabled.value && !form.code.trim()) {
    error.value = '请填写验证码计算结果'
    return
  }
  loading.value = true
  try {
    await auth.login(form)
    router.replace(route.query.redirect || '/')
  } catch (e) {
    error.value = e.message || '账号或密码不正确'
    if (captchaEnabled.value) loadCaptcha()
  } finally {
    loading.value = false
  }
}

async function handleOauth() {
  oauthLoading.value = true
  try {
    if (!auth.oauthAuthorizationUrl) {
      await auth.initOauth()
    }
    if (auth.oauthAuthorizationUrl) {
      await chrome.tabs.create({ url: auth.oauthAuthorizationUrl })
    } else {
      toast('GitHub 授权服务暂未开通或正在连接')
      oauthLoading.value = false
    }
  } catch (e) {
    toast('连接 GitHub 授权登录失败：' + (e.message || '网络异常'))
    oauthLoading.value = false
  }
}

async function consumeOauthTicket(ticket) {
  if (!ticket) return false
  oauthLoading.value = true
  try {
    await auth.loginByOauth(String(ticket))
    await chrome.storage.local.remove(['oauthTicket', 'oauthError', 'oauthAt'])
    router.replace(route.query.redirect || '/')
    return true
  } catch (e) {
    error.value = e.message || '单点登录未完成'
    oauthLoading.value = false
    try { await chrome.storage.local.remove(['oauthTicket', 'oauthError', 'oauthAt']) } catch (_) {}
    return false
  }
}

function onStorage(changes, area) {
  if (area !== 'local' || !changes.oauthTicket) return
  const ticket = changes.oauthTicket.newValue
  if (ticket) consumeOauthTicket(ticket)
}

onMounted(async () => {
  theme.initTheme()
  await auth.initOauth().catch(() => {})
  chrome.storage?.onChanged?.addListener(onStorage)

  const bag = await chrome.storage.local.get(['oauthTicket', 'oauthError'])
  if (bag.oauthTicket) {
    await consumeOauthTicket(bag.oauthTicket)
    return
  }
  if (bag.oauthError) {
    toast('单点登录未完成：账号未开通或授权失败')
  }
  loadCaptcha()
})

onBeforeUnmount(() => {
  chrome.storage?.onChanged?.removeListener(onStorage)
})
</script>

<template>
  <div class="auth-layout">
    <!-- 背景星云粒子画布 -->
    <AuthHeroBackdrop />

    <!-- 登录主面板 -->
    <div class="auth-panel">
      <!-- 顶部状态栏: Logo + 主题切换器 -->
      <header class="auth-panel__header">
        <div class="auth-panel__brand">
          <img :src="logoMark" alt="AgentHub" class="auth-panel__logo" />
          <span class="auth-panel__name">AgentHub</span>
        </div>

        <div class="theme-toggle">
          <button
            type="button"
            class="theme-toggle__btn"
            :class="{ active: theme.mode === 'light' }"
            title="浅色模式"
            @click="theme.setMode('light')"
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <circle cx="12" cy="12" r="5"/><path d="M12 1v2M12 21v2M4.22 4.22l1.42 1.42M18.36 18.36l1.42 1.42M1 12h2M21 12h2M4.22 19.78l1.42-1.42M18.36 5.64l1.42-1.42"/>
            </svg>
          </button>
          <button
            type="button"
            class="theme-toggle__btn"
            :class="{ active: theme.mode === 'dark' }"
            title="深色模式"
            @click="theme.setMode('dark')"
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"/>
            </svg>
          </button>
          <button
            type="button"
            class="theme-toggle__btn"
            :class="{ active: theme.mode === 'system' }"
            title="跟随系统"
            @click="theme.setMode('system')"
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <rect x="2" y="3" width="20" height="14" rx="2"/><path d="M8 21h8M12 17v4"/>
            </svg>
          </button>
        </div>
      </header>

      <!-- 居中表单卡片 -->
      <main class="auth-panel__main">
        <div class="auth-card">
          <!-- 标题区域 -->
          <div class="auth-card__head">
            <h1 class="auth-card__title">欢迎回来</h1>
            <p class="auth-card__subtitle">登录您的 AgentHub 账号以继续</p>
          </div>

          <!-- 表单实体 -->
          <form class="auth-card__form" @submit.prevent="submit">
            <!-- 一体式集成卡槽 (账号、密码、验证码合一) -->
            <div class="field-stack" :class="{ 'has-focus': !!focusedField }">
              <!-- 账号行 -->
              <div class="field-row" :class="{ active: focusedField === 'username' }">
                <div class="field-row__label">
                  <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                    <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/>
                  </svg>
                </div>
                <input
                  id="username"
                  v-model="form.username"
                  type="text"
                  class="field-row__input"
                  placeholder="账号 / 邮箱"
                  autocomplete="username"
                  spellcheck="false"
                  @focus="focusedField = 'username'"
                  @blur="focusedField = ''"
                />
              </div>

              <div class="field-stack__divider"></div>

              <!-- 密码行 -->
              <div class="field-row" :class="{ active: focusedField === 'password' }">
                <div class="field-row__label">
                  <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                    <rect x="3" y="11" width="18" height="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/>
                  </svg>
                </div>
                <input
                  id="password"
                  v-model="form.password"
                  :type="showPassword ? 'text' : 'password'"
                  class="field-row__input"
                  placeholder="登录密码"
                  autocomplete="current-password"
                  @focus="focusedField = 'password'"
                  @blur="focusedField = ''"
                />
                <button
                  type="button"
                  class="field-row__action"
                  :aria-label="showPassword ? '隐藏密码' : '显示密码'"
                  tabindex="-1"
                  @click="showPassword = !showPassword"
                >
                  <svg v-if="showPassword" viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                    <path d="M2 12s3.5-6 10-6 10 6 10 6-3.5 6-10 6-10-6-10-6z"/><circle cx="12" cy="12" r="2.6"/>
                  </svg>
                  <svg v-else viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                    <path d="M2 12s3.5-6 10-6 10 6 10 6-3.5 6-10 6-10-6-10-6z"/><circle cx="12" cy="12" r="2.6"/><path d="M4 4l16 16"/>
                  </svg>
                </button>
              </div>

              <!-- 验证码行 (若开启) -->
              <template v-if="captchaEnabled">
                <div class="field-stack__divider"></div>
                <div class="field-row field-row--captcha" :class="{ active: focusedField === 'code' }">
                  <div class="field-row__label">
                    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                      <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>
                    </svg>
                  </div>
                  <input
                    id="captcha"
                    v-model="form.code"
                    type="text"
                    class="field-row__input"
                    placeholder="验证码计算结果"
                    autocomplete="off"
                    spellcheck="false"
                    @focus="focusedField = 'code'"
                    @blur="focusedField = ''"
                  />
                  <div class="captcha-badge" title="点击刷新验证码" @click="loadCaptcha">
                    <img v-if="codeUrl" :src="codeUrl" alt="验证码" class="captcha-badge__img" />
                    <span v-else class="captcha-badge__placeholder">加载中</span>
                  </div>
                </div>
              </template>
            </div>

            <!-- 错误提示 -->
            <transition name="fade">
              <div v-if="error" class="form-error" role="alert">
                <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
                  <circle cx="12" cy="12" r="9"/><path d="M12 8v5"/><path d="M12 16.5v.01"/>
                </svg>
                <span>{{ error }}</span>
              </div>
            </transition>

            <!-- 登录主按钮 -->
            <button type="submit" class="submit-btn" :disabled="loading || oauthLoading">
              <span v-if="loading" class="btn-spinner" aria-hidden="true"></span>
              <span>{{ loading ? '正在验证凭证…' : '登 录' }}</span>
              <svg v-if="!loading" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <path d="M5 12h14M12 5l7 7-7 7"/>
              </svg>
            </button>

            <!-- SSO / OAuth 分隔与快捷入口 -->
            <div class="oauth-section">
              <div class="oauth-divider">
                <span>或通过以下方式继续</span>
              </div>
              <button
                type="button"
                class="oauth-btn"
                :disabled="oauthLoading || loading"
                @click="handleOauth"
              >
                <svg width="17" height="17" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
                  <path d="M12 2C6.477 2 2 6.477 2 12c0 4.418 2.865 8.166 6.839 9.489.5.092.682-.217.682-.483 0-.237-.009-.866-.014-1.7-2.782.604-3.369-1.34-3.369-1.34-.455-1.155-1.11-1.463-1.11-1.463-.908-.62.069-.608.069-.608 1.004.071 1.532 1.031 1.532 1.031.892 1.529 2.341 1.087 2.91.831.091-.646.349-1.087.635-1.337-2.221-.253-4.556-1.111-4.556-4.943 0-1.092.39-1.985 1.03-2.684-.103-.253-.447-1.27.098-2.647 0 0 .84-.269 2.75 1.025A9.564 9.564 0 0 1 12 6.756c.85.004 1.705.115 2.504.337 1.909-1.294 2.748-1.025 2.748-1.025.546 1.377.202 2.394.1 2.647.64.699 1.028 1.592 1.028 2.684 0 3.842-2.339 4.687-4.568 4.935.359.31.678.92.678 1.854 0 1.338-.012 2.419-.012 2.749 0 .268.18.58.688.482A10.001 10.001 0 0 0 22 12c0-5.523-4.477-10-10-10Z" />
                </svg>
                <span>{{ oauthLoading ? '等待 GitHub 授权…' : '使用 GitHub 授权登录' }}</span>
              </button>
              <p class="oauth-hint">将打开新标签完成授权，完成后自动回到侧边栏</p>
            </div>
          </form>
        </div>
      </main>

      <!-- 底部条款与版权声明 -->
      <footer class="auth-panel__footer">
        <div class="legal-links">
          <span>服务条款</span>
          <span class="legal-dot">·</span>
          <span>隐私政策</span>
          <span class="legal-dot">·</span>
          <span>© {{ new Date().getFullYear() }} AgentHub</span>
        </div>
      </footer>
    </div>
  </div>
</template>

<style scoped lang="scss">
/* ═══════════════════════════════════════════════════════════════
   AgentHub 侧边栏 · 一体式卡槽设计 (Unified Field Stack)
   ═══════════════════════════════════════════════════════════════ */

.auth-layout {
  position: relative;
  width: 100%;
  height: 100%;
  display: flex;
  background-color: var(--bg);
  color: var(--text);
  overflow: hidden;
  user-select: none;
  font-family: -apple-system, BlinkMacSystemFont, "SF Pro Text", "Segoe UI", Roboto, "Helvetica Neue", sans-serif;
}

.auth-panel {
  position: relative;
  z-index: 2;
  flex: 1;
  min-width: 0;
  height: 100%;
  display: flex;
  flex-direction: column;
  background: transparent;

  &__header {
    height: 52px;
    padding: 0 16px;
    display: flex;
    align-items: center;
    justify-content: space-between;
    flex-shrink: 0;
  }

  &__brand {
    display: flex;
    align-items: center;
    gap: 8px;
  }

  &__logo {
    width: 22px;
    height: 22px;
  }

  &__name {
    font-size: 15px;
    font-weight: 700;
    color: var(--text);
    letter-spacing: -0.01em;
  }

  &__main {
    flex: 1;
    min-height: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 8px 18px 16px;
    overflow-y: auto;
  }

  &__footer {
    height: 46px;
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 0 16px;
    flex-shrink: 0;
  }
}

/* 主题分段控制器 */
.theme-toggle {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  padding: 3px;
  background: var(--ai-fill-2, var(--bg-hover));
  border: 1px solid var(--border);
  border-radius: 999px;
  backdrop-filter: blur(8px);

  &__btn {
    width: 26px;
    height: 26px;
    border-radius: 50%;
    display: flex;
    align-items: center;
    justify-content: center;
    color: var(--text-tertiary);
    background: transparent;
    border: none;
    cursor: pointer;
    transition: all 0.14s ease;

    &:hover {
      color: var(--text);
    }

    &.active {
      background: var(--bg-raised);
      color: var(--text);
      box-shadow: 0 1px 3px rgba(30, 22, 47, 0.08);
    }
  }
}

/* ─────────────────────────────────────────────────────────────
   表单卡片
   ───────────────────────────────────────────────────────────── */
.auth-card {
  width: 100%;
  max-width: 340px;
  display: flex;
  flex-direction: column;
  animation: card-appear 0.4s cubic-bezier(0.16, 1, 0.3, 1) both;

  &__head {
    margin-bottom: 20px;
  }

  &__title {
    font-size: 24px;
    font-weight: 700;
    letter-spacing: -0.02em;
    color: var(--text);
    margin: 0 0 6px;
  }

  &__subtitle {
    font-size: 13px;
    color: var(--text-secondary);
    margin: 0;
    line-height: 1.45;
  }

  &__form {
    display: flex;
    flex-direction: column;
    gap: 14px;
  }
}

@keyframes card-appear {
  from {
    opacity: 0;
    transform: translateY(10px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

/* ==========================================================================
   一体式集成卡槽 (Unified Inset Field Stack) - 账号和密码在一块，中间细线分割
   ========================================================================== */
.field-stack {
  display: flex;
  flex-direction: column;
  background: var(--bg-raised);
  border: 1px solid var(--border-strong);
  border-radius: 12px;
  overflow: hidden;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.04);
  transition: border-color 0.16s ease, box-shadow 0.16s ease;

  &.has-focus {
    border-color: var(--accent);
    box-shadow: 0 0 0 3px var(--accent-weak);
  }

  &__divider {
    height: 1px;
    background: var(--divider);
    width: 100%;
  }
}

.field-row {
  display: flex;
  align-items: center;
  height: 44px;
  padding: 0 12px;
  transition: background-color 0.12s ease;

  &.active {
    background: var(--ai-fill-1, rgba(37, 99, 235, 0.03));
  }

  &__label {
    display: flex;
    align-items: center;
    justify-content: center;
    color: var(--text-tertiary);
    margin-right: 10px;
    flex-shrink: 0;
    transition: color 0.14s ease;
  }

  &.active &__label {
    color: var(--accent);
  }

  &__input {
    flex: 1;
    min-width: 0;
    height: 100%;
    border: none !important;
    outline: none !important;
    box-shadow: none !important;
    background: transparent !important;
    color: var(--text) !important;
    font-size: 13.5px;
    font-family: inherit;

    &::placeholder {
      color: var(--text-tertiary);
      font-size: 13px;
    }
  }

  &__action {
    display: flex;
    align-items: center;
    justify-content: center;
    background: transparent;
    border: none;
    color: var(--text-tertiary);
    cursor: pointer;
    padding: 6px;
    border-radius: 6px;
    transition: color 0.12s ease;

    &:hover {
      color: var(--text);
    }
  }

  &--captcha {
    padding-right: 6px;
  }
}

/* 验证码徽章 (紧凑贴合在卡槽内部) */
.captcha-badge {
  width: 92px;
  height: 34px;
  border-radius: 8px;
  background: var(--bg-elevated, var(--bg-raised));
  border: 1px solid var(--border);
  overflow: hidden;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  transition: opacity 0.14s ease, border-color 0.14s ease;

  &:hover {
    opacity: 0.85;
    border-color: var(--accent);
  }

  &__img {
    width: 100%;
    height: 100%;
    object-fit: fill;
  }

  &__placeholder {
    font-size: 11px;
    color: var(--text-tertiary);
  }
}

/* 错误提示条 */
.form-error {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 12px;
  background: var(--danger-weak);
  border: 1px solid var(--danger-border);
  border-radius: 8px;
  color: var(--danger-text);
  font-size: 12.5px;

  svg { flex-shrink: 0; }
}

.fade-enter-active,
.fade-leave-active {
  transition: all 0.18s ease;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}

/* 提交主按钮 */
.submit-btn {
  height: 42px;
  border-radius: 10px;
  background: var(--accent);
  color: #ffffff;
  border: none;
  font-size: 14px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  cursor: pointer;
  margin-top: 2px;
  box-shadow: 0 2px 8px rgba(37, 99, 235, 0.28);
  transition: all 0.14s ease;

  &:hover:not(:disabled) {
    background: var(--accent-hover);
    box-shadow: 0 4px 14px rgba(37, 99, 235, 0.38);
  }

  &:active:not(:disabled) {
    transform: scale(0.99);
  }

  &:disabled {
    opacity: 0.55;
    cursor: not-allowed;
    box-shadow: none;
  }
}

.btn-spinner {
  width: 14px;
  height: 14px;
  border: 2px solid rgba(255, 255, 255, 0.3);
  border-top-color: #fff;
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* SSO / OAuth */
.oauth-section {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 4px;
}

.oauth-divider {
  display: flex;
  align-items: center;
  gap: 12px;
  color: var(--text-tertiary);
  font-size: 11.5px;

  &::before, &::after {
    content: '';
    flex: 1;
    height: 1px;
    background: var(--divider);
  }
}

.oauth-btn {
  height: 40px;
  border-radius: 10px;
  background: var(--bg-raised);
  border: 1px solid var(--border-strong);
  color: var(--text);
  font-size: 13px;
  font-weight: 500;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  cursor: pointer;
  transition: all 0.14s ease;

  &:hover:not(:disabled) {
    background: var(--bg-hover);
    border-color: var(--accent);
  }

  &:disabled {
    opacity: 0.55;
    cursor: not-allowed;
  }
}

.oauth-hint {
  margin: 0;
  text-align: center;
  font-size: 11.5px;
  color: var(--text-tertiary);
  line-height: 1.4;
}

/* 底部法律与版权 */
.legal-links {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--text-tertiary);
}

.legal-dot {
  color: var(--border-strong);
}
</style>
