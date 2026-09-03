<template>
  <div class="register">
    <!-- 动态光斑 -->
    <div class="aurora aurora-1"></div>
    <div class="aurora aurora-2"></div>

    <div class="register-card">
      <div class="brand">
        <img :src="logo" alt="logo" class="brand-logo" />
        <div class="brand-name">{{ brandName }}</div>
        <div class="brand-slogan">创建你的平台账号</div>
      </div>
      <el-form ref="registerRef" :model="registerForm" :rules="registerRules" class="register-form">
        <el-form-item prop="username">
          <el-input
            v-model="registerForm.username"
            type="text"
            size="large"
            auto-complete="off"
            placeholder="账号"
          >
            <template #prefix><svg-icon icon-class="user" class="el-input__icon input-icon" /></template>
          </el-input>
        </el-form-item>
        <el-form-item prop="password" :rules="registerPwdValidator">
          <el-input
            v-model="registerForm.password"
            type="password"
            size="large"
            auto-complete="off"
            placeholder="密码"
            @keyup.enter="handleRegister"
          >
            <template #prefix><svg-icon icon-class="password" class="el-input__icon input-icon" /></template>
          </el-input>
        </el-form-item>
        <el-form-item prop="confirmPassword">
          <el-input
            v-model="registerForm.confirmPassword"
            type="password"
            size="large"
            auto-complete="off"
            placeholder="确认密码"
            @keyup.enter="handleRegister"
          >
            <template #prefix><svg-icon icon-class="password" class="el-input__icon input-icon" /></template>
          </el-input>
        </el-form-item>
        <el-form-item prop="code" v-if="captchaEnabled">
          <el-input
            size="large"
            v-model="registerForm.code"
            auto-complete="off"
            placeholder="验证码"
            style="width: 63%"
            @keyup.enter="handleRegister"
          >
            <template #prefix><svg-icon icon-class="validCode" class="el-input__icon input-icon" /></template>
          </el-input>
          <div class="register-code">
            <img :src="codeUrl" @click="getCode" class="register-code-img"/>
          </div>
        </el-form-item>
        <el-form-item style="width:100%;">
          <el-button
            :loading="loading"
            size="large"
            type="primary"
            class="register-button"
            style="width:100%;"
            @click.prevent="handleRegister"
          >
            <span v-if="!loading">注 册</span>
            <span v-else>注 册 中...</span>
          </el-button>
          <div style="float: right;">
            <router-link class="link-type" :to="'/login'">使用已有账户登录</router-link>
          </div>
        </el-form-item>
      </el-form>
    </div>
    <!--  底部  -->
    <div class="el-register-footer">
      <span>{{ footerContent }}</span>
    </div>
  </div>
</template>

<script setup>
import { ElMessageBox } from "element-plus"
import { getCodeImg, register } from "@/api/login"
import defaultSettings from '@/settings'
import { usePasswordRule } from "@/utils/passwordRule"
import logo from '@/assets/logo/logo.svg'

const title = import.meta.env.VITE_APP_TITLE || 'AgentHub'
const brandName = title.split(' ')[0]
const footerContent = defaultSettings.footerContent
const router = useRouter()
const { proxy } = getCurrentInstance()
const { registerPwdValidator } = usePasswordRule()

const registerForm = ref({
  username: "",
  password: "",
  confirmPassword: "",
  code: "",
  uuid: ""
})

const equalToPassword = (rule, value, callback) => {
  if (registerForm.value.password !== value) {
    callback(new Error("两次输入的密码不一致"))
  } else {
    callback()
  }
}

const registerRules = {
  username: [
    { required: true, trigger: "blur", message: "请输入您的账号" },
    { min: 2, max: 20, message: "用户账号长度必须介于 2 和 20 之间", trigger: "blur" }
  ],
  confirmPassword: [
    { required: true, trigger: "blur", message: "请再次输入您的密码" },
    { required: true, validator: equalToPassword, trigger: "blur" }
  ],
  code: [{ required: true, trigger: "change", message: "请输入验证码" }]
}

const codeUrl = ref("")
const loading = ref(false)
const captchaEnabled = ref(true)

function handleRegister() {
  proxy.$refs.registerRef.validate(valid => {
    if (valid) {
      loading.value = true
      register(registerForm.value).then(res => {
        const username = registerForm.value.username
        ElMessageBox.alert("<font color='red'>恭喜你，您的账号 " + username + " 注册成功！</font>", "系统提示", {
          dangerouslyUseHTMLString: true,
          type: "success",
        }).then(() => {
          router.push("/login")
        }).catch(() => {})
      }).catch(() => {
        loading.value = false
        if (captchaEnabled) {
          getCode()
        }
      })
    }
  })
}

function getCode() {
  getCodeImg().then(res => {
    captchaEnabled.value = res.captchaEnabled === undefined ? true : res.captchaEnabled
    if (captchaEnabled.value) {
      codeUrl.value = "data:image/gif;base64," + res.img
      registerForm.value.uuid = res.uuid
    }
  })
}

getCode()
</script>

<style lang='scss' scoped>
.register {
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

/* ===== 注册卡片：入场动画 ===== */
.register-card {
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.92);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  width: 420px;
  padding: 32px 36px 14px 36px;
  z-index: 1;
  box-shadow: 0 24px 64px rgba(8, 47, 73, 0.35);
  animation: card-in 0.6s cubic-bezier(0.22, 0.9, 0.3, 1) both;
}
@keyframes card-in {
  from { opacity: 0; transform: translateY(24px) scale(0.98); }
  to { opacity: 1; transform: translateY(0) scale(1); }
}

.brand {
  text-align: center;
  margin-bottom: 24px;

  .brand-logo {
    width: 48px;
    height: 48px;
    filter: drop-shadow(0 6px 16px rgba(14, 165, 233, 0.45));
    animation: float 4s ease-in-out infinite;
  }

  .brand-name {
    margin-top: 10px;
    font-size: 24px;
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
    letter-spacing: 2px;
  }
}
@keyframes float {
  0%, 100% { transform: translateY(0); }
  50% { transform: translateY(-6px); }
}

.register-form {
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

  :deep(.el-input__wrapper) {
    transition: box-shadow 0.2s ease;
    border-radius: 8px;
  }
  :deep(.el-input__wrapper.is-focus) {
    box-shadow: 0 0 0 1px #06B6D4 inset, 0 0 0 4px rgba(6, 182, 212, 0.15);
  }

  .register-button {
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

.register-code {
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
.register-code-img {
  height: 42px;
  padding-left: 12px;
  border-radius: 6px;
}

.el-register-footer {
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

html.dark .register {
  background-image: linear-gradient(rgba(0, 0, 0, 0.45), rgba(0, 0, 0, 0.45)), url("../assets/images/login-bg.png");

  .register-card {
    background: rgba(29, 30, 31, 0.88);
    box-shadow: 0 24px 64px rgba(0, 0, 0, 0.55);
  }
}

@media (prefers-reduced-motion: reduce) {
  .aurora, .brand-logo, .register-card {
    animation: none !important;
  }
}
</style>
