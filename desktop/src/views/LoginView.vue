<script setup>
import { onMounted, reactive, ref } from 'vue'
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
      window.location.assign(auth.oauthAuthorizationUrl)
    } else {
      toast('GitHub 授权服务暂未开通或正在连接')
    }
  } catch (e) {
    toast('连接 GitHub 授权登录失败：' + (e.message || '网络异常'))
  } finally {
    oauthLoading.value = false
  }
}

onMounted(async () => {
  await auth.initOauth().catch(() => {})

  const ticket = route.query.oauthTicket
  if (ticket) {
    oauthLoading.value = true
    try {
      await auth.loginByOauth(String(ticket))
      router.replace(route.query.redirect || '/')
      return
    } catch (e) {
      error.value = e.message || '单点登录未完成'
      oauthLoading.value = false
    }
  }
  if (route.query.oauthError) {
    toast('单点登录未完成：账号未开通或授权失败')
  }
  loadCaptcha()
})
</script>

<template>
  <div class="auth-layout">
    <!-- 左侧视觉面板：只有粒子星云，不放任何内容层 -->
    <aside class="auth-hero" aria-hidden="true">
      <div class="auth-hero__grid"></div>
      <AuthHeroBackdrop />
    </aside>

    <!-- ── 右侧登录表单主面板 (1:1 对标 Aivory AuthLayout / Login) ── -->
    <div class="auth-panel">
      <!-- 顶部状态栏: 移动端 Logo + 主题切换器 -->
      <header class="auth-panel__header">
        <div class="auth-panel__mobile-brand">
          <img :src="logoMark" alt="AgentHub" class="auth-panel__mobile-logo" />
          <span class="auth-panel__mobile-name">AgentHub</span>
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
            <!-- 账号输入 -->
            <div class="form-group">
              <label class="form-label" for="username">账号 / 邮箱</label>
              <div class="input-wrap">
                <svg class="input-icon" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/>
                </svg>
                <input
                  id="username"
                  v-model="form.username"
                  type="text"
                  class="form-input"
                  placeholder="请输入您的用户名或邮箱"
                  autocomplete="username"
                  spellcheck="false"
                />
              </div>
            </div>

            <!-- 密码输入 -->
            <div class="form-group">
              <div class="form-label-row">
                <label class="form-label" for="password">安全密码</label>
              </div>
              <div class="input-wrap">
                <svg class="input-icon" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                  <rect x="3" y="11" width="18" height="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/>
                </svg>
                <input
                  id="password"
                  v-model="form.password"
                  :type="showPassword ? 'text' : 'password'"
                  class="form-input"
                  placeholder="请输入密码"
                  autocomplete="current-password"
                />
                <button
                  type="button"
                  class="input-toggle"
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
            </div>

            <!-- 验证码 (若开启) -->
            <div v-if="captchaEnabled" class="form-group">
              <label class="form-label" for="captcha">计算验证码</label>
              <div class="captcha-row">
                <div class="input-wrap captcha-input-wrap">
                  <svg class="input-icon" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                    <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>
                  </svg>
                  <input
                    id="captcha"
                    v-model="form.code"
                    type="text"
                    class="form-input"
                    placeholder="计算结果"
                    autocomplete="off"
                    spellcheck="false"
                  />
                </div>
                <div class="captcha-image-badge" title="点击刷新验证码" @click="loadCaptcha">
                  <img v-if="codeUrl" :src="codeUrl" alt="验证码" class="captcha-image" />
                  <span v-else class="captcha-loading">加载中</span>
                </div>
              </div>
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
                <span>使用 GitHub 授权登录</span>
              </button>
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
/* ==========================================================================
   Aivory 官方双栏认证布局规范 (1:1 像素级还原)
   ========================================================================== */

.auth-layout {
  width: 100vw;
  height: 100vh;
  display: flex;
  background-color: var(--bg);
  color: var(--text);
  overflow: hidden;
  user-select: none;
  font-family: -apple-system, BlinkMacSystemFont, "SF Pro Text", "Segoe UI", Roboto, "Helvetica Neue", sans-serif;
}

/* ─────────────────────────────────────────────────────────────
   左侧品牌面板：蓝白静态信息展示
   ───────────────────────────────────────────────────────────── */
.auth-hero {
  position: relative;
  display: none;
  flex: 0 0 44%;
  height: 100%;
  background: linear-gradient(145deg, #eff6ff 0%, #f8fbff 58%, #e6f0ff 100%);
  border-right: 1px solid var(--border);
  overflow: hidden;
  align-items: center;
  justify-content: center;

  @media (min-width: 992px) {
    display: flex;
  }

  /* 深色下换成夜空底，否则浅色渐变会把粒子和辉光全冲淡 */
  :global(html.dark) &,
  :global([data-theme='dark']) & {
    background: radial-gradient(120% 90% at 50% 38%, #101c33 0%, #0a1120 55%, #060c13 100%);
  }

  &__grid {
    position: absolute;
    inset: 0;
    pointer-events: none;
    background-image:
      linear-gradient(rgba(37, 99, 235, 0.055) 1px, transparent 1px),
      linear-gradient(90deg, rgba(37, 99, 235, 0.055) 1px, transparent 1px);
    background-size: 32px 32px;
    mask-image: linear-gradient(to bottom, black, transparent 82%);
  }

}

:global(html.dark .auth-hero) {
  background:
    radial-gradient(720px 520px at 8% 8%, rgba(79, 141, 204, 0.09), transparent 58%),
    linear-gradient(145deg, #060c13 0%, #0c121a 58%, #101b28 100%);
}

:global(html.dark .auth-hero .auth-hero__grid) {
  background-image:
    linear-gradient(rgba(151, 164, 180, 0.055) 1px, transparent 1px),
    linear-gradient(90deg, rgba(151, 164, 180, 0.055) 1px, transparent 1px);
}

/* ─────────────────────────────────────────────────────────────
   右侧表单主面板 (Aivory AuthPanel)
   ───────────────────────────────────────────────────────────── */
.auth-panel {
  flex: 1;
  min-width: 0;
  height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--bg);

  &__header {
    height: 64px;
    padding: 0 24px;
    display: flex;
    align-items: center;
    justify-content: space-between;
    flex-shrink: 0;
  }

  &__mobile-brand {
    display: flex;
    align-items: center;
    gap: 8px;

    @media (min-width: 992px) {
      visibility: hidden;
    }
  }

  &__mobile-logo {
    width: 22px;
    height: 22px;
  }

  &__mobile-name {
    font-size: 15px;
    font-weight: 700;
    color: var(--text);
  }

  &__main {
    flex: 1;
    min-height: 0;
    display: grid;
    place-items: center;
    padding: 24px;
    overflow-y: auto;
  }

  &__footer {
    height: 64px;
    align-items: center;
    justify-content: center;
    padding: 0 20px;
    flex-shrink: 0;
  }
}

/* 主题分段控制器 */
.theme-toggle {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  padding: 3px;
  background: var(--ai-fill-2);
  border: 1px solid var(--border);
  border-radius: 999px;

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
   表单卡片 (Aivory Form Card)
   ───────────────────────────────────────────────────────────── */
.auth-card {
  width: 100%;
  max-width: 380px;
  display: flex;
  flex-direction: column;
  animation: card-appear 0.4s cubic-bezier(0.16, 1, 0.3, 1) both;

  &__head {
    margin-bottom: 24px;
  }

  &__title {
    font-size: 24px;
    font-weight: 700;
    letter-spacing: -0.02em;
    color: var(--text);
    margin: 0 0 6px;
  }

  &__subtitle {
    font-size: 13.5px;
    color: var(--text-secondary);
    margin: 0;
  }

  &__form {
    display: flex;
    flex-direction: column;
    gap: 16px;
  }
}

@keyframes card-appear {
  from {
    opacity: 0;
    transform: translateY(12px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

/* 表单组与输入框 */
.form-group {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.form-label-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.form-label {
  font-size: 12.5px;
  font-weight: 550;
  color: var(--text-secondary);
}

.input-wrap {
  position: relative;
  display: flex;
  align-items: center;
  width: 100%;
  height: 42px;
  background: var(--bg-raised);
  border: 1px solid var(--border-strong);
  border-radius: 10px;
  transition: all 0.14s ease;

  &:focus-within {
    border-color: var(--accent);
    box-shadow: 0 0 0 3px var(--accent-weak);
  }
}

.input-icon {
  color: var(--text-tertiary);
  margin-left: 12px;
  margin-right: 8px;
  flex-shrink: 0;
}

.form-input {
  flex: 1;
  min-width: 0;
  height: 100%;
  padding-right: 12px;
  border: none !important;
  background: transparent !important;
  outline: none !important;
  color: var(--text) !important;
  font-size: 13.5px;
  font-family: inherit;

  &::placeholder {
    color: var(--text-tertiary);
    font-size: 13px;
  }
}

.input-toggle {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 8px 12px;
  background: transparent;
  border: none;
  color: var(--text-tertiary);
  cursor: pointer;
  transition: color 0.12s ease;

  &:hover {
    color: var(--text);
  }
}

/* 验证码排列 */
.captcha-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.captcha-input-wrap {
  flex: 1;
}

.captcha-image-badge {
  width: 104px;
  height: 42px;
  border-radius: 10px;
  background: var(--bg-raised);
  border: 1px solid var(--border-strong);
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
}

.captcha-image {
  width: 100%;
  height: 100%;
  object-fit: fill;
}

.captcha-loading {
  font-size: 11px;
  color: var(--text-tertiary);
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

/* 记住密码 */
.form-extra-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 2px;
}

.checkbox-label {
  display: flex;
  align-items: center;
  gap: 7px;
  cursor: pointer;
}

.checkbox-input {
  accent-color: var(--accent);
  width: 14px;
  height: 14px;
  cursor: pointer;
}

.checkbox-text {
  font-size: 12.5px;
  color: var(--text-secondary);
}

/* 提交主按钮 (Aivory 电紫高亮按钮) */
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
  margin-top: 6px;
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
  gap: 12px;
  margin-top: 6px;
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
