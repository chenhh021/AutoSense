<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { register } from '@/api/userController'
import { getApiErrorMessage } from '@/utils/apiError'

const router = useRouter()
const submitting = ref(false)
const form = reactive<API.RegisterRequest>({
  userAccount: '',
  userPassword: '',
  confirmPassword: '',
})

const validatePasswordConfirmation = (_rule: unknown, value: string) => {
  if (!value) return Promise.reject('请再次输入密码')
  if (value !== form.userPassword) return Promise.reject('两次输入的密码不一致')
  return Promise.resolve()
}

const submit = async () => {
  submitting.value = true
  try {
    await register(form)
    message.success('注册成功，请登录')
    await router.replace({ name: 'login', query: { account: form.userAccount } })
  } catch (error) {
    message.error(getApiErrorMessage(error, '注册失败，请稍后重试'))
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="auth-page">
    <a-card class="auth-card" :bordered="false">
      <div class="auth-heading">
        <span class="eyebrow">创建账号</span>
        <h1>加入 AutoSense</h1>
        <p>注册普通用户账号，开始管理设备与诊断会话。</p>
      </div>

      <a-form :model="form" layout="vertical" @finish="submit">
        <a-form-item
          label="账号"
          name="userAccount"
          :rules="[
            { required: true, message: '请输入账号' },
            { pattern: /^[A-Za-z0-9_]{4,32}$/, message: '请输入 4~32 位字母、数字或下划线' },
          ]"
        >
          <a-input
            v-model:value="form.userAccount"
            size="large"
            autocomplete="username"
            placeholder="4~32 位字母、数字或下划线"
          />
        </a-form-item>
        <a-form-item
          label="密码"
          name="userPassword"
          :rules="[
            { required: true, message: '请输入密码' },
            {
              pattern: /^(?=.*[A-Za-z])(?=.*\d).{8,64}$/,
              message: '请输入 8~64 位且同时包含字母和数字的密码',
            },
          ]"
        >
          <a-input-password
            v-model:value="form.userPassword"
            size="large"
            autocomplete="new-password"
            placeholder="8~64 位，须同时包含字母和数字"
          />
        </a-form-item>
        <a-form-item
          label="确认密码"
          name="confirmPassword"
          :rules="[{ validator: validatePasswordConfirmation, trigger: 'change' }]"
        >
          <a-input-password
            v-model:value="form.confirmPassword"
            size="large"
            autocomplete="new-password"
            placeholder="请再次输入密码"
          />
        </a-form-item>
        <a-button type="primary" html-type="submit" size="large" block :loading="submitting">
          注册
        </a-button>
      </a-form>

      <p class="auth-switch">
        已有账号？
        <router-link to="/login">返回登录</router-link>
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
    radial-gradient(circle at 80% 10%, rgb(22 119 255 / 12%), transparent 32%),
    transparent;
}

.auth-card {
  width: min(100%, 460px);
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
