<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { signIn } from '@/stores/auth'
import { getApiErrorMessage } from '@/utils/apiError'

const route = useRoute()
const router = useRouter()
const submitting = ref(false)
const form = reactive<API.LoginRequest>({
  userAccount: typeof route.query.account === 'string' ? route.query.account : '',
  userPassword: '',
})

const redirectTarget = computed(() => {
  const redirect = route.query.redirect
  return typeof redirect === 'string' && redirect.startsWith('/') && !redirect.startsWith('//')
    ? redirect
    : '/console'
})

const submit = async () => {
  submitting.value = true
  try {
    await signIn(form)
    message.success('登录成功')
    await router.replace(redirectTarget.value)
  } catch (error) {
    message.error(getApiErrorMessage(error, '账号或密码错误，或账号已被禁用'))
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="auth-page">
    <a-card class="auth-card" :bordered="false">
      <div class="auth-heading">
        <span class="eyebrow">欢迎回来</span>
        <h1>登录 AutoSense</h1>
        <p>登录后继续管理设备和诊断会话。</p>
      </div>

      <a-form :model="form" layout="vertical" @finish="submit">
        <a-form-item
          label="账号"
          name="userAccount"
          :rules="[{ required: true, message: '请输入账号' }]"
        >
          <a-input
            v-model:value="form.userAccount"
            size="large"
            autocomplete="username"
            placeholder="请输入账号"
          />
        </a-form-item>
        <a-form-item
          label="密码"
          name="userPassword"
          :rules="[{ required: true, message: '请输入密码' }]"
        >
          <a-input-password
            v-model:value="form.userPassword"
            size="large"
            autocomplete="current-password"
            placeholder="请输入密码"
          />
        </a-form-item>
        <a-button type="primary" html-type="submit" size="large" block :loading="submitting">
          登录
        </a-button>
      </a-form>

      <p class="auth-switch">
        还没有账号？
        <router-link to="/register">立即注册</router-link>
      </p>
    </a-card>
  </div>
</template>

<style scoped>
.auth-page {
  display: grid;
  place-items: center;
  min-height: calc(100vh - 64px - 128px);
  padding: 40px 16px;
  background:
    radial-gradient(circle at 20% 10%, rgb(22 119 255 / 12%), transparent 32%),
    transparent;
}

.auth-card {
  width: min(100%, 440px);
  border-radius: 16px;
  box-shadow: 0 16px 48px rgb(15 35 62 / 12%);
}

.auth-heading {
  margin-bottom: 28px;
  text-align: center;
}

.eyebrow {
  color: #1677ff;
  font-weight: 600;
}

.auth-heading h1 {
  margin: 8px 0;
  font-size: 28px;
}

.auth-heading p,
.auth-switch {
  color: rgb(0 0 0 / 55%);
}

.auth-switch {
  margin: 24px 0 0;
  text-align: center;
}
</style>
