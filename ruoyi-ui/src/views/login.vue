<template>
  <div class="login">
    <!-- 动态光斑 -->
    <div class="aurora aurora-1"></div>
    <div class="aurora aurora-2"></div>

    <div class="login-card">
      <div class="brand">
        <img :src="logo" alt="logo" class="brand-logo" />
        <div class="brand-name">{{ brandName }}</div>
        <div class="brand-slogan">云上通用智能体平台</div>
      </div>
      <el-form ref="loginRef" :model="loginForm" :rules="loginRules" class="login-form">
        <el-form-item prop="username">
          <el-input
            v-model="loginForm.username"
            type="text"
            size="large"
            auto-complete="off"
            placeholder="账号"
          >
            <template #prefix><svg-icon icon-class="user" class="el-input__icon input-icon" /></template>
          </el-input>
        </el-form-item>
        <el-form-item prop="password">
          <el-input
            v-model="loginForm.password"
            type="password"
            size="large"
            auto-complete="off"
            placeholder="密码"
            @keyup.enter="handleLogin"
          >
            <template #prefix><svg-icon icon-class="password" class="el-input__icon input-icon" /></template>
          </el-input>
        </el-form-item>
        <el-form-item prop="code" v-if="captchaEnabled">
          <el-input
            v-model="loginForm.code"
            size="large"
            auto-complete="off"
            placeholder="验证码"
            style="width: 63%"
            @keyup.enter="handleLogin"
          >
            <template #prefix><svg-icon icon-class="validCode" class="el-input__icon input-icon" /></template>
          </el-input>
          <div class="login-code">
            <img :src="codeUrl" @click="getCode" class="login-code-img"/>
          </div>
        </el-form-item>
        <el-checkbox v-model="loginForm.rememberMe" style="margin:0px 0px 25px 0px;">记住密码</el-checkbox>
        <el-form-item style="width:100%;">
          <el-button
            :loading="loading"
            size="large"
            type="primary"
            class="login-button"
            style="width:100%;"
            @click.prevent="handleLogin"
          >
            <span v-if="!loading">登 录</span>
            <span v-else>登 录 中...</span>
          </el-button>
          <div style="float: right;" v-if="register">
            <router-link class="link-type" :to="'/register'">立即注册</router-link>
          </div>
        </el-form-item>
        <template v-if="oauthEnabled">
          <div class="oauth-divider"><span>或</span></div>
          <el-button
            size="large"
            class="oauth-button github-login-button"
            :loading="oauthLoading"
            @click="handleOauthLogin"
          >
            <svg v-if="!oauthLoading" class="github-icon" viewBox="0 0 24 24" aria-hidden="true">
              <path fill="currentColor" d="M12 2C6.477 2 2 6.477 2 12c0 4.418 2.865 8.166 6.839 9.489.5.092.682-.217.682-.483 0-.237-.009-.866-.014-1.7-2.782.604-3.369-1.34-3.369-1.34-.455-1.155-1.11-1.463-1.11-1.463-.908-.62.069-.608.069-.608 1.004.071 1.532 1.031 1.532 1.031.892 1.529 2.341 1.087 2.91.831.091-.646.349-1.087.635-1.337-2.221-.253-4.556-1.111-4.556-4.943 0-1.092.39-1.985 1.03-2.684-.103-.253-.447-1.27.098-2.647 0 0 .84-.269 2.75 1.025A9.564 9.564 0 0 1 12 6.756c.85.004 1.705.115 2.504.337 1.909-1.294 2.748-1.025 2.748-1.025.546 1.377.202 2.394.1 2.647.64.699 1.028 1.592 1.028 2.684 0 3.842-2.339 4.687-4.568 4.935.359.31.678.92.678 1.854 0 1.338-.012 2.419-.012 2.749 0 .268.18.58.688.482A10.001 10.001 0 0 0 22 12c0-5.523-4.477-10-10-10Z" />
            </svg>
            <span>使用 GitHub 登录</span>
          </el-button>
        </template>
      </el-form>
    </div>
    <!--  底部  -->
    <div class="el-login-footer">
      <span>{{ footerContent }}</span>
    </div>
  </div>
</template>

<script setup>
import { getCodeImg, getOauthLoginConfig } from "@/api/login"
import Cookies from "js-cookie"
import { encrypt, decrypt } from "@/utils/jsencrypt"
import useUserStore from '@/store/modules/user'
import defaultSettings from '@/settings'
import logo from '@/assets/logo/logo.svg'

const title = import.meta.env.VITE_APP_TITLE || 'AgentHub'
const brandName = title.split(' ')[0]
const footerContent = defaultSettings.footerContent
const userStore = useUserStore()
const route = useRoute()
const router = useRouter()
const { proxy } = getCurrentInstance()

const loginForm = ref({
  username: "",
  password: "",
  rememberMe: false,
  code: "",
  uuid: ""
})

const loginRules = {
  username: [{ required: true, trigger: "blur", message: "请输入您的账号" }],
  password: [{ required: true, trigger: "blur", message: "请输入您的密码" }],
  code: [{ required: true, trigger: "change", message: "请输入验证码" }]
}

const codeUrl = ref("")
const loading = ref(false)
const oauthLoading = ref(false)
const oauthEnabled = ref(false)
const oauthAuthorizationUrl = ref("")
// 验证码开关
const captchaEnabled = ref(true)
// 注册开关
const register = ref(false)
const redirect = ref(undefined)

watch(route, (newRoute) => {
    redirect.value = newRoute.query && newRoute.query.redirect
}, { immediate: true })

function handleLogin() {
  proxy.$refs.loginRef.validate(valid => {
    if (valid) {
      loading.value = true
      // 勾选了需要记住密码设置在 cookie 中设置记住用户名和密码
      if (loginForm.value.rememberMe) {
        Cookies.set("username", loginForm.value.username, { expires: 30 })
        Cookies.set("password", encrypt(loginForm.value.password), { expires: 30 })
        Cookies.set("rememberMe", loginForm.value.rememberMe, { expires: 30 })
      } else {
        // 否则移除
        Cookies.remove("username")
        Cookies.remove("password")
        Cookies.remove("rememberMe")
      }
      // 调用action的登录方法
      userStore.login(loginForm.value).then(redirectAfterLogin).catch(() => {
        loading.value = false
        // 重新获取验证码
        if (captchaEnabled.value) {
          getCode()
        }
      })
    }
  })
}

function redirectAfterLogin() {
  const query = route.query
  const otherQueryParams = Object.keys(query).reduce((acc, cur) => {
    if (cur !== "redirect" && cur !== "oauthTicket" && cur !== "oauthError") {
      acc[cur] = query[cur]
    }
    return acc
  }, {})
  router.push({ path: redirect.value || "/", query: otherQueryParams })
}

function handleOauthLogin() {
  if (!oauthAuthorizationUrl.value) {
    proxy.$modal.msgError("统一登录暂不可用，请稍后重试")
    return
  }
  oauthLoading.value = true
  window.location.assign(oauthAuthorizationUrl.value)
}

function initOauthLogin() {
  getOauthLoginConfig().then(res => {
    oauthEnabled.value = Boolean(res.enabled)
    oauthAuthorizationUrl.value = res.authorizationUrl || ""
  }).catch(() => {
    // OAuth 是可选能力，获取失败不影响用户名密码登录。
    oauthEnabled.value = false
  })

  const ticket = route.query.oauthTicket
  if (ticket) {
    oauthLoading.value = true
    userStore.loginByOauth(ticket).then(redirectAfterLogin).catch(() => {
      oauthLoading.value = false
    })
  }
  if (route.query.oauthError) {
    proxy.$modal.msgError("统一登录未完成：账号未开通、已停用或授权失败")
  }
}

function getCode() {
  getCodeImg().then(res => {
    captchaEnabled.value = res.captchaEnabled === undefined ? true : res.captchaEnabled
    if (captchaEnabled.value) {
      codeUrl.value = "data:image/gif;base64," + res.img
      loginForm.value.uuid = res.uuid
    }
  })
}

function getCookie() {
  const username = Cookies.get("username")
  const password = Cookies.get("password")
  const rememberMe = Cookies.get("rememberMe")
  loginForm.value = {
    username: username === undefined ? loginForm.value.username : username,
    password: password === undefined ? loginForm.value.password : decrypt(password),
    rememberMe: rememberMe === undefined ? false : Boolean(rememberMe)
  }
}

getCode()
getCookie()
initOauthLogin()
</script>

<style lang='scss' scoped>
.login {
  display: flex;
  justify-content: center;
  align-items: center;
  height: 100%;
  position: relative;
  overflow: hidden;
  background-image: url("../assets/images/login-bg.png");
  background-size: cover;
  background-position: center;
}

/* ===== 动态光斑 ===== */
.aurora {
  position: absolute;
  border-radius: 50%;
  filter: blur(90px);
  pointer-events: none;
  z-index: 0;
}
.aurora-1 {
  width: 480px;
  height: 480px;
  background: radial-gradient(circle, rgba(14, 165, 233, 0.45), transparent 70%);
  top: -120px;
  left: -100px;
  animation: drift 14s ease-in-out infinite alternate;
}
.aurora-2 {
  width: 520px;
  height: 520px;
  background: radial-gradient(circle, rgba(45, 212, 191, 0.35), transparent 70%);
  bottom: -160px;
  right: -120px;
  animation: drift 18s ease-in-out infinite alternate-reverse;
}
@keyframes drift {
  from { transform: translate(0, 0) scale(1); }
  to { transform: translate(60px, 40px) scale(1.15); }
}

/* ===== 登录卡片：入场动画 ===== */
.login-card {
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.92);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  width: 420px;
  padding: 36px 36px 14px 36px;
  z-index: 1;
  box-shadow: 0 24px 64px rgba(8, 47, 73, 0.35);
  animation: card-in 0.6s cubic-bezier(0.22, 0.9, 0.3, 1) both;
}

.oauth-divider {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 2px 0 16px;
  color: #94a3b8;
  font-size: 13px;

  &::before,
  &::after {
    content: '';
    flex: 1;
    height: 1px;
    background: #e2e8f0;
  }
}

.oauth-button {
  width: 100%;
  margin-bottom: 18px;
  color: #fff;
  border-color: #24292f;
  background: #24292f;
  font-weight: 600;

  &:hover {
    color: #fff;
    border-color: #0f1114;
    background: #0f1114;
  }
}

.github-login-button {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 9px;
}

.github-icon {
  width: 20px;
  height: 20px;
}
@keyframes card-in {
  from { opacity: 0; transform: translateY(24px) scale(0.98); }
  to { opacity: 1; transform: translateY(0) scale(1); }
}

.brand {
  text-align: center;
  margin-bottom: 28px;

  .brand-logo {
    width: 56px;
    height: 56px;
    filter: drop-shadow(0 6px 16px rgba(14, 165, 233, 0.45));
    animation: float 4s ease-in-out infinite;
  }

  .brand-name {
    margin-top: 12px;
    font-size: 26px;
    font-weight: 700;
    letter-spacing: 1px;
    background: linear-gradient(135deg, #0EA5E9, #06B6D4);
    -webkit-background-clip: text;
    background-clip: text;
    color: transparent;
  }

  .brand-slogan {
    margin-top: 6px;
    font-size: 13px;
    color: #909399;
    letter-spacing: 4px;
  }
}
@keyframes float {
  0%, 100% { transform: translateY(0); }
  50% { transform: translateY(-6px); }
}

.login-form {
  .el-input {
    height: 42px;
    input {
      height: 42px;
    }
  }
  .input-icon {
    height: 39px;
    width: 14px;
    margin-left: 0px;
  }

  /* 输入框聚焦发光 */
  :deep(.el-input__wrapper) {
    transition: box-shadow 0.2s ease;
    border-radius: 8px;
  }
  :deep(.el-input__wrapper.is-focus) {
    box-shadow: 0 0 0 1px #06B6D4 inset, 0 0 0 4px rgba(6, 182, 212, 0.15);
  }

  /* 登录按钮：渐变 + 悬浮反馈 */
  .login-button {
    border-radius: 8px;
    font-size: 15px;
    letter-spacing: 6px;
    background: linear-gradient(135deg, #0EA5E9, #06B6D4);
    border: none;
    box-shadow: 0 8px 20px rgba(14, 165, 233, 0.35);
    transition: transform 0.15s ease, box-shadow 0.2s ease;

    &:hover, &:focus {
      background: linear-gradient(135deg, #0284C7, #0891B2);
      transform: translateY(-1px);
      box-shadow: 0 12px 26px rgba(14, 165, 233, 0.45);
    }
    &:active {
      transform: translateY(0);
      box-shadow: 0 6px 14px rgba(14, 165, 233, 0.3);
    }
  }
}

.login-code {
  width: 33%;
  height: 42px;
  float: right;
  img {
    cursor: pointer;
    vertical-align: middle;
    transition: transform 0.15s ease;
    &:hover { transform: scale(1.03); }
  }
}
.login-code-img {
  height: 42px;
  padding-left: 12px;
  border-radius: 6px;
}

.el-login-footer {
  height: 40px;
  line-height: 40px;
  position: fixed;
  bottom: 0;
  width: 100%;
  text-align: center;
  color: rgba(255, 255, 255, 0.75);
  font-family: Arial;
  font-size: 12px;
  letter-spacing: 1px;
  z-index: 1;
}

html.dark .login {
  background-image: linear-gradient(rgba(0, 0, 0, 0.45), rgba(0, 0, 0, 0.45)), url("../assets/images/login-bg.png");

  .login-card {
    background: rgba(29, 30, 31, 0.88);
    box-shadow: 0 24px 64px rgba(0, 0, 0, 0.55);
  }
}

/* 尊重系统减弱动效设置 */
@media (prefers-reduced-motion: reduce) {
  .aurora, .brand-logo, .login-card {
    animation: none !important;
  }
}
</style>
